# Bench setup

The hardware/lab bench for rumahku and how to reach each machine. This is the
single source of truth for "how do I connect to X" — do not rely on chat
history. Update it whenever the bench changes (new device, host, key, path).

Mesh: all machines are on a **NetBird** WireGuard mesh under the
`*.kugelblitz.internal` domain (NetBird IPs in `100.127.0.0/16`).

---

## Machines & devices

### `argonite` — primary dev box (this machine)
- Role: main development box; writes code, runs the on-device (Android) build
  toolchain, drives the Pixel 6 over adb, orchestrates the GPU box over SSH.
- CPU/GPU: AMD, **no NVIDIA GPU** (`nvidia-smi` absent) — cannot train locally;
  training happens on-device (phone) or on `carbonite-noble`.
- NetBird: `argonite.kugelblitz.internal` → `100.127.229.102`
- Toolchain: Android SDK/NDK at `~/Android/Sdk` (NDK `26.1.10909125` and
  `27.0.12077973`); Rust + `cargo` + `cargo-ndk 4.1.2` at `~/.cargo/bin`.
- adb: `~/Android/Sdk/platform-tools/adb` (not on `PATH`).
- Runs the `claude-rumahku` systemd service (see project notes) from
  `/home/argonite/Developments/rumahku`.

### `carbonite-noble` — GPU box (remote, over NetBird)
- Role: off-device training / high-quality "cloud" reconstruction and the
  Phase 2 fallback path (capture on phone → train here over the mesh).
  Runs the nerfstudio/gsplat + COLMAP pipeline; validated our captures at
  **PSNR 25.9** (COLMAP-seeded, RTX 3080).
- NetBird: `carbonite-noble.kugelblitz.internal` → `100.127.152.116`
  (Connected; ~26–45 ms from argonite; SSH open, Ubuntu OpenSSH 9.6).
- GPUs: **2× NVIDIA** (verified 2026-07-07) — index 0 **RTX 3080 (10 GB)**,
  index 1 **RTX 5060 Ti (16 GB)**; driver `610.43.02` (NVIDIA open kmod). Plus
  an Intel iGPU (not for compute).
- OS: immutable **ostree** distro (Bazzite/SteamOS-like). SSH lands inside a
  **distrobox** container (`ubuntu:24.04`, name `carbonite-noble`, rootless
  podman underneath) with GPU passthrough. The host binary is at
  **`/run/host/usr/bin/nvidia-smi`** and now **symlinked onto PATH** as
  `/usr/local/bin/nvidia-smi` (so bare `nvidia-smi` works). NOTE: do NOT
  `apt install nvidia-utils` — host driver is `610.43.02`, newer than any repo
  package, so a package install would hit `Driver/library version mismatch`;
  the symlink reuses the matching host binary + the pre-mounted
  `libnvidia-ml.so.1`. The symlink lives in the distrobox fs, so re-run the
  `ln -sf` if the distrobox is ever recreated (`distrobox enter` on the host).
  CUDA `nvcc` is not on `PATH` (training uses the nerfstudio/gsplat env's toolkit).
- **Autostart:** NetBird runs *inside* the `carbonite-noble` distrobox, so if
  that container is down the whole box drops off the mesh. A systemd **user**
  service on the host keeps it up across reboots:
  `~/.config/systemd/user/carbonite-noble.service` (`distrobox enter
  carbonite-noble -- true`), enabled for `default.target`, with
  `loginctl enable-linger deck` so it starts at boot without a login. If the box
  is unreachable on the mesh, the host is `carbonite` at LAN `10.0.0.72`
  (**do not touch without explicit OK**); check `podman ps -a` / `distrobox list`
  and `systemctl --user status carbonite-noble.service`.
- Connect (login user is **`deck`**):
  ```bash
  ssh -i ~/.ssh/rumahku_splat deck@carbonite-noble.kugelblitz.internal
  # or ...@100.127.152.116
  # GPUs:  /run/host/usr/bin/nvidia-smi
  ```
  Key: `~/.ssh/rumahku_splat` (the pub comment `rumahku-splat-driver` is a label,
  **not** the username). Login verified from argonite 2026-07-07.

### Pixel 6 — on-device trainer target (Mali GPU)
- Role: the M0 feasibility target — prove Brush trains a splat on the phone GPU.
  Worst-case "GPU-poor" dev target. Verified 2026-07-07: `oriole`, Android 16
  (SDK 36), ABI **arm64-v8a**, GPU **ARM Mali-G78** (OpenGL ES 3.2, drv r54p1).
- Connect via adb over TCP (wireless debugging is already set up):
  ```bash
  ADB=~/Android/Sdk/platform-tools/adb
  $ADB connect 192.168.7.229:5555     # LAN IP, port 5555
  $ADB devices -l                     # -> Pixel_6 device
  ```
  Push target dir on device: `/data/local/tmp` (writable/executable).
  If it drops, re-`connect`; if the port is lost, re-arm over USB with
  `$ADB tcpip 5555`.
- Unlock (secure PIN + fingerprint): use **`tools/adb-unlock.sh`**. It carries
  no PIN — it reads `$PIXEL_PIN` or `~/.config/rumahku/pixel-pin` (mode 600, not
  in git). `tools/adb-unlock.sh <package>` unlocks then launches an app. It
  works around gesture-nav swipe interception and the bouncer animation by
  running an on-device timed sequence with a mid-screen swipe, and retries.
  Do not rely on ad-hoc `input keyevent` timing — it is flaky.

### Samsung S25 — flagship on-device target (Adreno) — *planned*
- Per `PHASE2.md` the flagship M0 target (Adreno). Not yet part of the verified
  bench; add connection details when on hand.

---

## Quick reference

| Target            | Address / how                                   | Auth                              |
|-------------------|-------------------------------------------------|-----------------------------------|
| argonite (local)  | this box                                         | —                                 |
| carbonite-noble   | `100.127.152.116` / SSH over NetBird             | key `~/.ssh/rumahku_splat`, user `deck` |
| Pixel 6           | adb (`~/Android/Sdk/platform-tools/adb`), USB    | USB debugging                     |
