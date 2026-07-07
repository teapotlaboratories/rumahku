# Phase 2 — On-device reconstruction (the Vulkan trainer)

Goal: run the Gaussian-splat **training** on the phone, so the shipping app is
truly phone-only (capture + reconstruction on-device, semi-real-time). Phase 1
(capture) is done; off-device validation proved the captured data reconstructs
well (**PSNR 25.9** with COLMAP seeding on an RTX 3080). This phase makes the
reconstruction happen on the phone.

---

## 1. The pivotal decision: reuse Brush, don't build a trainer from scratch

The original plan was to hand-write a Gaussian-splat optimizer (forward +
**backward** rasterizer, Adam, density control) in Vulkan compute (GLSL/SPIR-V)
behind the `nativecore` JNI layer. That is months of work and the project's
biggest risk — reimplementing gsplat's hand-tuned CUDA kernels for mobile.

**Brush changes this.** Brush (github.com/ArthurBrussee/brush) is a Rust
Gaussian-splat engine built on `wgpu` (→ Vulkan on Android) and the `burn` ML
framework. Its README states training is *fully supported natively on Android*;
the repo has `apps/brush-app/src/android.rs` and a documented
`aarch64-linux-android` build. We already built and validated Brush off-device
(it trained our datasets correctly).

**So Phase 2 = integrate a proven mobile-capable trainer, not write one.** This
removes the single largest risk. The remaining work is integration, UX, an
on-device seeding strategy, and mobile perf/memory — all tractable engineering.

Two data-format facts that make integration easy:
- Our capture engine already writes **nerfstudio `transforms.json`**, which
  Brush reads directly. The capture→train handoff is essentially free.
- Brush exports **`.ply`**, which our (future) on-device viewer / share flow
  can consume.

---

## 2. Target architecture

```
  rumahku app (Kotlin/Compose + NDK)
  ┌───────────────────────────────────────────────┐
  │  Capture engine (Phase 1)                      │
  │    ARCore → keyframes (image+pose+intrinsics)  │
  │    → transforms.json + JPEGs on device         │
  │                      │                         │
  │                      ▼                         │
  │  Brush training core  (Rust cdylib, JNI)       │
  │    burn + wgpu → Vulkan on the phone GPU       │
  │    incremental optimize → splat                │
  │                      │                         │
  │                      ▼                         │
  │  On-device viewer + export (.ply / share)      │
  └───────────────────────────────────────────────┘
```

**Integration shape:** we do NOT embed Brush's egui app. We build Brush's
**training core** (the `brush-train` / `brush-render` / `brush-process` crates)
as an Android **cdylib with a small C API**, and call it over JNI from the
rumahku app. Likely we fork Brush and add a thin `brush-ffi` crate exposing:
`create_session`, `add_keyframes`, `train_steps(n)`, `get_render(pose)`,
`export_ply`, `stats`. Built with `cargo-ndk` for `aarch64-linux-android`.

The Vulkan trainer lives where `nativecore` was going to — but it's Rust, not
hand-written GLSL.

---

## 3. Milestones

### M0 — Feasibility spike (make-or-break, do first)
Prove Brush trains on the actual phone GPUs before investing in integration.
- Build Brush `apps/brush-app` for `aarch64-linux-android`, run on Pixel 6
  (Mali) and S25 (Adreno).
- Confirm `wgpu → Vulkan` compute works on those drivers for the ops Brush
  needs (subgroup ops, atomics). **This is the #1 risk** — mobile Vulkan
  compute driver support is uneven.
- Train a tiny scene on-device; record **iterations/sec, VRAM/RAM, thermals**.
- Exit criteria: Brush trains a splat on the phone GPU at all. If yes → the
  whole approach is validated. If no → fallback (§5).

### M1 — Integrate the training core into rumahku
- Fork Brush; add `brush-ffi` (C API) + build a cdylib via `cargo-ndk`.
- JNI bridge from Kotlin; feed captured `transforms.json` + images.
- Trigger a full (batch) train from the app after a scan; produce a `.ply`.

### M2 — Semi-real-time / incremental training
- The core UX from the vision: build the splat **as you scan**. Stream
  keyframes into the trainer; run training on a background thread; show the
  splat refining live. Brush supports live/interactive training to build on.

### M3 — On-device seeding (the quality lever)
Seeding gave **+6.75 dB** off-device (COLMAP). COLMAP on-device is impractical,
so we need a mobile seed for the point cloud:
- Reuse ARCore data we already have: the **coverage-dot world points** (Phase 1
  `CoverageBuffer`) and/or ARCore **Depth API** point cloud as initial splats.
- Or lightweight on-device triangulation from ARCore feature tracks.
- This is the highest-value quality work on-device.

### M4 — On-device viewer + export
- Use Brush's splat renderer (or a Vulkan splat viewer) to view in-app.
- Export/share `.ply`.

### M5 — Perf, memory, thermals
- Cap Gaussian count / LOD / image downscale for the mobile GPU.
- Manage sustained-compute heat & battery (real for "scan a whole house").
- Foreground service + progress; pause/resume training.

---

## 4. Risks (ranked)

1. **Mobile Vulkan compute driver support** — Adreno/Mali drivers may not
   support all wgpu compute features. Resolved by M0.
2. **Performance** — a phone GPU is ~10–50× slower than the 3080; expect
   coarse-then-refine over minutes, not an instant splat. Manage expectations;
   semi-real-time = progressive.
3. **Memory / thermal / battery** — sustained GPU compute throttles and drains;
   caps + LOD + thermal handling required.
4. **On-device seeding** — without it, quality drops back toward the ~19 dB
   random-init range. M3 is essential to keep the quality we validated.
5. **Integration/build complexity** — Rust + NDK + wgpu + our Kotlin app;
   Brush's app is a starting reference, but we extract the core as a library.

---

## 5. Fallback (already de-risked)

If M0 shows mobile wgpu/Vulkan can't run the trainer well, we already built the
alternative during this project: **capture on phone → train on a GPU box over
NetBird**. The RTX box (`carbonite-noble` on the mesh) with the
nerfstudio/gsplat + COLMAP pipeline is set up and reboot-persistent. This
"phone captures, your own GPU refines over the mesh" flow is a strong pragmatic
fallback — and arguably a shippable optional feature (a "high-quality" mode)
even if on-device training works.

---

## 6. Open decisions

- **Which GPU-poor floor do we support?** Pixel 6 (Mali) is a good worst-case
  dev target; S25 (Adreno 8 Elite) is the flagship target. Test both at M0.
- **Fork strategy:** track upstream Brush vs. a hard fork. Prefer a thin
  `brush-ffi` on top of a pinned Brush to ease upstream pulls.
- **Seeding source:** ARCore Depth API vs. coverage points vs. feature-track
  triangulation — decide at M3 based on quality.
- **Scope of "semi-real-time":** live-refining preview (M2) vs. train-after-scan
  (M1) for the first shippable version.

---

## 7. Suggested sequencing

M0 (spike) is the gate. If it passes, M1 → M2 → M3 in order gives a usable
phone-only scanner; M4/M5 harden it. If M0 fails, pivot to the mesh/cloud
fallback (§5) as the primary path while a from-scratch Vulkan trainer is
reconsidered.
