# Hybrid reconstruction backend — design

Polycam does the heavy reconstruction (photogrammetry, **Gaussian splats**) in
the cloud, not on the phone — even they only do the *live coverage mesh*
on-device (via LiDAR). rumahku follows the same split:

```
 PHONE (on-device)                        BACKEND (GPU)
 ─────────────────                        ─────────────
 capture: ARCore images + poses           POST /jobs  (dataset.zip) ─► job_id
   + colored seed + transforms.json  ───►
 raw-depth coverage mesh (live)           train splat with Brush on a real GPU
                                          (minutes, high iters, high quality)
 poll GET /jobs/{id}  ◄───────────────    { state, progress, iter }
 download .ply  ◄─────────────────────    GET /jobs/{id}/result
 walkthrough viewer (already built)
```

**Reuse:** capture + the walkthrough viewer are done. The backend runs the **same
Brush** trainer we already use on-device — it's `wgpu`→Vulkan/Metal, not
Android-specific, so on a real GPU it's fast + high quality (the on-device build
is the *capped* version of the exact same thing). One reconstruction codebase.

## Job API (stateless — serverless-ready)
Plain HTTP, async job model so long trains + cold starts are fine:
- `POST /jobs` — multipart upload of a capture bundle (`transforms.json`,
  `images/*.jpg`, `seed.ply`) or a single `dataset.zip`. Returns `{ job_id }`.
- `GET /jobs/{id}` — `{ state: queued|running|done|error, iter, total, message }`.
- `GET /jobs/{id}/result` — the exported `.ply` (or `.spz`/`.splat`).
- (optional) `GET /jobs/{id}/preview` — a mid-train render thumbnail.

Design rules that keep it portable to serverless GPU (Modal / RunPod / Replicate
/ Beam):
1. **Stateless per job**: input dataset → output ply, no shared server state.
   Store datasets/results in object storage (S3/GCS) or stream directly.
2. **Containerized**: one Docker image with the GPU runtime (Vulkan for Brush)
   + the Brush binary + a thin HTTP/worker wrapper. Runs identically on our box
   (`docker run --gpus`/`--device`) and on a serverless GPU platform.
3. **Idempotent + resumable-ish**: a job is a pure function of its dataset.

## Components to build
1. **`backend/`** — a small service:
   - `api` (FastAPI or similar): the 3 endpoints + a job queue (in-proc for own
     GPU; the platform's queue for serverless).
   - `worker`: unzip dataset → run Brush (`brush-cli run_headless` / the training
     core) with the requested iters → export `.ply` → mark done, expose progress.
   - `Dockerfile`: CUDA/Vulkan base + Brush + the wrapper.
2. **Phone**: after capture, **"Build in cloud"** → zip the capture → `POST /jobs`
   → poll → download `.ply` → save into the scan → open the walkthrough. Reuse
   `ReconstructionActivity`'s progress UI, pointed at the remote job instead of
   the local `ReconstructionService`. Keep on-device Brush as an **offline**
   fallback (the differentiator: works with no network).

## Host reality (now)
- `argonite` (this dev box): only a weak **Radeon R7 Carrizo iGPU** + Vulkan, no
  Docker. Brush might limp for tiny tests; not a real training host.
- Need a proper GPU box (`carbonite-noble`? a desktop NVIDIA?) for real training,
  or a serverless GPU for production. The container design means the *same* image
  runs on whichever we pick.

## Brush headless binary (new — no CLI exists in the fork)
The fork has no train CLI (only `brush-ffi` + library crates). So build a small
binary — a new `brush-server` bin crate (or a `main.rs` in a thin crate) that:
`dataset-dir + iters + out.ply` → runs `brush_process::create_process` headless
(the same call chain as `brush-ffi`'s `run_train`) → exports the `.ply`, printing
progress to stdout/JSON. The HTTP worker just shells out to this binary per job.
Builds for the server target (x86-64 + Vulkan/CUDA), reusing the training core.

## Open decisions
- Output format: `.ply` (works with our viewer) vs `.spz`/`.splat` (smaller).
- Upload size limits (captures are ~tens of MB of JPEGs).
- Which GPU box is the "own GPU" for dev.

## Operations (carbonite-noble, current dev backend)
The backend runs as a **`systemd --user` service** (`rumahku-backend.service`)
inside a distrobox on `carbonite-noble` (the maintainer's laptop — a dev backend,
**not** production). Brush trains on the host GPU via `distrobox-host-exec`;
Splatfacto runs from the nerfstudio image via host `podman --gpus all`. Two GPUs:
an RTX 3080 (10 GB, GPU 0 — runs gsplat) and an RTX 5060 Ti (16 GB, GPU 1 — too
new for the CUDA-11.8 nerfstudio image, so Splatfacto/COLMAP-CUDA pin to GPU 0).

**Env vars** (all optional; defaults in parens):
| Var | Default | Purpose |
|---|---|---|
| `PORT` | `8000` | listen port |
| `JOBS_DIR` | `~/rumahku/jobs` | per-job uploads + training output |
| `MAX_CONCURRENT` | `1` | jobs training at once (GPU-memory bound) |
| `HOST_EXEC` | auto (`distrobox-host-exec`) | run Brush/podman on the host; `""` forces in-container |
| `NERF_IMAGE` | `ghcr.io/nerfstudio-project/nerfstudio:latest` | Splatfacto image |
| `RUMAHKU_TOKEN` | `""` (off) | bootstrap bearer token; if set, `/jobs` requires `Authorization: Bearer <token>` (`/health` stays open). Usually left empty — prefer named credentials (below). |
| `RUMAHKU_CREDS` | `~/rumahku/credentials.json` | file of named, per-device tokens managed by the credctl TUI |
| `JOB_RETAIN_MAX` | `20` | keep the newest N finished job dirs |
| `JOB_RETAIN_HOURS` | `168` | also delete finished dirs older than this |
| `JOB_REAP_EVERY_S` | `3600` | reaper sweep interval |

**Credentials (auth):** a request to `/jobs` is allowed if its `Authorization:
Bearer <token>` matches `RUMAHKU_TOKEN` **or** any *active* credential in
`RUMAHKU_CREDS`. With neither, the backend is **open** (the single-user default).
Manage per-device tokens with the curses TUI — run it over SSH on the box:
```sh
python3 rumahku_credctl.py          # a=add  ⏎=reveal  r=revoke/enable  d=delete
python3 rumahku_credctl.py --list   # or --add "<name>"  (non-interactive)
```
serve.py re-reads the file on its next request (mtime-cached), so add/revoke
takes effect **live, no restart**. Flow to turn auth on: add a credential → the
backend immediately requires it → paste that token into the app (Settings →
"Access token"). Adding the *first* credential flips the backend from open to
locked, so set the app token right after (an in-flight app that lacks it gets
401s until you do). Revoking/deleting the last active credential reopens it.

**Retention:** a background reaper bounds `JOBS_DIR` — it keeps the newest
`JOB_RETAIN_MAX` finished dirs and deletes any finished dir older than
`JOB_RETAIN_HOURS`. Queued/running jobs are never touched. It sweeps hourly and
once at startup, logging each deletion to stdout (`journalctl --user -u
rumahku-backend`, or `docker logs`). Ground-truth check: `du -sh $JOBS_DIR` and
its dir count. (On the carbonite-noble user-service the journal currently drops
python stdout — a box quirk, not the app — so use `du` there.)

**Deploy** (edit → copy → restart; no build step, zero-dep stdlib server):
```sh
scp backend/serve.py backend/colmap_refine.py deck@carbonite-noble:<rumahku>/
ssh deck@carbonite-noble systemctl --user restart rumahku-backend.service
curl -s http://127.0.0.1:8000/health          # {"ok":true,...}
```

**Container image** (`backend/Dockerfile`, for a real cloud GPU — still
unvalidated end-to-end, see TODO): CUDA runtime + Vulkan for Brush, plus
`colmap` (CPU SIFT) + `numpy` for the refine path; a `.dockerignore` keeps the
build context to just the three copied files. Run:
`docker run --gpus all -p 8000:8000 -v rumahku-jobs:/data/jobs rumahku-backend`.
