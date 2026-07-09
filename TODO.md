# TODO

See `README.md` for the project overview.

## Focus: reconstruction quality
The cloud pipeline is fast + measured (PSNR/SSIM) but the splats look weak
(~21 dB @ 2k iters; good splats are 25–35+). Improve quality — see the notes
being gathered in `docs/` and the leading hypotheses:

- [ ] **Pose accuracy** — we feed Brush raw ARCore VIO poses (not SfM-refined).
      Splatting is very pose-sensitive; this is the top suspect. Try Brush's
      camera/pose optimization during training, and/or a COLMAP refinement pass.
- [ ] **More iters / higher res** — now that the GPU is fast, push iters + res
      and watch PSNR vs. iters to find the real ceiling.
- [ ] **Capture coverage** — ensure enough overlapping views per surface; the
      gate keeps sharp frames but coverage/overlap may be thin.
- [ ] **Intrinsics / camera model** — verify focal + principal point; phone wide
      lenses have distortion a pinhole model doesn't capture.

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
