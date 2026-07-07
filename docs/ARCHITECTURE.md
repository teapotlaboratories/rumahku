# rumahku — Architecture

3D interior scanning on a phone. Capture a room by walking around with an
Android phone; reconstruct it as a photorealistic **Gaussian splat**. Think
Polycam / Matterport, but using an ordinary Samsung/Pixel-class camera (no
LiDAR) and Gaussian Splatting for the reconstruction.

This document is the durable reference for the project's design, decisions,
and state. If you are picking this up cold, read this first.

---

## 1. Vision & goals

- **Phone-only.** The final product does everything on the phone: capture,
  camera tracking, and the 3D reconstruction. No desktop/cloud step in the
  shipping app.
- **Gaussian Splatting** for the reconstruction, chosen for visual fidelity.
- **Semi-real-time.** The user keeps scanning while the reconstruction builds
  up incrementally in the background (not a single offline batch at the end).
- **Real-time capture guidance.** While scanning, the app shows what has been
  captured so the user knows where to point next.

Primary target device class: **Samsung S25 / Pixel 6+** (ARCore-capable, no
depth sensor). Development/testing has been on a **Pixel 6** (`oriole`).

### Why "training" is unavoidable

The phone only measures 2D images + camera poses; it never measures 3D
directly (no depth sensor). A photorealistic 3D model must be *optimized* so
that rendering it from each captured viewpoint reproduces the real photos.
That optimization loop **is** the "training." It can run on the phone — that's
the eventual on-device trainer — but it cannot be skipped.

---

## 2. Pipeline overview

```
                          ON DEVICE (Android app)
  ┌───────────────────────────────────────────────────────────────┐
  │  Camera2 / ARCore  ──▶  pose + intrinsics (ARCore VIO/SLAM)     │
  │        │                        │                               │
  │        ▼                        ▼                               │
  │  camera texture         Keyframe selector (dist/angle throttle) │
  │        │                        │                               │
  │        ▼                        ▼                               │
  │  BackgroundRenderer     image (YUV→JPEG) + pose + intrinsics    │
  │  + CoverageRenderer            │                                │
  │  (live guidance)               ▼                                │
  │                          DatasetWriter → transforms.json + jpgs │
  └───────────────────────────────┬───────────────────────────────┘
                                   ▼
                        RECONSTRUCTION (Gaussian splat)
        ┌──────────────────────────────────────────────────────┐
        │  Now (validation):  desktop/GPU trainer (Brush)       │
        │  Target (product):  on-device Vulkan-compute trainer  │
        └──────────────────────────────────────────────────────┘
                                   ▼
                          .ply splat  →  viewer
```

**Key design decision — skip COLMAP.** The slow, fragile part of a normal 3DGS
pipeline is COLMAP structure-from-motion (recovering camera poses from images).
ARCore already gives us metric-scale poses in real time, so we record them per
keyframe and feed images **+ poses** straight into the trainer. This is what
makes on-device / near-real-time reconstruction plausible.

---

## 3. Android app (Phase 1 — capture engine)

Stack: **Kotlin + Jetpack Compose** (UI), **C++/NDK** (native compute, JNI),
**ARCore** (tracking), **OpenGL ES 3** (camera + overlay rendering).

Package: `com.teapotlab.rumahku`

### Source map

```
app/src/main/
  AndroidManifest.xml          camera + AR feature/permission, activities
  cpp/
    CMakeLists.txt             builds libnativecore.so
    native-lib.cpp             JNI stub (future home of the Vulkan trainer)
  java/com/teapotlab/rumahku/
    MainActivity.kt            diagnostics screen + "Start scanning"
    NativeBridge.kt            JNI bridge (loads libnativecore.so)
    CaptureActivity.kt         live scan screen; owns ARCore Session lifecycle
    ar/
      ArAvailability.kt        ARCore install/support check
      DisplayRotationHelper.kt keeps ARCore geometry in sync with rotation
      ArRenderer.kt            GLSurfaceView.Renderer; drives session.update()
      CoverageBuffer.kt        thread-safe world-space coverage points
      gl/
        GlUtil.kt              shader compile/link helpers
        BackgroundRenderer.kt  draws ARCore camera texture (external-OES)
        CoverageRenderer.kt    draws coverage dots (GL_POINTS, world-anchored)
    capture/
      CaptureSession.kt        keyframe policy + orchestration (GL thread)
      DatasetWriter.kt         writes images + transforms.json (bg thread)
      YuvUtil.kt               YUV_420_888 → NV21 (for JPEG encoding)
    ui/theme/Theme.kt          Compose theme
```

### Threading model

- **GL thread** (`ArRenderer.onDrawFrame`) is the only place the ARCore `Frame`
  is valid. Per frame: `setCameraTextureName` → `session.update()` → draw
  camera background → `onFrame(frame)` (status + capture) → draw coverage dots.
- **Capture** (`CaptureSession.onFrame`) runs on the GL thread but only does the
  minimum: decide if a keyframe is due, `acquireCameraImage()`, copy bytes +
  pose, `close()`. The heavy JPEG encode + disk write is handed to a
  **single background executor** so the render loop never stalls.
- **UI thread**: Compose state (`status`, `progress`) updated via
  `runOnUiThread`.

### Keyframe policy

Capture a frame only while `TrackingState.TRACKING` and once the camera has
moved **≥ 0.10 m** or rotated **≥ 12°** since the last keyframe
(`CaptureSession`). Avoids 30 near-identical frames/sec; gives good viewpoint
coverage.

### Coverage guidance

On each keyframe, a world-space point is projected **1.5 m in front** of the
camera (`Pose.transformPoint([0,0,-1.5])`) and stored in `CoverageBuffer`.
`CoverageRenderer` draws these as world-anchored teal dots every frame using
the camera view·projection matrix. Dots cluster on scanned surfaces; bare
regions show what still needs capturing.

### Camera config

`selectHighestResCameraConfig` picks the camera config with the largest CPU
image (**1920×1080** on Pixel 6) so saved frames are as high-res as the device
offers. Depth is **DISABLED** (not consumed yet; ARCore's ML-depth log spam).

---

## 4. Dataset format (the app's output)

Per capture session, under
`Android/data/com.teapotlab.rumahku/files/captures/<epoch_ms>/`:

```
images/000000.jpg, 000001.jpg, …   (JPEG, quality 95, sensor resolution)
transforms.json
```

`transforms.json` follows the **nerfstudio / instant-ngp** convention:

```json
{
  "fl_x": ..., "fl_y": ..., "cx": ..., "cy": ..., "w": 1920, "h": 1080,
  "frames": [
    { "file_path": "images/000000.jpg",
      "transform_matrix": [[..4..],[..],[..],[0,0,0,1]] }
  ]
}
```

### Conventions

- `transform_matrix` is a **row-major 4×4 camera-to-world** pose.
- Source pose is ARCore `camera.pose` (camera-to-world, OpenGL convention:
  camera looks down **−Z**, **+Y** up). `Pose.toMatrix` gives column-major
  (OpenGL); `DatasetWriter.toRowMajorJson` transposes to row-major.
- Intrinsics come from `camera.imageIntrinsics`, matching the
  `acquireCameraImage()` resolution.
- Images are stored in the **sensor's landscape orientation** (rotated 90° vs.
  how the phone is held). Intrinsics + poses are in that same sensor frame, so
  they are internally consistent — but this must line up with whatever trainer
  consumes it. **Validate at first reconstruction.**

---

## 5. Reconstruction

### Now — desktop validation with Brush

**Purpose: de-risk.** Prove a captured dataset reconstructs into a coherent 3D
scene *before* investing in the on-device trainer.

- **Brush** (github.com/ArthurBrussee/brush) — a Rust, **WebGPU/wgpu** Gaussian
  splat trainer. Chosen because it runs on **any GPU** (no CUDA), including the
  dev box's AMD iGPU via Vulkan — and because WebGPU/Vulkan is the same family
  as the eventual on-device path, so lessons transfer.
- Headless training via the `brush-cli` binary. Reads nerfstudio `transforms.json`
  directly. Exports `.ply`.
- Dev box GPU: **AMD Radeon R7 (RADV CARRIZO)**, only **512 MB VRAM** → train
  with `--max-resolution`, `--max-splats`, and reduced `--total-train-iters`.
  For full-res/quality, use a cloud NVIDIA GPU.

Validation location (not committed to the app repo):
`~/Developments/rumahku-splat/` (brush checkout, datasets, build log).

### Target — on-device trainer (Phase 2, not yet built)

Port a Gaussian-splat optimizer to the phone's GPU via **Vulkan compute**
(lives behind the `nativecore` JNI layer in `app/src/main/cpp/`). Consumes the
growing keyframe set incrementally (semi-real-time). This is the project's
main R&D risk; everything before it is engineering.

---

## 6. Known issues & findings

1. **16 KB page alignment** — Pixel 6 (dev mode) warns that native `.so` files
   aren't 16 KB-aligned (`libnativecore.so` + ARCore's libs). Non-fatal today;
   Google will require it eventually. Fix: NDK linker flag
   (`-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON` / max-page-size=16384) for our
   lib; bump ARCore for theirs.
2. **`camera_model: "PINHOLE"`** — `DatasetWriter` writes this, but "PINHOLE"
   is a COLMAP term; nerfstudio/Brush expect `"OPENCV"` or unset (=pinhole).
   **Fix: omit the field, or emit `"OPENCV"` with zero distortion.** (Patched
   manually for the first validation run.)
3. **Image orientation** — saved in sensor-landscape; verify consistency with
   intrinsics/poses at first reconstruction.
4. **ARCore ML-depth log spam** — `feature_track_ml_depth_provider` errors are
   internal to ARCore's monocular tracking (no depth sensor). Non-fatal, not
   ours; unaffected by our Depth API config.
5. **512 MB VRAM on dev box** — fine for downscaled validation; use cloud GPU
   for full-quality runs.

---

## 7. Development environment

- **SDK**: `~/Android/Sdk` (platform-34, build-tools 34.0.0).
- **NDK**: `27.0.12077973`; **CMake**: `3.22.1` (pinned in `app/build.gradle.kts`).
- **JDK 21**, **Gradle 8.9** (wrapper), **AGP 8.6.1**, **Kotlin 2.0.21**.
- `local.properties` sets `sdk.dir` (git-ignored).

### Build / install / run

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.teapotlab.rumahku/.MainActivity
```

### Wireless debugging (dev workflow used)

```sh
adb tcpip 5555
adb connect <phone-ip>:5555      # phone + host on same WiFi
```
Note: adb server may need render-node/USB perms; on this box it is run as root
once (`sudo adb start-server`). Classic tcpip mode resets on phone reboot.

### GPU access (dev box, for Brush)

`argonite` was added to the `render` group so radv can open
`/dev/dri/renderD128`. New logins pick it up; in an existing shell use
`sg render -c "…"` or run under sudo.

### Brush validation (rough)

```sh
cd ~/Developments/rumahku-splat/brush
cargo build --release -p brush-cli
sg render -c "target/release/brush-cli <dataset_dir> \
    --max-resolution 800 --total-train-iters 5000 --max-splats 300000 \
    --export-path ./out/ --export-name splat_{iter}.ply"
```

---

## 8. Repository & workflow

- **GitHub**: `teapotlaboratories/rumahku` (public).
- **Agent rules**: see `.ai/rules.md` — binding for AI agents. Summary:
  - No AI attribution anywhere (commits, PRs, code, docs).
  - No work-hours timestamps in pushed history (Mon–Fri 09:00–18:00 local);
    amend commit dates to outside that window before pushing.
  - Docs-only changes may go straight to `main`; everything else on a branch
    via PR, merged with **rebase and merge**.

---

## 9. Status & roadmap

**Done (Phase 1 — capture engine, verified on Pixel 6):**
- Toolchain (Kotlin + NDK/JNI + ARCore + Compose), diagnostics screen.
- Live ARCore camera preview + tracking-state guidance.
- Keyframe capture (image + pose + intrinsics), throttled; `transforms.json`.
- Real-time 3D coverage dots.

**In progress:**
- Desktop splat validation with Brush (de-risk the capture data).

**Next:**
- Confirm a good splat from captured data; fix findings (§6).
- Phase 2: on-device Vulkan-compute trainer (`nativecore`).
- Coverage polish, session browser / export.
```
