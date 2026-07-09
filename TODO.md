# TODO

See `README.md` for the project overview.

## Focus: reconstruction quality
Measured (295-image scene, 1080px, PSNR on held-out views):
- Raw ARCore poses: 23.3 @15k, 23.8 @30k (plateaus — more iters don't help).
- COLMAP pose-prior refine (all 295 registered): **24.7 @15k** (+1.4 dB).

**Conclusion: pose refinement helps modestly (~+1.5 dB) but capture quality is
the real ceiling** ("capture is ~80% of quality" per the tuning guide).

- [x] More iters / higher res — tested; PSNR plateaus ~24 (not the fix).
- [x] Pose accuracy — COLMAP pose-prior refine = +1.4 dB (worth banking).
- **Capture quality (biggest lever) — in progress:**
  - [x] Fixed focus (no refocus blur, stable intrinsics).
  - [x] Denser keyframes (7 cm / 8°) for ~70–80% overlap.
  - [ ] **Lock AE/AWB** — needs `SharedCamera` (ARCore + Camera2) to set
        `CONTROL_AE_LOCK`/`CONTROL_AWB_LOCK`; photometric consistency matters.
  - [ ] Capture-technique guidance UX (perimeter orbit, multi-height, loop closure).
- [ ] **Productionize pose-prior refinement** in the backend (COLMAP pose-prior
      or GLOMAP with ARCore priors + relaxed triangulation) — banked +1.5 dB.
- [ ] Intrinsics / lens distortion (OPENCV camera model vs pinhole).

## Backlog
### Backend → cloud GPU
- [ ] Validate `backend/Dockerfile` on a real cloud GPU (RunPod / vast.ai).
- [ ] Token auth on `/jobs` (required before public exposure).
- [ ] `.dockerignore` + deploy README.
- [ ] Serverless option (RunPod Serverless / Modal) for scale-to-zero.
- [ ] Job retention / cleanup (old uploads + `.ply` pile up in `JOBS_DIR`).

### App features
- [ ] Batch export — extend the home multi-select to export scans (`.ply`/`.zip`).
- [ ] On-device reconstruction cancel (cloud cancel exists; offline path doesn't).

## Resolved
- [x] **Backend survives reboot** — persistent, enabled `systemd --user` service;
      verified by a live reboot (container + backend auto-recover, GPU intact).
- [x] **Cloud GPU speed** — train on the host via `distrobox-host-exec` (~14–19×).
