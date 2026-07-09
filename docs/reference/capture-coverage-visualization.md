# Real-Time Capture-Coverage Visualization for an Android RGB + ARCore 3D-Capture App: A Practical Engineering Guide

## TL;DR
- **Build the coverage overlay as an incremental voxel/TSDF representation fused from ARCore's Depth API (Raw Depth 16-bit, typically ~160×120 up to 640×480 px), rendered as a semi-transparent "hologram" mesh via Filament/OpenGL ES with depth-based occlusion** — this is the closest on-device analog to Polycam's live LiDAR mesh and RealityScan's live point cloud, and it runs comfortably in real time as a *guidance* layer separate from the cloud reconstruction.
- **For native Android, use SceneView (ARCore + Google Filament) or a hand-rolled OpenGL ES renderer; avoid Unity unless you need cross-platform.** Polycam ships a custom C++/WebAssembly engine, Scaniverse/Niantic is Unity-based, and RealityScan is an Epic/Unreal product — so there is no single industry answer, but for an Android-first ARCore app the native Filament path gives the best performance/thermal profile.
- **Represent coverage as an occupancy/observation grid that scores each surface cell by number of views, viewing-angle diversity, and distance**, colorize under- vs. well-captured regions (Polycam/RealityScan-style), and provide a frustum-based fallback plus ARCore feature-point cloud for devices with weak or no Depth API support.

## Key Findings

### How the leading apps actually do it
- **Polycam** has two capture paradigms. Its **LiDAR mode** paints a live colored mesh onto surfaces as you sweep the device (iOS-only, needs the Apple LiDAR sensor). Its **Photo mode** (the model for this task, and the only mode on Android) uploads photos to the cloud for photogrammetry/Gaussian-splat reconstruction using Apple's Object Capture pipeline. Polycam's own **Object Mode "Guided" capture gives a live point-cloud preview** so users can "spot gaps in coverage before you finish your session," and coaches a "cover the full area first, then fill in details" strategy.
- **Scaniverse (Niantic)** provides **live meshing feedback** during scanning: the on-screen mesh appears "patchy or incomplete," with "missing sections" or "wobbly or disappearing mesh," [Nianticspatial](https://www.nianticspatial.com/docs/scaniverse/techniques/) which directly signals under-captured areas. It recommends multi-pass patterns (perimeter loop clockwise + counterclockwise, then a lattice grid) for viewpoint diversity, [Nianticspatial](https://www.nianticspatial.com/docs/scaniverse/techniques/) and processes meshes and Gaussian splats on-device.
- **RealityScan Mobile (Epic)** shows a **real-time point cloud over the subject**; per Epic's product page, users "scan objects easily with the real-time point cloud showing on top of your subject, showing where you need to have more coverage and areas you already covered well." It also leaves **AR frame markers in space at each captured photo position**, offers an **auto-capture** mode, and provides a post-capture **color-coded coverage preview** for quality checking.
- **KIRI Engine** added, in its **v4.0 release (September 8, 2025)**, an **AR mode** that per KIRI's release notes "shows each captured photo as a 3D camera icon in space, helping users track coverage and ensure all angles are captured."
- **Luma AI** uses **projected orbital "loops" around the subject** that **fade away as each loop is completed**, an **aiming reticle with inward-pointing lines** to keep the camera centered, and a **reticle that turns orange when you move too fast or aim wrong (blue = good)**; its Freeform mode drops AR markers representing collected samples so users can see coverage gaps.
- **Matterport** uses a top-down **mini-map where un-captured regions render as black areas** ("fog of war"), guiding the operator to fill in the black before processing.

**The recurring UX vocabulary** across these apps: (1) live accumulating mesh or point-cloud overlay; (2) colored confidence/coverage feedback (well-covered vs. thin); (3) "fog-of-war"/painting metaphors (black → filled, loops fading); (4) capture-zone rings/loops/spheres; (5) photo-position markers placed in 3D space; (6) auto-capture triggered by pose/coverage; (7) coverage percentage / completion gauges.

### ARCore Depth API — capabilities and hard limits
- Depth is produced by a **depth-from-motion** algorithm, optionally fused with a hardware ToF sensor if present; **no ToF is required**. Valid depth appears only after the user moves the device.
- **Two APIs:** the **full Depth API** (`DepthMode.AUTOMATIC`, smoothed, every-pixel) and the **Raw Depth API** (`DepthMode.RAW_DEPTH_ONLY`, sparse but more accurate, with a per-pixel confidence image). Per Google's Raw Depth guide, "the compute cost of the Raw Depth API is about half of the compute cost for the full Depth API," making it the recommended input for reconstruction/measurement tasks.
- **Image format & resolution:** `HardwareBuffer.D_16`, single 16-bit plane, little-endian, millimeters. Per ARCore's `Frame` reference, "the size of the depth image is typically around 160x120 pixels, with higher resolutions up to 640x480 on some devices." The 16-bit API expresses range up to **65,535 mm (~65 m)**; deprecated 8-bit versions capped at 8191 mm.
- **Accuracy sweet spot:** per the `Frame` reference, "optimal depth accuracy occurs between 500 millimeters (50 centimeters) and 5000 millimeters (5 meters) from the camera. Error increases quadratically as distance from the camera increases." Textureless surfaces (white walls) give imprecise depth.
- **Confidence:** Raw Depth confidence is a Y8 image, 0–255. Google's `Frame` reference advises that "removing depth pixels below a confidence threshold of half confidence (128) tends to work well."
- **Device support is a subset of ARCore devices** (Android only; iOS not supported), [Google](https://codelabs.developers.google.com/codelabs/arcore-depth) so a fallback path is mandatory.
- **Scene Semantics API** returns per-pixel outdoor labels (SKY, BUILDING, TREE, ROAD, TERRAIN, VEHICLE, PERSON, WATER, etc.) [Google](https://developers.google.com/ar/reference/java/com/google/ar/core/SemanticLabel) with confidence, and `getSemanticLabelFraction()`. It is **outdoor-only, portrait-only**, and shares the Depth API device list. **Geospatial Depth API** fuses depth-from-motion with Streetscape Geometry to improve depth up to 65 m outdoors. Both are useful for masking sky/irrelevant regions out of the coverage grid, not core to it.

### On-device reconstruction/coverage algorithms
- The canonical real-time volumetric method is **TSDF (Truncated Signed Distance Function) fusion** — Curless & Levoy (1996), made real-time by **KinectFusion** (2011) on a dense voxel grid, then scaled by **Voxel Hashing** (Nießner et al., 2013) using a spatial hash of 8×8×8 voxel blocks for constant-time access [arxiv](https://arxiv.org/pdf/2301.12796) and memory only near surfaces.
- **CHISEL** (Klingensmith et al., RSS 2015) demonstrated real-time large-scale TSDF on a *mobile device* (Google Project Tango) using dynamic spatially-hashed chunks and space carving; its open-source form is **OpenChisel** (CPU-only, no GPU required, no built-in renderer). Other reusable libraries: **VDBFusion** (OpenVDB-backed TSDF, sensor-agnostic, [MDPI](https://www.mdpi.com/1424-8220/22/3/1296) parameterized by voxel size + truncation distance), **InfiniTAM** (voxel-hashing engine), and **Open3D**'s CUDA voxel-hashing TSDF integration (~10 ms/frame on desktop GPU).
- **Surfel-based** alternatives (ElasticFusion, SurfelMeshing, "Real-time Scalable Dense Surfel Mapping" at ~10 Hz CPU) avoid a voxel grid and can be lighter for a coverage-only overlay.
- **Coverage/next-best-view literature** gives the exact data structure for "have I captured this?" feedback: a **bounded object-centric voxel grid whose cells are labeled Occupied / Free / Unknown**, with **frontier voxels** at the Occupied/Unknown boundary marking where new data is needed, and per-view **coverage scores** counting newly revealed surface. This maps directly onto a colorized coverage overlay.

### Rendering the overlay on Android
- **Filament** is Google's production real-time renderer and is the engine under **SceneView** (the actively maintained successor to the archived Sceneform). SceneView is Jetpack-Compose-native, loads glTF/glb, and supports ARCore plane detection, anchors, **depth occlusion**, and geospatial anchors out of the box.
- **Depth-based occlusion** so real geometry hides the overlay: ARCore documents two approaches — **per-object forward-pass** (each fragment's alpha clipped by comparing asset depth to the sampled depth image) and **two-pass** (render virtual content to a buffer, then blend against real depth). [Google](https://developers.google.com/ar/develop/c/depth/developer-guide) The Depth API delivers the depth image to a fragment shader in OpenGL NDC (convertible to texture coords via `Frame.transformCoordinates2d`), and depth values are the projection onto the principal axis (z), not ray length. [Google](https://developers.google.com/ar/develop/c/depth/developer-guide)
- **DepthLab / ARCore Depth Lab** (UIST 2020, open-source) is the reference implementation for depth-driven effects, including the **ScreenSpaceDepthMesh** technique: a regular triangle-grid template mesh whose vertices are displaced in a GPU shader by reprojected depth, with Freeze/Unfreeze snapshotting [GitHub](https://github.com/googlesamples/arcore-depth-lab) — essentially a ready-made live depth-mesh overlay.
- **Hologram/"captured" look:** combine a translucent fill with **Fresnel edge-glow** (rim brightens at grazing angles), optional **wireframe/triangle** overlay, and animated scan-line stripes — the standard "HoloLens shader pack" recipe, all expressible as a Filament `material` with `blending: transparent`.

### Framework/engine choice — what the apps use
- **Polycam:** custom cross-platform **C++ renderer delivered to web via WebAssembly**, with native React Native/Swift/Kotlin shells and low-level graphics (OpenGL ES/Vulkan/Metal). Not Unity/Unreal. (Confirmed via Polycam engineering job postings.)
- **Scaniverse/Niantic:** **Unity-based** (Niantic Lightship ARDK / Scanning Framework), same scanning experience exposed to third-party Unity developers. (Framework is Unity-confirmed; the shipping app is strongly inferred.)
- **RealityScan Mobile:** an **Epic/Unreal** product (built on Capturing Reality + Quixel + Sketchfab; App Store developer is "Unreal Engine").
- **Luma AI:** native iOS + ARKit with custom (Metal) NeRF/splat rendering. (Inferred; no explicit engine source.)
- **KIRI Engine:** native custom pipeline; no public evidence of Unity/Unreal.

## Details

### 1. The representation: an incremental coverage voxel grid fused from ARCore depth

**Core loop (per selected frame, on a background thread):**
1. Acquire the frame's pose (`Frame.getCamera().getPose()`), camera **texture intrinsics** (`getTextureIntrinsics()`), the **raw depth image** (`acquireRawDepthImage16Bits()`) and its **confidence image** (`acquireRawDepthConfidenceImage()`). Skip frames where the depth timestamp hasn't changed (ARCore reprojects old depth otherwise).
2. **Reproject depth → 3D points in world space.** For each depth pixel (u,v) with valid depth d (drop confidence < 128, drop d outside 0.5–5 m): back-project with intrinsics to camera-space `(X,Y,Z)`, then transform by the camera pose model matrix to world space. This is exactly the `DepthData`/`convertRawDepthImagesTo3dPointBuffer()` pattern in Google's Raw Depth codelab (4 floats/point: X,Y,Z,confidence). [Google](https://codelabs.developers.google.com/codelabs/arcore-rawdepthapi)
3. **Fuse into a spatially-hashed voxel grid.** Use a hash map keyed on integer voxel-block coordinates (8×8×8 voxel blocks, Nießner-style) so memory is allocated only near observed surfaces. For a *coverage* tool you do **not** need a full precise TSDF — a lightweight per-voxel record is enough:
   - `observation_count` (how many frames saw this cell),
   - `view_direction_accumulator` (sum/bins of normalized camera→voxel directions, to measure **angular diversity**),
   - `best_distance` / `min_distance` (was it ever seen within the 0.5–5 m sweet spot?),
   - optionally a coarse TSDF value + weight if you also want a smooth iso-surface to render.
4. **Classify each cell** as *Unknown* (never observed), *Under-captured* (few observations or narrow angular coverage or only seen too far away), or *Well-captured* (enough observations from diverse angles at good distance). This Occupied/Free/Unknown + frontier scheme comes straight from the next-best-view literature and is what drives the color map.

**Why voxel hashing over a dense grid:** KinectFusion's dense grid blows memory on anything bigger than a small bounded volume; voxel hashing / CHISEL chunks keep only surface-adjacent blocks, which is what makes room-scale real-time mapping feasible on a phone.

**Meshing for display:** run **Marching Cubes** over occupied blocks incrementally (only re-mesh blocks touched since last frame) to get a triangle mesh, or skip meshing entirely and render **accumulated points/surfels** colored by coverage class. For a guidance overlay, an incrementally re-meshed low-res surface (voxel size 2–5 cm, à la OpenChisel's 2.5 cm default) looks like Polycam's live mesh without the cost of a high-fidelity reconstruction.

### 2. Tracking capture quality per area
Score each surface cell and colorize:
- **Observation count** — cells seen by many frames are more reliable.
- **Viewing-angle diversity** — bin the camera→cell directions into a small hemisphere histogram; photogrammetry/Gaussian-splat quality depends on parallax, so a cell seen only from one direction should read as "under-captured" even if seen many times. (This mirrors Scaniverse's clockwise+counterclockwise and lattice guidance, and Luma's multi-loop-at-different-heights advice.)
- **Distance** — down-weight observations outside ARCore's 0.5–5 m accuracy window.
- **Blur/exposure** — optionally reject frames with high motion (large IMU angular velocity) or low sharpness (variance-of-Laplacian on the luma image) before fusing, so a "captured" cell really was captured cleanly.

Map the composite score to a color ramp (e.g., red/none → yellow → green) and render it on the overlay, exactly the "areas you already covered well" vs. "where you need more coverage" feedback RealityScan describes.

### 3. Frustum-based coverage (the fallback and complement)
Even without dense depth, you can mark seen surfaces by **projecting each keyframe's camera frustum onto the accumulated geometry** (or onto ARCore detected planes / the sparse feature-point cloud) and flagging intersected cells as observed. ARCore's `PointCloud` gives world-space feature points with per-point IDs persistent across frames and confidence values — accumulate these into the same voxel grid to get a sparse coverage proxy on devices where the Depth API is unsupported or unreliable. This is the graceful-degradation path.

### 4. Rendering pipeline (native Android)
- **Renderer:** Filament via **SceneView** (`io.github.sceneview:arsceneview`) for fast integration, or a custom OpenGL ES 3 renderer (as the Google `hello_ar_java` and Depth Lab samples do) if you want maximum control over the accumulating mesh buffers.
- **Alignment:** render the coverage mesh in ARCore world space; anchor it to a session anchor so it stays registered as tracking refines. Use `setMaxFramesPerSeconds` / camera config to match the AR frame rate (30 fps on most phones, 60 on Pixels).
- **Occlusion:** enable depth occlusion (`CameraStream.DepthOcclusionMode.DEPTH_OCCLUSION_ENABLED` in SceneView, or the forward-pass/two-pass shader from the ARCore depth developer guide) so the overlay is correctly hidden behind real objects — critical for the "painted onto the real surface" illusion.
- **Material:** a transparent Filament material combining translucent fill + Fresnel rim glow + optional wireframe, color-driven per-vertex by the coverage class. Note the known Filament/Sceneform gotcha that a transparent material that must also *occlude* needs care (`colorWrite`, two-pass transparency) — see the Sceneform issue thread.

### 5. Reconciling on-device coverage with cloud reconstruction
The on-device mesh is a **rough guidance proxy, not the deliverable**. Keep them cleanly separated:
- On device: accumulate the coverage grid + the actual capture payload (RGB frames/keyframes + ARCore poses + intrinsics + optionally depth). The coverage grid only decides **which frames to keep and when the user has enough**.
- In the cloud: run the heavy photogrammetry / Gaussian-splat / NeRF reconstruction on the uploaded RGB + poses. This is exactly Polycam Photo mode's and RealityScan's division of labor (capture + light feedback on device, reconstruction in the cloud). One patent (US 11,232,633) describes this edge/cloud split explicitly: on-device tracking + live coverage feedback, cloud for final mesh + texture, cutting scan time and ~80%+ of device battery/thermal load versus full on-device reconstruction. [uspto](https://image-ppubs.uspto.gov/dirsearch-public/print/downloadPdf/11232633)
- Use the coverage grid to drive **auto-capture** (trigger a keyframe when the frustum enters an under-covered region from a new angle) and a **completion percentage** (well-captured cells / total target cells), the same gauge metaphor these apps expose.

### 6. Performance, thermal, and battery budget
- **Decouple threads:** AR/render on the GL thread at display rate; depth acquisition + reprojection + fusion + meshing on a worker thread (or GPU compute). Never block the render thread on fusion.
- **Throttle fusion:** fuse depth every N frames (e.g., 5–10 Hz), not every frame; only integrate when the pose has moved/rotated beyond a threshold.
- **Keep depth cheap:** prefer **Raw Depth** (half the compute of full Depth) and the native ~160×120 resolution for the coverage grid; you don't need 640×480 for cm-scale voxels.
- **Bound the grid & decimate the mesh:** voxel hashing to cap memory; incremental Marching Cubes only on dirty blocks; LOD/decimate the display mesh (the Blender Decimate-style 300k→6k reduction [Viget](https://www.viget.com/articles/cross-platform-ar-with-unity) principle) so the GPU isn't re-uploading huge buffers.
- **Watch ARCore thermal signals:** monitor Android thermal APIs and ARCore's "VIO frequency low" logcat warning (indicates CPU starvation), disable unused ARCore features (Instant Placement after tracking, Augmented Images), [Google](https://developers.google.com/ar/develop/performance) and offer a 30 fps cap. Native ARCore + Filament typically shows lower CPU/memory than Unity AR Foundation for equivalent work (roughly 140–200 MB / 25–40% CPU native vs. 180–250 MB / 35–50% for AR Foundation in one mid-range benchmark), [Angry-shark-studio](https://www.angry-shark-studio.com/blog/ar-foundation-vs-arcore-comparison/) which matters for a long, thermally-stressful scanning session.

### 7. Fallbacks for weak/no depth support
1. **Full Depth API** if Raw Depth is thin on a given device.
2. **Frustum + sparse feature-point coverage** (Section 3) when Depth API is unsupported.
3. **Plane-based coverage:** mark ARCore detected planes as observed where the frustum crosses them.
4. **Pure guidance UX** (Luma-style orbital loops / ring targets + capture-position markers) that needs *only* camera pose, no depth at all — the most universal fallback, and the one that degrades most gracefully.

## Recommendations

**Stage 1 — Ship a pose-only guidance layer first (lowest risk, universal).**
Implement capture-position markers in 3D space (RealityScan/KIRI pattern) plus optional orbital ring targets (Luma pattern) using only ARCore pose + `PointCloud`. This works on every ARCore device with no Depth dependency and immediately gives users "where have I shot from."

**Stage 2 — Add the depth-fused coverage mesh where supported.**
Gate on `Session.isDepthModeSupported(RAW_DEPTH_ONLY)`. Implement the reprojection → spatially-hashed voxel grid → coverage classification → incremental Marching Cubes pipeline (Sections 1–2). Render via SceneView/Filament with depth occlusion and the hologram material. Start from the **ARCore Raw Depth codelab** for reprojection and **ARCore Depth Lab's ScreenSpaceDepthMesh** for the live mesh shader; consider **OpenChisel/VDBFusion** patterns (or a trimmed port) for the fusion core.

**Stage 3 — Coverage scoring, auto-capture, and completion gauge.**
Add angular-diversity + distance + blur scoring, colorize under/well-captured, drive auto-capture from frontier cells, and show a completion percentage. This is what turns "a live mesh" into genuine "you've captured this / go here next" guidance.

**Concrete default parameters to start with:** voxel size 3 cm; 8×8×8 voxel blocks; fuse at 6–8 Hz; confidence threshold 128; depth range clamp 0.5–5 m; "well-captured" = ≥5 observations from ≥3 distinct angular bins within range.

**Thresholds that should change the plan:**
- If sustained frame rate drops below ~25 fps or the device hits thermal throttling → raise voxel size, lower fusion rate, drop to points-only rendering.
- If a target device fails `isDepthModeSupported` → fall back to Stage-1 pose-only guidance automatically.
- If cloud reconstruction quality is fine with fewer frames → loosen the completion threshold to shorten sessions.

**Engine decision:** for an **Android-first ARCore capture app**, go **native Kotlin/NDK + SceneView (Filament)**. Choose Unity + AR Foundation only if cross-platform iOS parity is a hard near-term requirement (accepting ~10–20% performance overhead [Angry-shark-studio](https://www.angry-shark-studio.com/blog/ar-foundation-vs-arcore-comparison/) and higher memory). Do not use Unreal for a lightweight capture-guidance app.

## Caveats
- **No public source confirms the exact live-capture renderer** of RealityScan, Scaniverse, Luma, or KIRI at the binary level; engine attributions above are confirmed for Polycam (job postings) and Epic/Unreal (RealityScan is an Epic product) but **inferred** for the others. Treat them as directional, not definitive.
- **ARCore depth is depth-from-motion**: it is not survey-grade. An iPhone 13 Pro comparative building-documentation study reported achievable phone-scanning accuracies of **10–20 cm at the 95% confidence level (2σ)**, with no app achieving 95% of distances within a 5 cm level-of-accuracy; mean deviation from terrestrial laser scanning ranged from ~5 cm (Polycam) to ~44 cm (Scaniverse), and RMSE from ~10 cm (Polycam) to ~56 cm (Scaniverse). This is adequate for a *coverage* overlay, not for measurement — the overlay must be framed to users as guidance, not the final model.
- **Depth API device fragmentation is real** — a meaningful fraction of ARCore devices lack Depth API support, which is why the pose-only fallback is not optional.
- **Scene Semantics is outdoor/portrait-only** and won't help indoors; don't design core coverage logic around it.
- Some cited performance numbers (CPU/memory for AR Foundation vs. native, ~10 ms/frame Open3D TSDF, ~10 Hz surfel mapping) come from **specific hardware in specific papers/benchmarks** and will vary widely across phones; treat them as order-of-magnitude, and profile on your actual target devices.
- Marching Cubes / Poisson meshing on-device from ARCore points is known to be finicky (developers repeatedly report ARCore's sparse cloud is too noisy for clean surfaces); [GitHub](https://github.com/google-ar/arcore-android-sdk/issues/463) the coverage-grid approach sidesteps this by not needing a watertight mesh — it only needs to show *where* data exists.