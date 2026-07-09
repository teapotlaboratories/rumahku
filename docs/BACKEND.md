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
- Output format: `.ply` (works with our viewer) vs `.spz`/`.splat` (smaller).
- Auth + upload size limits (captures are ~tens of MB of JPEGs).
- Which GPU box is the "own GPU" for dev.
