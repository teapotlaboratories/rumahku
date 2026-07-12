# TODO

See `README.md` for the project overview.

## Focus: reconstruction quality
Measured (295-image scene, 1080px, 15k iters, PSNR/SSIM on 37 held-out views —
all one controlled A/B on the GPU box, same data/iters/eval-split):
| Init | PSNR | SSIM |
|---|---|---|
| Images only (no SfM points) | 16.7 | 0.68 |
| Raw ARCore poses + `seed.ply` (11k pts) — **the app today** | 23.37 | 0.768 |
| COLMAP pose-prior refine + 104k pts (`?refine=colmap`) | **23.65** | **0.806** |

**Conclusions:**
1. **SfM point init is the dominant lever** — no points → 16.7; ARCore's 11k
   seed → 23.4 (**+6.6 dB**). The app already ships `seed.ply`, so it's on the
   good side of this cliff.
2. **Pose-prior refine is a modest, real boost** over that: +0.3 dB / +0.04 SSIM
   (the earlier "+1.4 dB" from a single manual run was within Brush's run-to-run
   variance). The SSIM gain is the more visible win. Costs ~5 min (CPU SIFT).
3. **Capture quality is still the ceiling** ("~80% of quality" per the tuning
   guide) — refinement can't add detail the capture never recorded.

- [x] More iters / higher res — tested; PSNR plateaus ~24 (not the fix).
- [x] Pose accuracy — COLMAP pose-prior refine, measured +0.3 dB / +0.04 SSIM.
- [x] **Productionize pose-prior refinement** — `backend/colmap_refine.py` +
      `?refine=colmap` in `serve.py`; deployed + validated end-to-end on the box
      (refine → train-on-refined → export; job shows a `phase:"refine"` stage).
- **Capture quality (biggest lever) — in progress:**
  - [x] Fixed focus (no refocus blur, stable intrinsics).
  - [x] Denser keyframes (7 cm / 8°) for ~70–80% overlap.
  - [ ] **Lock AE/AWB + fast shutter** — needs `SharedCamera` (ARCore + Camera2)
        to set `SENSOR_EXPOSURE_TIME` + `CONTROL_AE_LOCK`/`CONTROL_AWB_LOCK`;
        photometric consistency matters. Needs on-device exposure tuning.
  - [ ] Capture-technique guidance UX (perimeter orbit, multi-height, loop closure).
- [x] Wire `?refine=colmap` into the app — "Cloud · High+" tier; the build UI
      now shows a "Refining poses…" phase (home card + watch screen).
- [x] **Fair Splatfacto bake-off done** — feeding the same 104k-pt COLMAP model
      to Splatfacto (15k iters, same scene/eval-mode) lifts it **17.9 → 20.8 dB**
      (+2.9 dB), confirming the earlier 19.7 was handicapped by missing SfM init.
      But Splatfacto @15k still trails **Brush @15k (23.65)** on our data even
      with equal init (caveats: eval splits differ — Brush every-8th vs
      nerfstudio fraction; Splatfacto prefers 30k). **Verdict: keep Brush as the
      default trainer**; the lever remains capture + SfM init, not the trainer.
- [x] Splatfacto live progress + PSNR — parse ns-train's "N (pct%)" steps for a
      live ring; report final PSNR/SSIM via `ns-eval` (its eval is console-silent,
      so no per-step PSNR like Brush).
- [x] **Quality gating (QA gate)** — backend surfaces LPIPS alongside PSNR/SSIM,
      an advisory accept-band verdict (good/fair/poor/review), and SfM health
      (triangulated points / mean reprojection error / track length via
      `colmap model_analyzer`). The app shows them in the per-scan metrics dialog
      so a thin/poor capture is flagged for a re-scan instead of silently shipping.
- [x] **Preserve metric scale** — disable nerfstudio pose normalization on both
      dataparser paths (`--auto-scale-poses False --center-method none
      --orientation-method none`) so the exported `.ply` stays in ARCore meters —
      prerequisite for in-app measurements (see production-architecture note).
- [ ] Intrinsics / lens distortion (OPENCV camera model vs pinhole).

Infra note: the **RTX 5060 Ti (GPU1) can't run the nerfstudio CUDA-11.8 image**
("no kernel image" — arch too new), so Splatfacto/COLMAP-CUDA jobs only run on
the RTX 3080 (GPU0). Brush (Vulkan) is unaffected.

## Backlog
### Backend → cloud GPU
- [ ] Validate `backend/Dockerfile` on a real cloud GPU (RunPod / vast.ai).
      (Image now also ships `colmap`+`numpy` for the refine path; still unvalidated.)
- [x] **Token auth on `/jobs`** — opt-in `RUMAHKU_TOKEN` bearer check (`/health`
      stays open); off by default for the laptop. Wired end-to-end: the app sends
      `Authorization: Bearer <token>` from Settings → "Access token" on every
      /jobs request (upload/poll/download/cancel). Server + client tested.
- [x] **`.dockerignore` + deploy README** — `backend/.dockerignore`; ops/deploy
      section in `docs/BACKEND.md` (env vars, deploy steps, retention, auth).
- [ ] Serverless option (RunPod Serverless / Modal) for scale-to-zero.
- [x] **Job retention / cleanup** — background reaper keeps the newest
      `JOB_RETAIN_MAX` (20) finished job dirs + reaps any older than
      `JOB_RETAIN_HOURS` (168h); queued/running untouched. Freed 13 GB on rollout.

### App features
- [ ] Batch export — extend the home multi-select to export scans (`.ply`/`.zip`).
- [ ] On-device reconstruction cancel (cloud cancel exists; offline path doesn't).

## Resolved
- [x] **Backend survives reboot** — persistent, enabled `systemd --user` service;
      verified by a live reboot (container + backend auto-recover, GPU intact).
- [x] **Cloud GPU speed** — train on the host via `distrobox-host-exec` (~14–19×).
