# TODO

Parked / upcoming work. See `README.md` for the project overview.

## Backend → cloud GPU
Move the reconstruction backend off the personal GPU box to a cloud GPU.

- [ ] **Validate `backend/Dockerfile` on a real cloud GPU** (RunPod / vast.ai /
      Lambda). The image is written and correct for a standard driver stack, but
      the dev box's bleeding-edge NVIDIA driver (610.x) fights the in-container
      Vulkan path, so it can't be validated locally — the definitive test is on
      a mainstream cloud driver, where in-container Vulkan is the normal path.
      Key requirements already baked in: `NVIDIA_DRIVER_CAPABILITIES` must include
      `graphics`, and `libvulkan1 + libx11-6 + libxext6` must be installed.
- [ ] **Token auth** on `/jobs` — the service is currently unauthenticated
      (fine on the VPN, required before any public exposure).
- [ ] **`.dockerignore` + deploy README** so the build context is lean and the
      deploy is repeatable.
- [ ] **Serverless option** (RunPod Serverless / Modal / Beam) for scale-to-zero
      once there's real traffic — wrap `_run_job` in the provider's handler.
- [ ] **Job retention / cleanup** — old uploads + `.ply` outputs accumulate in
      `JOBS_DIR`; add a TTL sweep.

## App features
- [ ] **Batch export** — the home multi-select was built for delete *and*
      export; add export/share of selected scans (`.ply` / `.zip`).
- [ ] **On-device reconstruction cancel** — cancel works for cloud builds but
      not the offline (on-device) training path yet.

## Resolved / not needed
- [x] **Backend survives reboot** — the backend is a persistent, enabled
      `systemd --user` service (`~/.config/systemd/user/rumahku-backend.service`)
      that auto-starts and restarts on failure.
- [x] **Distrobox auto-start on host boot ("Fix 2")** — *not needed*: verified
      by an actual reboot that the container comes up on its own and the backend
      auto-starts with it. Full auto-recovery, zero intervention.
