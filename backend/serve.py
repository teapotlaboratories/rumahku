#!/usr/bin/env python3
"""
rumahku reconstruction backend — a zero-dependency job service that trains a
Gaussian splat from an uploaded capture using the Brush CLI on a GPU.

Stateless job model (serverless-ready): each job is a pure function of its
uploaded dataset. Endpoints:

  POST /jobs?iters=2000&max_res=1024   body = dataset.zip  -> {"job_id": "..."}
      the zip must contain a nerfstudio dataset (transforms.json + images/,
      optional seed.ply), at the root or in a single subfolder.
  GET  /jobs/{id}                       -> {"state": queued|running|done|error,
                                            "elapsed": s, "iter": n, ...}
  GET  /jobs/{id}/result                -> the exported .ply (octet-stream)
  GET  /health                          -> {"ok": true}

Runs the SAME Brush trainer as the on-device app, just on a real GPU. Uses only
the Python standard library so it runs anywhere python3 is present; the design
(stateless per-job, one binary) ports directly to a serverless GPU container.

Env: BRUSH_CLI (path to the brush-cli binary), JOBS_DIR, PORT.
"""
import glob
import io
import json
import os
import queue
import re
import shutil
import socketserver
import subprocess
import threading
import time
import uuid
import zipfile
from http.server import BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

BRUSH = os.environ.get("BRUSH_CLI", os.path.expanduser("~/rumahku/brush-cli"))
JOBS_DIR = os.environ.get("JOBS_DIR", os.path.expanduser("~/rumahku/jobs"))
PORT = int(os.environ.get("PORT", "8000"))
# How many jobs may train at once. GPU training is memory-bound, so the default
# is 1 (queue the rest) — bump it if the box has spare VRAM / multiple GPUs.
MAX_CONCURRENT = int(os.environ.get("MAX_CONCURRENT", "1"))
os.makedirs(JOBS_DIR, exist_ok=True)

JOBS = {}            # job_id -> {state, queued_at, started, iter, total, error, ply}
LOCK = threading.Lock()
WORK_Q = queue.Queue()   # (job_id, ds_dir, iters, max_res) awaiting a worker
ITER_RE = re.compile(rb"iter[=\s]+(\d+)")


def _worker():
    """Pull queued jobs and train them one at a time (per worker), so many
    concurrent uploads don't thrash the GPU — they queue instead."""
    while True:
        job_id, ds_dir, iters, max_res = WORK_Q.get()
        try:
            with LOCK:
                j = JOBS.get(job_id)
                if j is None or j.get("state") == "error":
                    continue  # gone/cancelled while queued
                j.update(state="running", started=time.time())
            _run_job(job_id, ds_dir, iters, max_res)
        finally:
            WORK_Q.task_done()


def _queue_position(job_id: str) -> int:
    """1-based position of a queued job among all queued jobs (0 if running)."""
    with LOCK:
        me = JOBS.get(job_id, {})
        if me.get("state") != "queued":
            return 0
        ahead = sum(
            1 for j in JOBS.values()
            if j.get("state") == "queued" and j.get("queued_at", 0) < me.get("queued_at", 0)
        )
    return ahead + 1


def _run_job(job_id: str, ds_dir: str, iters: int, max_res: int):
    out_dir = os.path.join(ds_dir, "out")
    os.makedirs(out_dir, exist_ok=True)
    log_path = os.path.join(ds_dir, "brush.log")
    cmd = [
        BRUSH, ds_dir,
        "--total-train-iters", str(iters),
        "--max-resolution", str(max_res),
        "--export-every", str(iters),
        "--export-path", out_dir + "/",
        "--export-name", "export_{iter}.ply",
    ]
    # RUST_LOG=info makes Brush emit periodic `iter=N` lines (its progress bar is
    # silent without a TTY), which we parse for real progress.
    env = dict(os.environ, RUST_LOG="info")
    try:
        with open(log_path, "wb") as lf:
            proc = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT, cwd=ds_dir, env=env)
        # Poll the log for iteration progress while the process runs.
        while proc.poll() is None:
            time.sleep(2.0)
            _update_iter(job_id, log_path)
        rc = proc.returncode
    except Exception as e:  # noqa: BLE001
        with LOCK:
            JOBS[job_id].update(state="error", error=f"spawn failed: {e}")
        return
    plys = sorted(glob.glob(os.path.join(out_dir, "*.ply")))
    with LOCK:
        if rc == 0 and plys:
            JOBS[job_id].update(state="done", ply=plys[-1], iter=iters)
        else:
            tail = _log_tail(log_path)
            JOBS[job_id].update(state="error", error=f"exit {rc}; {tail}")


def _update_iter(job_id: str, log_path: str):
    try:
        with open(log_path, "rb") as f:
            f.seek(max(0, os.path.getsize(log_path) - 8192))
            m = ITER_RE.findall(f.read())
        if m:
            with LOCK:
                JOBS[job_id]["iter"] = int(m[-1])
    except OSError:
        pass


def _log_tail(log_path: str, n: int = 400) -> str:
    try:
        with open(log_path, "rb") as f:
            f.seek(max(0, os.path.getsize(log_path) - n))
            return f.read().decode("utf-8", "replace").replace("\n", " ")[-n:]
    except OSError:
        return ""


def _find_dataset_root(base: str):
    """Locate the folder containing transforms.json (root or one subfolder)."""
    for root, _dirs, files in os.walk(base):
        if "transforms.json" in files:
            return root
    return None


class Handler(BaseHTTPRequestHandler):
    def _json(self, code, obj):
        body = json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if not self.path.startswith("/jobs"):
            return self._json(404, {"error": "not found"})
        q = parse_qs(urlparse(self.path).query)
        iters = int(q.get("iters", ["2000"])[0])
        max_res = int(q.get("max_res", ["1024"])[0])
        n = int(self.headers.get("Content-Length", "0"))
        if n <= 0:
            return self._json(400, {"error": "empty body (expected dataset.zip)"})
        data = self.rfile.read(n)
        job_id = uuid.uuid4().hex[:12]
        ds_dir = os.path.join(JOBS_DIR, job_id)
        os.makedirs(ds_dir, exist_ok=True)
        try:
            zipfile.ZipFile(io.BytesIO(data)).extractall(ds_dir)
        except Exception as e:  # noqa: BLE001
            return self._json(400, {"error": f"bad zip: {e}"})
        root = _find_dataset_root(ds_dir)
        if not root:
            return self._json(400, {"error": "no transforms.json in upload"})
        with LOCK:
            JOBS[job_id] = {"state": "queued", "queued_at": time.time(),
                            "started": None, "iter": 0, "total": iters}
        WORK_Q.put((job_id, root, iters, max_res))
        self._json(200, {"job_id": job_id})

    def do_GET(self):
        if self.path == "/health":
            return self._json(200, {"ok": True, "brush": os.path.exists(BRUSH)})
        parts = self.path.strip("/").split("/")
        if len(parts) >= 2 and parts[0] == "jobs":
            job_id = parts[1]
            with LOCK:
                j = JOBS.get(job_id)
            if not j:
                return self._json(404, {"error": "no such job"})
            if len(parts) >= 3 and parts[2] == "result":
                if j.get("state") != "done":
                    return self._json(409, {"error": f"state={j.get('state')}"})
                ply = j["ply"]
                self.send_response(200)
                self.send_header("Content-Type", "application/octet-stream")
                self.send_header("Content-Length", str(os.path.getsize(ply)))
                self.end_headers()
                with open(ply, "rb") as f:
                    shutil.copyfileobj(f, self.wfile)
                return
            started = j.get("started")
            out = {"state": j["state"],
                   "elapsed": round(time.time() - started) if started else 0,
                   "iter": j.get("iter", 0), "total": j.get("total", 0)}
            if j["state"] == "queued":
                out["queue_pos"] = _queue_position(job_id)
            if j.get("error"):
                out["error"] = j["error"]
            return self._json(200, out)
        self._json(404, {"error": "not found"})

    def log_message(self, *args):  # quiet
        pass


class Server(socketserver.ThreadingTCPServer):
    allow_reuse_address = True
    daemon_threads = True


if __name__ == "__main__":
    print(f"rumahku backend on :{PORT}  (brush={BRUSH}, jobs={JOBS_DIR}, "
          f"workers={MAX_CONCURRENT})", flush=True)
    for _ in range(MAX_CONCURRENT):
        threading.Thread(target=_worker, daemon=True).start()
    Server(("0.0.0.0", PORT), Handler).serve_forever()
