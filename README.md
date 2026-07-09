# rumahku

**Scan a room on your phone, walk it in 3D.**

rumahku is a phone-only 3D room scanner. You capture a space with an ordinary
Android phone, it reconstructs a **Gaussian splat**, and you walk through it in a
fullscreen, Matterport-style viewer — no LiDAR, no depth sensor required.

Because a clean, dense reconstruction isn't feasible on a sensor-less phone,
rumahku uses the same split Polycam does: **capture on-device, reconstruct on a
GPU, view on-device.**

## How it works

```
 Phone (capture)                Cloud GPU (reconstruct)            Phone (view)
 ───────────────                ──────────────────────            ────────────
 ARCore images + poses          Brush trains a Gaussian            In-app splat
 + seed point cloud     ──►     splat on the GPU         ──►       walkthrough
 (sharpness-gated)              (backend/serve.py)                 (level camera)
 live coverage mesh             returns a .ply                     drag / pinch / move
```

- **Capture** — ARCore drives the camera; keyframes (image + pose + intrinsics)
  are saved only when the phone is steady and the frame is sharp (a
  Laplacian-variance gate). ARCore feature points become a `seed.ply`. A live
  TSDF + marching-cubes **coverage mesh** shows what's been scanned, and on-screen
  cues ("hold steady / move slower") guide the capture.
- **Reconstruct** — the capture is uploaded to a GPU backend that trains the
  splat with [Brush](https://github.com/ArthurBrussee/brush) and returns the
  `.ply`. Tiers: **Cloud · Fast / Balanced / High**, plus an **On-device**
  offline path. Builds run in the background (detachable, resumable, cancellable)
  and report live iteration progress + a **PSNR/SSIM** quality metric.
- **View** — an in-app renderer walks the splat with a **level-horizon camera**
  (free look, no roll) that tracks your finger in real time.

## Repository layout

| Path | What |
|---|---|
| `app/` | The Android app (Kotlin, Jetpack Compose, ARCore). Splat rendering is via a Rust FFI (`libbrush_ffi.so`). |
| `backend/` | The cloud reconstruction service — `serve.py` (zero-dependency Python job service) and a `Dockerfile` for GPU hosts. |
| `docs/` | Architecture, backend design, milestones, and worklogs. |
| `tools/` | Dev helpers (device unlock, etc.). |
| `TODO.md` | Parked / upcoming work. |

The Rust splat trainer + FFI (`brush-cli`, `libbrush_ffi.so`) are built from a
fork of Brush kept in a sibling repo.

## Build the app

```sh
./gradlew :app:assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
```

Primary target: **Pixel 6** (ARCore + arm64-v8a). Prebuilt APKs are attached to
[releases](https://github.com/teapotlaboratories/rumahku/releases).

## Run the backend

The backend trains splats with `brush-cli` on an NVIDIA GPU.

```sh
# native
BRUSH_CLI=./brush-cli PORT=8000 python3 backend/serve.py

# or containerized on a cloud GPU host (nvidia-container-runtime)
docker build -t rumahku-backend backend/
docker run --gpus all -p 8000:8000 rumahku-backend
```

Endpoints: `POST /jobs?iters=&max_res=&eval_split=` (dataset.zip) → `{job_id}`;
`GET /jobs/{id}` (status incl. `psnr`/`ssim`); `GET /jobs/{id}/result` (the
`.ply`); `DELETE /jobs/{id}` (cancel). Point the app's backend-URL setting at it.

See [`docs/BACKEND.md`](docs/BACKEND.md) for the architecture and
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) for the on-device pipeline.

## Docs

- [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) — on-device pipeline
- [`docs/BACKEND.md`](docs/BACKEND.md) — hybrid cloud reconstruction
- **Reference** (`docs/reference/`) — industry surveys guiding the target architecture:
  - [`capture-coverage-visualization.md`](docs/reference/capture-coverage-visualization.md) — live coverage overlay UX
  - [`pipeline-tuning-guide.md`](docs/reference/pipeline-tuning-guide.md) — RGB→SfM→3DGS→mesh/floor-plan tuning
  - [`production-architecture.md`](docs/reference/production-architecture.md) — end-to-end cloud platform
- [`docs/MILESTONES.md`](docs/MILESTONES.md) — roadmap / milestones
- [`TODO.md`](TODO.md) — upcoming work
