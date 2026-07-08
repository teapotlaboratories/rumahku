# rumahku — Milestones & To-Do

The canonical roadmap **and** to-do list. Keep it current as work lands. Details
live in the linked design docs; this is the map + checkboxes.

Legend: ✅ done · 🔨 in progress · ⬜ planned · ⏸ blocked

Vision: a **phone-only** 3D room scanner — capture *and* reconstruct a Gaussian
splat on the device, semi-real-time. See `PHASE2.md`.

---

## Phase 1 — Capture ✅ (done)
- [x] Android app scaffold (Kotlin/Compose + NDK + ARCore)
- [x] Live ARCore camera preview + tracking-status overlay
- [x] Keyframe capture gated on stable tracking
- [x] Write nerfstudio `transforms.json` + JPEGs on device (`DatasetWriter`)
- [x] Real-time 3D coverage dots
- [x] Native (NDK/JNI) `nativecore` diagnostics bridge

## Phase 2 — On-device training

### M0 — Feasibility spike ✅ (done)
- [x] Build Brush (`brush-cli`) for `aarch64-android` (cargo-ndk, NDK 27)
- [x] Prove wgpu→Vulkan compute runs on Pixel 6 **Mali-G78**
- [x] Train a splat on-device (5.5 it/s, 311 MB, 32 °C) → valid `.ply`
- [ ] 🔨 **S25 (Adreno)** — the other M0 target (see `S25.md`). On a Samsung Tab
      S8 (Adreno 730) proxy: install, ARCore, depth, and GL **coverage-overlay
      rendering all work on Adreno**; wgpu/cubecl **training compute unverified** —
      blocked by that Tab's pathological external-storage `EACCES` (device-specific,
      not the S25). Added a storage→internal fallback (`Storage.kt`). Needs the
      real S25 to finish.

### M1 — Integrate the training core into the app ✅ (done — PR #2)
- [x] `brush-ffi` cdylib (JNI shim over Brush), standard `jni 0.21`
- [x] Size spike: shippable `.so` **44 MB**, no perf regression
- [x] `.so` → `jniLibs/`; `BrushTrainer.kt` (loadLibrary + external funs)
- [x] "Reconstruct latest scan" in `MainActivity`, live progress
- [x] End-to-end verified on Pixel 6 (16k-splat `.ply` in-app)
- [x] **Foreground service + wakelock** — survives backgrounding/screen-lock
- [ ] ⬜ Migrate the 44 MB `.so` to Git LFS or a CI build step

### M2 — Semi-real-time / incremental training 🔨 (refine-live shipped; see `M2.md`)
- [x] Design doc + architecture (`docs/M2.md`).
- [x] **"Refine live" slice (shipped + verified)**: FFI exposes the live training
      splats via `TRAIN_SPLAT_VIEW` (the `Slot<Splats>` from `create_process`) +
      `nativeRenderTrainingPreview`; the reconstruction screen renders them every
      ~1.5 s so you **watch the splat converge** during the build. Concurrent
      train + preview render on the shared Mali GPU ran to completion with no
      crash (the key risk) — verified on Pixel 6.
- [ ] Full **during-capture** streaming (long-lived manual trainer feeding
      in-memory `SceneBatch`es as you scan; plan in `M2.md`) — the bigger build.

### M3 — On-device seeding (the quality lever) ✅ (validated + field-tested)
- [x] Validate the lever on-device: **+10.8 dB** seeded vs random (see `M3.md`)
- [x] `SeedPointCloud` — accumulate ARCore feature points across frames
- [x] `DatasetWriter` writes `seed.ply` + `ply_file_path` (Brush auto-seeds)
- [x] **Field test (real handheld scan)**: 43 keyframes + a **2288-point ARCore
      seed** captured on a real room. Surfaced a real bug — **586 seed points were
      NaN** (ARCore returns them; our `confidence < min` filter let them through
      since `NaN < 0.3` is false), which aborted the trainer
      (`assertion failed: !x.is_nan()`). **Fixed**: `SeedPointCloud` +
      `DatasetWriter` now reject non-finite points. Re-ran on the cleaned scan →
      seeded reconstruction runs (no crash).
- [x] **Color the seed points**: at each keyframe, project accumulated seed
      points into the image and sample RGB from the camera (NV21); `seed.ply`
      written with `uchar red/green/blue`. Colored seed starts training closer to
      the final look (matters in a small on-device iter budget). Compiles + no
      crash on Pixel 6; colored-seed visual proof pending a real scan.
- [ ] ⬜ Denser seed via the ARCore **Depth API**

### M4 — On-device viewer + export/share ✅ (done)
- [x] In-app splat renderer (`nativeRenderView`/`Orbit`/`Look` reuse Brush's
      `render_splats` + `to_init_splats`). Device-init-once guard required
      (`burn_init_setup` panics if called twice → SIGABRT).
- [x] Fast rendering: reuse the wgpu device + tokio runtime + cache the parsed
      splat → warm frames **~90 ms** (cold ~500 ms).
- [x] **Matterport-style walkthrough** (`nativeRenderLook` + `SplatViewerActivity`):
      stand at a capture standpoint, **drag = look around**, **pinch = zoom**,
      **double-tap = move** to the next standpoint. Verified on Pixel 6 on a
      sharp reconstruction.
- [x] Good-splat demo: `scan99_colmap` (COLMAP) → nerfstudio `transforms.json` +
      colored `seed.ply` (pycolmap) → retrained seeded splat (**PSNR 23.7**) →
      viewable; walkthrough shows a recognizable room. (also have: capture-pose
      fly-through `nativeRenderView` + orbit `nativeRenderOrbit` in the FFI.)
- [x] **Fullscreen immersive UI** (Polycam/Matterport-style): render fills the
      screen (system bars hidden), portrait-aspect render (FFI derives vertical
      fov from output shape), minimal overlay (standpoint pill, recenter, hint),
      whole surface is the control. Verified on Pixel 6.
- [x] Level-horizon panning: yaw about the camera's own up (≈ gravity), not the
      COLMAP world +Y.
- [x] Directional double-tap: `nativeLookForward` gives the look direction; the
      viewer moves to the nearest standpoint within ~66° of it (else next).
- [x] Export / share the `.ply` — `FileProvider` + system share sheet
      ("share" pill). Verified: shares `good_2000.ply` to Gmail/WhatsApp/etc.
- Note: `scan99` is a front-facing scan (not 360°), so looking far to the side
  shows uncaptured/black regions — a coverage limit of that scan, not the viewer.

### Reconstruction — cancel ✅ (done)
- [x] **Cancel a running build**: `ReconstructionService.cancel()` → the FFI's
      cooperative `nativeCancel` (loop checks a CANCEL flag) → clean return →
      result `CANCELLED` → back home; no partial/corrupt splat. Cancel button on
      the progress screen. Verified on Pixel 6 (graceful, **no SIGABRT**). This
      also removes the need to force-kill mid-build (the likely abort trigger).

### Known issues
- [x] ~~Native trainer SIGABRT~~ — **root-caused via the M3 field test**: NaN
      seed points (`assertion failed: !x.is_nan()`), now filtered out (see M3).
      The force-kill teardown case is separately avoidable via graceful Cancel.
- [ ] Belt-and-suspenders: `panic=unwind` + `catch_unwind` in brush-ffi so ANY
      future native panic surfaces as the error screen, not a process abort.

### M5 — Perf, memory, thermals 🔨 (in progress)
- [x] Iteration study (on Pixel 6 Mali, seeded scan99_colmap): PSNR 21.5 / 24.8 /
      25.3 at 1k / 2k / 3k iters; 3000 iters in 755 s at 24→28 °C (no throttling).
      → default set to **2000 iters** (near-baseline quality, ~7 min).
- [ ] Cap Gaussian count / LOD / image downscale for the phone GPU
- [ ] Sustained-compute heat + battery on a long (whole-house) scan
- [ ] Pause/resume training
- [x] **Quality-mode selector**: tapping "Tap to build" shows Quick (1000) /
      Balanced (2000, default) / High (3000); iters flow through
      `ReconstructionActivity` → `ReconstructionService.start(dir, iters)` →
      `nativeTrain`, and the progress % uses the chosen count. Verified on Pixel 6.

## UI/UX (product polish) — see `docs/UX.md`
### Home + scan library ✅ (done)
- [x] Bright, friendly brand theme (fixed coral/teal light palette; dynamic
      color off) — `ui/theme/Theme.kt`.
- [x] Home = **scan library**: grid of cards (thumbnail + friendly date + status
      chip Ready/Tap-to-build), "New scan" FAB, empty state, reload on resume.
      Tap Ready → walkthrough; tap Captured → reconstruct. Verified on Pixel 6.
- [x] **Sleekness pass** (A/B'd → chose immersive): photo fills the card, title
      over a gradient scrim, frosted status chip (dot + label), line-art house
      placeholder (no emoji), ▶ over Ready photos, "Tap to walk through/build 3D"
      hints. Verified on Pixel 6.
- [x] Extended the sleek language to **reconstruct** (icon badges — ✓/✕ in soft
      circles instead of 🎉/😕) and the **viewer** (frosted circular share/recenter
      icon buttons instead of text pills). Viewer + running state verified on
      Pixel 6; success/error badges compile (trivial icon-in-circle).
- [x] **Rename / delete scans** (long-press card → manage dialog; rename persists
      to `name.txt`, delete has a confirm). Verified on Pixel 6.
- [x] **Per-scan build progress on the card**: the scan being reconstructed
      shows a live ring + "Building N%" (service exposes `currentDir`; home polls
      `nativeCurrentIter`). Replaces the global banner. Guard added so tapping a
      building card doesn't start a second train. Verified on Pixel 6.
- [ ] Scan detail sheet
### Reconstruction progress screen ✅ (done)
- [x] `ReconstructionActivity` — tap a "Tap to build" card → starts the service,
      shows a circular % ring, live splat count + elapsed, friendly messages;
      **done → "Open walkthrough"**, **error → readable message**. Verified on
      Pixel 6 (running + error states; success wired). Bright/friendly.
- [ ] Live in-progress splat preview (needs periodic export + a render that
      doesn't contend with training on the GPU)
### Capture screen polish ✅ (done)
- [x] Camera-style coral **record button** (circle → stop-square), friendly
      guidance (live ARCore tracking hint + action prompt), keyframe/distance
      pill while capturing, and a satisfying "done" (finish → back to the
      library where the new scan appears). AR/GL rendering untouched. Verified.
- [ ] Live coverage/quality meter; capture tips/onboarding
### Polycam-style coverage overlay 🔨 (built; field test pending) — see `docs/CAPTURE_COVERAGE.md`
- [x] ARCore **Depth API** → dense world points (`DepthCoverage`, unproject
      depth→camera→world), voxel-deduped into `CoverageBuffer`, rendered as a
      translucent **teal "processed surface" wash** so uncaptured gaps stay
      obvious. Depth enabled when supported (Pixel 6: yes); graceful fallback.
- [x] Verified: builds, depth supported, capture runs, no crash / no accumulate
      errors.
- [x] Live **"coverage · N pts"** readout on the capture HUD (teal pill) — polls
      `CoverageBuffer.count()`, gives numeric confirmation the overlay is
      accumulating during a real scan. Renders verified; count needs a real scan.
- [ ] ⏸ **Field test** — see the wash fill in on a real handheld room scan; the
      bench phone points at a blank wall → can't track → no depth to accumulate
      (same blocker as the M3 field test).
- [ ] Occlusion; a real triangle mesh; fade/confidence-colour the wash
### Visual system + transitions ✅ (done)
- [x] Theme-level polish across all screens: warm brand `windowBackground` (no
      black launch flash), gentle **fade activity transitions** (`@anim/fade_*`),
      branded coral **splash** (API 31+), correct status-bar icon colour per
      screen (dark on light home/reconstruct; light on the dark camera/viewer via
      `Theme.Rumahku.Dark`). Verified on Pixel 6.
### App icon refresh ✅ (done)
- [x] Rebranded adaptive icon: white house (coral door + round gable window) on
      the coral brand circle (`drawable/ic_launcher_foreground.xml`,
      `ic_launcher_background` → coral). Verified on Pixel 6 (app-info render).
### Later UX passes ⬜
- [ ] Motion within screens (staggered card entrance, shared-element to viewer)

## Fallback (de-risked, optional feature)
- [x] Capture on phone → train on `carbonite-noble` (RTX) over NetBird
      (COLMAP + nerfstudio/gsplat pipeline exists; PSNR 25.9 baseline)

## Bench / infrastructure ✅
- [x] `carbonite-noble` GPU box on NetBird; autostart distrobox service (`BENCH.md`)
- [x] `tools/adb-unlock.sh` — reliable Pixel 6 unlock over adb
- [x] Bench documented in `docs/BENCH.md`

---

Docs: [`PHASE2.md`](PHASE2.md) · [`M1.md`](M1.md) · [`M3.md`](M3.md) ·
[`BENCH.md`](BENCH.md) · daily [`worklog/`](worklog/)
