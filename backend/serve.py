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
# When this backend runs inside a distrobox, the container's Vulkan falls back to
# CPU (the NVIDIA userspace only works on the host), so training is ~20x slower.
# Run brush-cli on the HOST via distrobox-host-exec instead — paths are identical
# (the home is a shared mount) and the host's NVIDIA Vulkan drives the GPU.
# Set HOST_EXEC="" to force in-container execution.
HOST_EXEC = os.environ.get("HOST_EXEC", shutil.which("distrobox-host-exec") or "")
# nerfstudio (Splatfacto/gsplat) runs from its official Docker image on the GPU
# via podman — it has CUDA + gsplat prebuilt, avoiding an in-container install.
NERF_IMAGE = os.environ.get("NERF_IMAGE", "ghcr.io/nerfstudio-project/nerfstudio:latest")
os.makedirs(JOBS_DIR, exist_ok=True)

JOBS = {}            # job_id -> {state, queued_at, started, iter, total, error, ply}
LOCK = threading.Lock()
WORK_Q = queue.Queue()   # (job_id, ds_dir, iters, max_res) awaiting a worker
ITER_RE = re.compile(rb"iter[=\s]+(\d+)")
PSNR_RE = re.compile(rb"PSNR\s+([\d.]+)\s+SSIM\s+([\d.]+)")


def _worker():
    """Pull queued jobs and train them one at a time (per worker), so many
    concurrent uploads don't thrash the GPU — they queue instead."""
    while True:
        job_id, ds_dir, iters, max_res, eval_split, trainer = WORK_Q.get()
        try:
            with LOCK:
                j = JOBS.get(job_id)
                if j is None or j.get("state") in ("error", "cancelled"):
                    continue  # gone/cancelled while queued
                j.update(state="running", started=time.time())
            if trainer == "splatfacto":
                _run_splatfacto(job_id, ds_dir, iters, max_res, eval_split)
            else:
                _run_job(job_id, ds_dir, iters, max_res, eval_split)
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


def _run_job(job_id: str, ds_dir: str, iters: int, max_res: int, eval_split: int = 0):
    out_dir = os.path.join(ds_dir, "out")
    os.makedirs(out_dir, exist_ok=True)
    log_path = os.path.join(ds_dir, "brush.log")
    brush_cmd = [
        BRUSH, ds_dir,
        "--total-train-iters", str(iters),
        "--max-resolution", str(max_res),
        "--export-every", str(iters),
        "--export-path", out_dir + "/",
        "--export-name", "export_{iter}.ply",
    ]
    # Hold out every Nth view for evaluation so Brush computes + logs PSNR/SSIM
    # (a quality metric on unseen views). Off by default — full views = best
    # quality; enable per-request with ?eval_split=N.
    if eval_split and eval_split > 0:
        brush_cmd += ["--eval-split-every", str(eval_split)]
    # RUST_LOG=info makes Brush emit periodic `iter=N` lines (its progress bar is
    # silent without a TTY), which we parse for real progress. When running on the
    # host, pass it through `env` since host-exec doesn't forward our environment.
    if HOST_EXEC:
        cmd = [HOST_EXEC, "env", "RUST_LOG=info", *brush_cmd]
        env = dict(os.environ)
    else:
        cmd = brush_cmd
        env = dict(os.environ, RUST_LOG="info")
    try:
        with open(log_path, "wb") as lf:
            proc = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT, cwd=ds_dir, env=env)
        with LOCK:
            JOBS[job_id]["proc"] = proc      # so DELETE can terminate it
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
        if JOBS[job_id].get("state") == "cancelled":
            pass                              # user cancelled — leave it
        elif rc == 0 and plys:
            JOBS[job_id].update(state="done", ply=plys[-1], iter=iters)
        else:
            tail = _log_tail(log_path)
            JOBS[job_id].update(state="error", error=f"exit {rc}; {tail}")


def _run_splatfacto(job_id: str, ds_dir: str, iters: int, max_res: int, eval_split: int = 0):
    """Train with nerfstudio Splatfacto (gsplat) in the official Docker image on
    the GPU (podman --gpus). Trains from the nerfstudio dataset (transforms.json)
    and exports a .ply. CUDA works in a CDI/--gpus container (unlike Vulkan)."""
    out_dir = os.path.join(ds_dir, "ns_out")
    log_path = os.path.join(ds_dir, "brush.log")   # shared log path (status polls it)
    os.makedirs(out_dir, exist_ok=True)
    inner = (
        "set -e; "
        f"ns-train splatfacto --data /ws --output-dir /ws/ns_out "
        f"--max-num-iterations {iters} --viewer.quit-on-train-completion True "
        f"--pipeline.model.cull_alpha_thresh 0.005 "
        f"nerfstudio-data --downscale-factor 1 --eval-mode fraction; "
        "CFG=$(find /ws/ns_out -name config.yml | head -1); "
        "ns-export gaussian-splat --load-config \"$CFG\" --output-dir /ws/ns_out/export"
    )
    podman = [
        "podman", "run", "--rm", "--gpus", "all", "--security-opt=label=disable",
        "-v", f"{ds_dir}:/ws", NERF_IMAGE, "bash", "-lc", inner,
    ]
    cmd = ([HOST_EXEC] if HOST_EXEC else []) + podman
    try:
        with open(log_path, "wb") as lf:
            proc = subprocess.Popen(cmd, stdout=lf, stderr=subprocess.STDOUT, cwd=ds_dir)
        with LOCK:
            JOBS[job_id]["proc"] = proc
        while proc.poll() is None:
            time.sleep(2.0)
            _update_iter(job_id, log_path)   # best-effort progress/PSNR from the log
        rc = proc.returncode
    except Exception as e:  # noqa: BLE001
        with LOCK:
            JOBS[job_id].update(state="error", error=f"splatfacto spawn: {e}")
        return
    plys = sorted(glob.glob(os.path.join(out_dir, "export", "*.ply")))
    with LOCK:
        if JOBS[job_id].get("state") == "cancelled":
            pass
        elif rc == 0 and plys:
            JOBS[job_id].update(state="done", ply=plys[-1], iter=iters)
        else:
            JOBS[job_id].update(state="error", error=f"splatfacto exit {rc}; {_log_tail(log_path)}")


def _update_iter(job_id: str, log_path: str):
    try:
        with open(log_path, "rb") as f:
            f.seek(max(0, os.path.getsize(log_path) - 16384))
            tail = f.read()
        m = ITER_RE.findall(tail)
        p = PSNR_RE.findall(tail)
        with LOCK:
            if m:
                JOBS[job_id]["iter"] = int(m[-1])
            if p:
                JOBS[job_id]["psnr"] = float(p[-1][0])
                JOBS[job_id]["ssim"] = float(p[-1][1])
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
        eval_split = int(q.get("eval_split", ["0"])[0])   # hold out every Nth view for PSNR
        trainer = q.get("trainer", ["brush"])[0]          # brush | splatfacto
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
                            "started": None, "iter": 0, "total": iters, "trainer": trainer}
        WORK_Q.put((job_id, root, iters, max_res, eval_split, trainer))
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
            if j.get("psnr") is not None:
                out["psnr"] = round(j["psnr"], 2)
                out["ssim"] = round(j.get("ssim", 0.0), 3)
            if j.get("error"):
                out["error"] = j["error"]
            return self._json(200, out)
        self._json(404, {"error": "not found"})

    def do_DELETE(self):
        """Cancel a job: kill the training process (if running) and mark it
        cancelled. A queued job is skipped when a worker reaches it."""
        parts = self.path.strip("/").split("/")
        if len(parts) >= 2 and parts[0] == "jobs":
            job_id = parts[1]
            with LOCK:
                j = JOBS.get(job_id)
                if not j:
                    return self._json(404, {"error": "no such job"})
                proc = j.get("proc")
                j["state"] = "cancelled"
            if proc is not None and proc.poll() is None:
                try:
                    proc.terminate()
                except Exception:  # noqa: BLE001
                    pass
            return self._json(200, {"ok": True, "state": "cancelled"})
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
