# Tuning & Best-Practices Guide: RGB-Only Android → SfM → 3DGS → Mesh/Floor-Plan Pipeline for Real-Estate Capture

## TL;DR
- **Capture is ~80% of final quality**: shoot 4K-downscaled video or bursts at 70–80% frame overlap, lock exposure/focus/white-balance, keep shutter fast (≤ ~1/100 s handheld) to avoid blur, walk slowly (~0.1 m/s), do multi-height boustrophedon + perimeter-orbit passes with a loop closure, and rely on ARCore metric poses + a known-length reference object to fix scale. Aim for and validate < 2% linear error against tape measurements.
- **Reconstruction**: run hloc SuperPoint+LightGlue (or COLMAP ALIKED+LightGlue) → COLMAP/GLOMAP with ARCore poses as priors (`pose_prior_mapper` + `model_aligner` for metric scale, relaxed triangulation angle for low-parallax indoors), train Splatfacto (gsplat, 30k iters, SfM-initialized) for the render branch and a parallel PGSR/2DGS branch for geometry; extract meshes by TSDF fusion (voxel ~6–10 mm indoors) and floor-plans by projecting the metric cloud to a top-down density map fed to RoomFormer/PolyRoom.
- **Dual-GPU (RTX 3080 + RTX 5060)**: do **NOT** attempt distributed single-scene 3DGS across these mismatched cards — the Blackwell 5060 needs CUDA 12.8+/sm_120 kernels while the Ampere 3080 uses sm_86, and NCCL runs at the speed of the slowest/smallest card. Use **job/stage parallelism**: pin stages/scenes to GPUs with `CUDA_VISIBLE_DEVICES`, one Celery worker per GPU. Put 3DGS training on the 3080 (more VRAM) and SfM + 2DGS/floor-plan on the 5060, pipelining SfM(next scene) alongside 3DGS(current scene).

---

## Key Findings

1. **Overlap and coverage dominate.** Independent capture guides converge on 70–80% overlap between consecutive frames, with every surface visible in at least three images. Room-scale scenes need roughly 200–500+ frames; a whole building is captured as linked room sub-scans plus corridor connectors with a return-to-start loop closure.
2. **Lock the camera.** Auto-exposure, autofocus, and auto-white-balance drift break photometric consistency and feature matching. Lock all three; use a shutter fast enough to freeze walking motion; shoot in landscape (better horizontal overlap for a horizontally moving operator).
3. **ARCore supplies metric poses and intrinsics for free.** ARCore's VIO produces metric-scale 6-DoF poses and per-frame intrinsics; feed these to COLMAP as pose priors and use `model_aligner` to remove SfM scale ambiguity. The Depth API adds per-pixel metric depth on supported devices (depth-from-motion; unreliable on blank walls/glass).
4. **GLOMAP/COLMAP tuning matters for low-texture indoors.** Learned features (SuperPoint+LightGlue via hloc, or ALIKED+LightGlue in COLMAP 4.x) beat SIFT indoors; sequential matching + loop closure suits video; relaxing the minimum triangulation angle is critical for low-parallax indoor motion. [arxiv](https://arxiv.org/pdf/2504.20040)
5. **Two Gaussian branches, two jobs.** Splatfacto/gsplat gives the best rendering; 2DGS/PGSR/GOF give geometrically accurate meshes. They are separate training runs and can run on separate GPUs.
6. **Heterogeneous multi-GPU = parallelism across jobs, not within a scene.** gsplat/Grendel-GS distributed training exists but assumes homogeneous GPUs and NCCL; mixing Ampere + Blackwell is fragile and bottlenecked by the slower/smaller card. The high-throughput answer for a prototype is per-GPU worker pinning.

---

## Details

## PART 1 — CAPTURE TUNING & BEST PRACTICES (Android / ARCore)

### 1.1 Capture trajectories for rooms and buildings
- **Per room**: combine a **boustrophedon ("lawnmower") pattern** with an **orbit of the room perimeter**. Walk the perimeter facing slightly inward (~15–30° toe-in) so walls, floor–wall and ceiling–wall junctions are all seen, then do interior sweeps. The Magnopus/Allee-Willis field notes recommend, when a full orbit is impossible, a **concave half-shell** of overlapping arcs at multiple elevations to preserve the 70%+ overlap that makes pose estimation succeed. [Magnopus](https://www.magnopus.com/blog/lessons-in-gaussian-splats-from-the-allee-willis-house)
- **Multiple heights**: capture at least **three passes** — low (~1.0 m), mid (~1.5 m), high (~2.0 m / above head). The forensic-capture study (MDPI *J. Imaging* 2025) found filming in landscape, at slow speed, with **at least three layers** and focused on key objects produced the cleanest, lowest-artifact reconstructions.
- **IMU excitation**: begin each session with gentle **figure-eight / side-to-side translation** to give ARCore's VIO translational parallax; pure in-place rotation gives no baseline and starves both VIO scale and SfM triangulation.
- **Loop closure**: always **return to the starting viewpoint** and re-observe the first wall/corner. This closes the trajectory loop, letting both VIO and SfM redistribute drift and stabilizing global scale.
- **Between rooms**: keep recording through doorways (don't stop/restart the session) so threshold frames tie rooms into one coordinate frame. For a whole building, treat each room as a sub-scan sharing corridor overlap; very large buildings may need per-room sub-models merged (COLMAP `hierarchical_mapper` / `model_merger`).
- **Overlap target**: **70–80%** between consecutive frames. Visually: shooting along a wall, consecutive frames should share ~70% of the wall; orbiting an object, each step ≈ 30% of frame width (each feature then appears in 3–5 frames — enough for COLMAP to triangulate accurately).
- **Frame counts**: object/small room ≈ 100–200 images; full room ≈ 200–500+; whole building = sum of room sub-scans, typically a few thousand keyframes after selection.

### 1.2 Video vs burst photo; concrete camera settings
- **Video** is preferred for coverage speed and pose sync on Android (ARCore records pose per frame); **bursts** give higher per-frame resolution and less rolling-shutter/compression artifact. For real-estate throughput, use **video at high resolution and moderate frame rate**, then extract sharp keyframes.
- **Resolution**: capture 4K if the device allows, then downscale for SfM/3DGS (COLMAP `max_image_size` ~1600–3200; Splatfacto `num_downscales=2`). Higher capture resolution preserves texture on distant walls.
- **Frame rate**: 30 fps is a sound default; ARCore recording targets `CameraConfig.TargetFps.TARGET_FPS_30`. Higher fps only helps if you walk faster.
- **Shutter speed**: the single most important anti-blur control. Keep exposure time short enough to freeze motion — a rule of thumb is ≤ ~1/(2 × focal-length-equivalent) s, and for handheld indoor walking aim for **≤ 1/100 s** where lighting permits. Motion blur destroys features irrecoverably.
- **ISO**: raise ISO to keep the shutter fast rather than letting exposure lengthen; SfM and 3DGS tolerate noise far better than blur. Cap ISO to avoid extreme grain (device-dependent).
- **Lock exposure (AE), focus (AF), and white balance (AWB)** for the whole session — changing exposure between frames creates inconsistent brightness that confuses both feature matching and the 3DGS photometric optimizer. Use Camera2/CameraX manual controls.
- **Rolling shutter**: move slowly and avoid fast pans; rolling-shutter skew corrupts pose and geometry. Gimbal/monopod stabilization strongly recommended.
- **Format**: shoot RAW/least-compressed for bursts; highest-bitrate profile for video.

### 1.3 Motion-blur avoidance and movement speed
- **Walking speed**: slow and deliberate — the forensic study tested ~0.085 m/s ("slow") and found it superior to faster paces; treat **~0.1 m/s** as the target interior pace.
- **Pan speed**: rotate the phone slowly and always combine rotation with translation so each frame has parallax.
- **Stabilization**: monopod, tripod (stills), or a phone gimbal; the field guidance is to "do whatever it takes to avoid motion blur."

### 1.4 Frame selection / keyframe extraction
- **Blur metric — variance of the Laplacian (VoL)**: convolve the grayscale frame with a Laplacian and take the variance; low variance = blurry. A commonly cited working threshold is **VoL ≈ 100** (below → discard), but the absolute value depends on resolution, bit depth, and texture, so **calibrate per device**: gather sharp/blurry samples, plot the two distributions, and set the cut between clusters (e.g. the 5–10th percentile of the sharp set). Keep the sharpest frame within each sliding window (e.g., highest VoL per ~20 frames, a practice validated in video-deblur work).
- **FFT-based** detection (standard deviation of the high-frequency magnitude spectrum over image sub-blocks) is a robust adjunct that avoids VoL's texture sensitivity.
- **Baseline/parallax**: don't keep near-duplicate frames — enforce a minimum camera-center translation or feature disparity between kept keyframes (Torr's GRIC / point-to-epipolar-line criteria are the classic robust approach). Deduplicate with combined global+local similarity.
- **How many to keep**: enough to preserve 70%+ overlap after culling — typically reduce a 30 fps walkthrough to a few unique, sharp frames per second.

### 1.5 Lighting for indoor capture
- **Consistency beats brightness**: diffuse, even, overcast-like lighting yields the best 3DGS (fewer harsh shadows → more consistent color estimation). Turn on all room lights, avoid mixing daylight and tungsten where possible, and keep exposure locked.
- **Windows/highlights**: avoid blown-out windows — they create featureless white holes and disrupt exposure. Draw blinds partly, or expose for the interior and mask clipped windows.
- **Flicker**: fluorescent/LED flicker can band frames; use a flicker-safe shutter (e.g., 1/100 s at 50 Hz, 1/120 s at 60 Hz) if banding appears.
- **HDR**: leave phone HDR/auto-HDR OFF (per-frame tone-mapping changes break consistency). Handle residual exposure variation downstream via appearance embeddings / bilateral grid in Splatfacto.
- **Reflective/glass/mirror and textureless walls**: mirrors and glass create "mirror-world" phantom geometry; blank walls give ARCore imprecise depth and SfM no features. Mitigations: capture at grazing angles, keep textured objects in frame, add temporary removable texture on large blank walls, use a polarizer for glossy surfaces, and flag mirrors/windows for masking.

### 1.6 ARCore specifics
- **Poses & intrinsics**: use `Camera.getPose()` for the physical camera pose (defined at the center of exposure of the center row of the image), and `Camera.getImageIntrinsics()` (`ArCameraIntrinsics_getFocalLength`, `getPrincipalPoint`, `getImageDimensions`) to record per-frame fx, fy, cx, cy. Store these with each frame for direct import into COLMAP as intrinsics + pose priors.
- **Tracking-state handling**: trust poses only when `getTrackingState() == TRACKING`. [Google](https://developers.google.com/ar/reference/java/com/google/ar/core/Camera) Note: once tracking is anything other than `TRACKING`, "the pose should not be considered useful." On `PAUSED`, read `getTrackingFailureReason()` (INSUFFICIENT_LIGHT, EXCESSIVE_MOTION, INSUFFICIENT_FEATURES, CAMERA_UNAVAILABLE) to guide the operator; drop frames captured while paused.
- **Depth API**: enable Depth mode after checking device support (`Config.DepthMode`); `acquireDepthImage16Bits()` gives smoothed metric depth, `acquireRawDepthImage16Bits()` gives raw (more geometrically accurate but sparser) depth with a confidence image. Depth "has the same timestamp and field of view intrinsics as the camera," is only valid after the user has moved the device, and is imprecise on featureless surfaces. Use depth for scale sanity-checks, monocular-depth supervision of the surface branch, and floor/ceiling plane fitting.
- **Session config & recording**: use the ARCore **Recording & Playback API** to record an MP4 with embedded pose/depth tracks for deterministic offline re-processing. Known caveat (SDK issue #1247): the recording stores **smoothed, not raw** depth, and frame rate can drop below 30 fps when recording high-res color + depth simultaneously.
- **Avoiding tracking loss**: good lighting, a translation-rich initialization motion, no fast rotation, and keeping textured content in view.

### 1.7 Metric scale capture
- **Primary source of scale = ARCore metric poses** (VIO fuses IMU accelerometry → real-world meters). Condition VIO for good scale by exciting the IMU with translational motion (walk, gentle figure-eight) before/while capturing; avoid pure rotation.
- **Reference object**: place at least one **known-dimension object** (folding ruler, printed scale bar/checkerboard of known size, or a standard A4/Letter sheet) in view in each room for an independent scale check and a fallback if VIO scale drifts.
- **Validation**: after reconstruction, measure known dimensions (a door ≈ 2.03 m, a tape-measured wall) in the model and compute percent error; target < 2%.

### 1.8 Common capture failure modes → fixes
- **Motion blur** → faster shutter, slower walk, stabilization.
- **Exposure/WB drift** → lock AE/AWB.
- **Low parallax / pure rotation** → add translation; relax triangulation angle downstream.
- **Textureless walls / mirrors / windows** → grazing angles, added texture, masking, polarizer.
- **Tracking loss** → monitor tracking state, improve lighting, re-initialize with translation.
- **Insufficient overlap / gaps** → methodical lawnmower + orbit, multi-height passes.
- **Loop not closed** → always return to start.

## PART 2 — 3D RECONSTRUCTION TUNING & BEST PRACTICES

### 2.1 SfM tuning (hloc / COLMAP / GLOMAP)
- **Features**: indoors, prefer **learned features**. Via hloc: SuperPoint + LightGlue. For maximum accuracy, `SuperPoint(max_num_keypoints=None)` and `LightGlue(features='superpoint', depth_confidence=-1, width_confidence=-1)` (disables adaptive early-exit/pruning); for speed, `max_num_keypoints=1024` with default confidences (`depth_confidence=0.95`, `width_confidence=0.99`, `filter_threshold=0.1`). COLMAP 4.x natively offers **ALIKED + LightGlue** (`--FeatureExtraction.type ALIKED`, requires an ONNX build) which "can produce more repeatable features … particularly for scenes with limited view overlap, little scene texture, and drastic illumination changes." SIFT (`--SiftExtraction.max_num_features 8192`, RootSIFT via `descriptor_normalization l1_root`) remains a robust baseline.
- **Matching strategy**: for **video**, use **sequential matching** with **loop-closure (vocab-tree) detection** enabled; for unordered photo sets use vocab-tree/retrieval (NetVLAD in hloc); exhaustive only for small sets. Enable **guided matching** (`--SiftMatching.guided_matching 1`) to raise inlier counts.
- **Geometric verification**: default thresholds are usually fine; for low-parallax indoor motion the key is to **relax the minimum triangulation angle** in the mapper. The MP-SfM study found that on the low-texture, forward-translation/in-place-rotation RealEstate10K scenes "relaxing the minimum triangulation angle allowed in COLMAP is critical" for both COLMAP and GLOMAP with SuperPoint+LightGlue. Also set `--Mapper.min_model_size` (e.g. ~50 images) to reject spurious sub-models.
- **Camera model**: use **PINHOLE** or **OPENCV** (with distortion) and **share intrinsics** across all frames from one phone camera (`--ImageReader.single_camera 1` / camera_mode SINGLE). If you trust ARCore intrinsics, fix them (`--Mapper.ba_refine_focal_length 0 --Mapper.ba_refine_principal_point 0`). Note COLMAP keeps the principal point fixed by default (its estimation is ill-posed).
- **Masking dynamic objects**: use `--ImageReader.mask_path` (black/intensity-0 pixels are skipped) to mask people, pets, mirrors, and windows.
- **Using ARCore poses/intrinsics as priors**:
  - Write ARCore intrinsics into the COLMAP `cameras` table and poses into the `images` table (`prior_qw…prior_tz`) after feature extraction.
  - Run **`colmap pose_prior_mapper`** — "essentially the incremental mapper with prior position constraints enabled" — controlling uncertainty via `--prior_position_std_x/y/z` (default 1.0 m each) to make reconstruction faster and metrically conditioned. (COLMAP also auto-populates priors from EXIF GPS when present.)
  - Additionally/alternatively run **`colmap model_aligner`** to "align/geo-register model to coordinate system of given camera centers" (a Sim(3)/7-DoF fit to ARCore camera centers) — the canonical way to remove scale ambiguity and set metric units. Use a RANSAC alignment threshold (~0.1–1 m) to reject outlier poses, as in the nuScenes 6-DoF lifting pipeline (RANSAC threshold 1 m to filter erroneous poses).
- **GLOMAP vs incremental**: GLOMAP (now merged into COLMAP as the **global_mapper**) is "1–2 orders of magnitude faster [Medium](https://medium.com/@sugunsegu/understanding-glomap-global-structure-from-motion-64b052be7927) " on large/looping sets with on-par or superior quality; run `view_graph_calibrator` first for good focal priors. [COLMAP](https://colmap.github.io/cli.html) **Caveat**: GLOMAP's view-graph calibration "may produce different camera parameters compared to the incremental pipeline's self-calibration" — re-validate downstream (Nerfstudio/3DGS expecting incremental intrinsics may break). The standalone GLOMAP repo is deprecated/archived (March 2026); use COLMAP's integrated global mapper. On the hardest indoor scenes incremental COLMAP still registers more images. [COLMAP](https://colmap.org/what-is-the-difference-between-incremental-and-global-sfm-in-colmap/)
- **Registration failures**: relax triangulation angle, add overlap/loop frames, switch to learned features, lower matching ratio thresholds, seed with pose priors, and split into sub-models + merge if a single global solve fails. [DeepWiki](https://deepwiki.com/colmap/colmap/3.2-sparse-reconstruction-commands)

### 2.2 3D Gaussian Splatting tuning (gsplat / Splatfacto / Nerfstudio)
Production defaults (verified against Nerfstudio `splatfacto.py`, `method_configs.py`, and gsplat `DefaultStrategy`/`MCMCStrategy`):
- **Iterations**: `max_num_iterations = 30000` (both `splatfacto` and `splatfacto-big`; "big" differs via AbsGrad and thresholds, not iteration count).
- **Warmup / refine start**: `warmup_length = 500` (densification off before this; gsplat `refine_start_iter = 500`).
- **Refinement interval**: `refine_every = 100`. [GitHub](https://github.com/nerfstudio-project/nerfstudio/blob/main/nerfstudio/models/splatfacto.py)
- **Densify gradient threshold**: Splatfacto model-level `densify_grad_thresh` is **0.0008** in current `main`; the gsplat `DefaultStrategy.grow_grad2d` library default is **0.0002**; `splatfacto-big` uses **0.0006** with `absgrad=True`. Lower it to densify more (sharper detail, more Gaussians, more VRAM).
- **Opacity cull threshold**: `cull_alpha_thresh = 0.1` default; **set to 0.005 for higher quality** (Nerfstudio docs) with `--pipeline.model.continue_cull_post_densification=False`.
- **Scale cull threshold**: `cull_scale_thresh = 0.5`; `grow_scale3d = 0.01`.
- **Opacity reset interval**: every **3000 steps** (`reset_alpha_every = 30 × refine_every`; gsplat `reset_every = 3000`).
- **Stop splitting**: `stop_split_at = 15000` (DefaultStrategy `refine_stop_iter = 15000`; MCMC big uses 25000).
- **SH degree**: `sh_degree = 3`, increased by one every `sh_degree_interval = 1000` steps.
- **Learning rates** (method_configs.py): means 1.6e-4 → 1.6e-6 (exp decay over 30k); features_dc 0.0025; features_rest 0.0025/20; opacities 0.05; scales 0.005; [GitHub](https://github.com/nerfstudio-project/nerfstudio/blob/main/nerfstudio/configs/method_configs.py) quats 0.001.
- **SSIM weight**: `ssim_lambda = 0.2`.
- **Downscale/resolution**: `num_downscales = 2`, `resolution_schedule = 3000` (coarse-to-fine). [GitHub](https://github.com/nerfstudio-project/nerfstudio/blob/main/nerfstudio/models/splatfacto.py)
- **Initialization**: **always initialize from SfM points** (`load_3D_points=True`) — "Gaussian splatting works much better if you initialize it from pre-existing geometry, such as SfM points from COLMAP."
- **Large-capture gotcha**: with >3000 frames the default densification trigger can silently never fire (Nerfstudio issue #2927) — adjust `refine_every`/`reset_alpha_every` [GitHub](https://github.com/nerfstudio-project/nerfstudio/issues/2927) or the densification condition. [GitHub](https://github.com/nerfstudio-project/nerfstudio/issues/2927)
- **MCMC strategy**: gsplat `MCMCStrategy` (Kheradmand et al., NeurIPS 2024) steers toward a hard Gaussian cap `cap_max = 1_000_000` [Gsplat](https://docs.gsplat.studio/main/apis/strategy.html) (set it to the count a DefaultStrategy run converges to), `noise_lr = 5e5`, `refine_stop_iter = 25000`, `min_opacity = 0.005`; requires `absgrad=True`. [AMD ROCm](https://rocm.docs.amd.com/projects/gsplat/en/docs-25.10/reference/gsplat-api-reference.html) Use MCMC when you want a fixed Gaussian budget / more uniform quality (and to fit the 8 GB 5060); use DefaultStrategy for natural growth.
- **Anti-aliasing (Mip-Splatting)**: enable the antialiased rasterize mode (Yu et al., CVPR 2024 — a 3D smoothing filter + 2D Mip filter) to avoid aliasing when the viewer zooms or changes focal length — important for buyer-facing tours where users pinch-zoom. Without it, "the scene tends to degrade quickly … when you pull outside of the capture path or change the focal length."
- **Appearance variation / exposure**: if exposure wasn't perfectly locked, enable the **bilateral grid** (`use_bilateral_grid=True`, `grid_shape=(16,16,8)`, lr 2e-3→1e-4) [GitHub](https://github.com/nerfstudio-project/nerfstudio/blob/main/nerfstudio/configs/method_configs.py) to correct per-image ISP/exposure drift, or use **Splatfacto-W** (per-image appearance embeddings ~48-dim + three-layer-MLP/SH background model) for in-the-wild collections.
- **Background/floaters**: indoors there's no sky, but enable a background/alpha model to suppress floaters outside windows; lower `cull_alpha_thresh` to 0.005 and enable `use_scale_regularization=True` (PhysGaussian scale regularizer) to curb long spikey Gaussians.

### 2.3 Surface/geometry branch (2DGS / PGSR / GOF)
- **2DGS (hbb1/2d-gaussian-splatting)** regularization (from `train.py`): **`lambda_normal = 0.05`, active only after iteration 7000**; **`lambda_dist` (depth distortion)** parser default is literally **0.0** but effective values are scene-dependent — **~1000 for bounded (object) scenes, 100 for 360°/unbounded, ~10 for large scenes** — active after iteration 3000. [GitHub](https://github.com/hbb1/2d-gaussian-splatting/blob/main/train.py) `depth_ratio = 0` (mean depth) for unbounded/large indoor scenes, 1 (median) for bounded. [GitHub](https://github.com/hbb1/2d-gaussian-splatting) Densification grad threshold 0.0002, opacity prune 0.05, opacity reset every 3000, 30000 iters, SH raised every 1000 steps.
- **TSDF fusion / mesh extraction (2DGS)**: adjust `--depth_ratio`, `--voxel_size`, `--depth_trunc`. [GitHub](https://github.com/hbb1/2d-gaussian-splatting) The 2DGS paper uses **voxel_size = 0.004 m, sdf_trunc = 0.02 m, depth_trunc = 3 m** on DTU; for room-scale indoors use a **larger voxel (~6–10 mm)** and larger `depth_trunc` (~6–10 m). Larger voxels are not just faster — Triangle-Splatting-SLAM's ablation shows "finer TSDF voxel sizes capture more noise, increasing the Chamfer error, whereas larger voxel sizes smooth the geometry at the cost of detail." GausSurf uses voxel 0.003 / trunc 0.02 with StableNormal priors for extra normal supervision.
- **PGSR (zju3dv/PGSR)**: built on 2DGS with unbiased planar depth (renders camera-to-plane distance + normal, divides to get unbiased depth) plus single-view geometric, multi-view photometric, and multi-view geometric regularization. **30000 iterations**; multi-view/geometric regularization begins at **`--multi_view_weight_from_iter 7000`**. Documented loss weights: single-view geometric ≈ **0.015**, multi-view NCC (photometric) ≈ **0.15** [arxiv](https://arxiv.org/pdf/2508.07701) (some DTU scripts use 0.5), multi-view geometric ≈ **0.03** (confirm exact per-dataset values in `arguments/__init__.py` and `scripts/run_tnt.py`). Weak-texture flags: `--max_abs_split_points 0`, [DeepWiki](https://deepwiki.com/zju3dv/PGSR/9.1-dtu-dataset) `--opacity_cull_threshold 0.05`, `--use_depth_filter`. [GitHub](https://github.com/zju3dv/PGSR) Mesh extraction: `--max_depth 10.0` (depth_trunc), `--voxel_size 0.01` [GitHub](https://github.com/zju3dv/PGSR) documented general default (smaller ~0.002 for objects). Hyperparameter tuning (2024-07-18 update) **lowered PGSR's mean DTU Chamfer distance to 0.47 mm** (per the official repo README; the paper reported 0.53 mm), so PGSR is a strong default for clean planar indoor surfaces.
- **GOF (Gaussian Opacity Fields)**: extracts surfaces directly from the Gaussian level-set (ray-Gaussian intersection normals) "without resorting to Poisson reconstruction or TSDF fusion" — attractive for unbounded scenes; use when TSDF over-smooths.
- **Manhattan/planar priors for walls**: exploit the strong planarity of walls/floors/ceilings — flatten Gaussians (a scale regularizer minimizing the smallest scale drives them toward local surface planes), add normal-consistency to align surfels to planes, and use `model_orientation_aligner` (Manhattan-world) to axis-align. This yields cleaner, near-watertight room shells.
- **Clean watertight-ish rooms**: fuse depth over all keyframes with a moderate voxel, cap `depth_trunc` to room size, mask windows/mirrors, then post-process (largest-connected-component, hole-fill, optional Poisson).

### 2.4 Floor-plan extraction tuning
- **Density map**: project the metric point cloud (or mesh vertices) along the gravity axis into a **top-down 2D density map** that highlights structural elements (walls). Resolution is the key knob — match the grid cell to your target metric precision (e.g. **1–5 cm/pixel** for room measurements). Gravity axis comes from ARCore's gravity vector / `model_orientation_aligner`.
- **RoomFormer (CVPR 2023)**: single-stage Transformer that takes the density map and emits variable-size room polygons via two-level (room + corner) queries, "without hand-crafted intermediate stages," with **fast inference (0.01 s/scene)** and state-of-the-art results on Structured3D and SceneCAD. It "can readily be extended to predict additional information, i.e., semantic room types and architectural elements like doors and windows" (the SD-TQ variant). Performance is contingent on density-map quality, so clean the point cloud first.
- **PolyRoom (ECCV 2024)**: room-aware Transformer (uniform-sampling representation, room-aware query initialization + self-attention) that reduces missing corners/edges and self-intersecting polygons. On Structured3D it "enhances the F1 score of corner and angle by 4.8% and 0.8% respectively compared to the SLIBO-Net which relies on the Manhattan assumption." Prefer PolyRoom when RoomFormer produces unclosed/overlapping polygons.
- **Classical fallback / wall detection**: RANSAC plane fitting to find vertical wall planes and horizontal floor/ceiling; apply **Manhattan-world regularization** to snap walls to dominant orthogonal directions. Tune the RANSAC distance threshold to sensor noise (~2–5 cm).
- **Clutter/furniture**: furniture creates spurious density; segment out non-structural points by **height-slicing** (keep a horizontal slab at wall height, e.g. ~1.0–1.8 m) before building the density map, then rely on RoomFormer/PolyRoom's learned priors to ignore residual clutter.
- **Doors/windows**: detect as gaps/low-density in walls or via the semantic-rich RoomFormer variant; cross-check with ARCore depth (openings read as far depth).

### 2.5 Metric accuracy tuning
- **Align to metric scale** via `model_aligner` against ARCore camera centers (Sim3/7-DoF) or, as fallback, Umeyama alignment to a known reference object / ARCore depth. Fix intrinsics to ARCore values to avoid focal/scale coupling.
- **Reduce drift**: loop closure in capture + sequential matching with loop detection; global BA; pose priors weighted by covariance.
- **Validate scale**: compare model measurements (door heights, tape-measured walls) to ground truth and report percent error. Where a reference laser scan exists, CloudCompare RMSE against it is the gold standard (datasets such as FIORD register COLMAP output to a Faro Focus 3D scan via ICP for exactly this purpose) — set realistic expectations, as RGB-only room-scale RMSE is typically centimeters to decimeters at building scale.
- **Target**: < 2% linear error on room dimensions for buyer-facing measurements; disclose that RGB-only measurements are indicative, not survey-grade.

### 2.6 Quality evaluation metrics
- **Rendering**: PSNR, SSIM, LPIPS [arxiv](https://arxiv.org/pdf/2407.12306) on held-out views (Nerfstudio reports these directly). Plateauing PSNR with a growing Gaussian count signals over-densification.
- **Geometry**: **Chamfer distance** and **F1-score** vs a reference mesh (DTU/TnT protocol); 2DGS reports Chamfer, PGSR reaches 0.47 mm mean on DTU. Watch the voxel-size/Chamfer tradeoff (finer voxel → more noise → worse Chamfer).
- **SfM health**: registration rate (fraction of images registered), mean reprojection error, track length, and number of 3D points — a low registration rate flags capture/matching problems.
- **Metric**: percent error vs tape measurements; scale-bar residual.

## PART 3 — UTILIZING THE HETEROGENEOUS DUAL-GPU MACHINE (RTX 3080 + RTX 5060)

### 3.1 The hardware reality
- **RTX 3080**: Ampere, compute capability **sm_86**, ~10–12 GB GDDR6X, works with mature CUDA 11.x/12.x and stable PyTorch wheels.
- **RTX 5060**: Blackwell, compute capability **sm_120**, ~8 GB, and **requires CUDA 12.8+ and a PyTorch built with sm_120 kernels** (currently a nightly `cu128`/`cu129` wheel, or a source build with `TORCH_CUDA_ARCH_LIST="12.0"`). [GitHub](https://github.com/bajegani/pytorch-build-blackwell-sm120) On older PyTorch you get `CUDA error: no kernel image is available for execution on the device` [GitHub](https://github.com/pytorch/pytorch/issues/159207) / "NVIDIA GeForce RTX 5060 with CUDA capability sm_120 is not compatible with the current PyTorch installation."
- **Single host, both cards**: install a **single recent NVIDIA driver** new enough for Blackwell (it will also drive Ampere) and **CUDA 12.8+**. Build/install one PyTorch containing **both sm_86 and sm_120 kernels** — `TORCH_CUDA_ARCH_LIST="8.6;12.0"` for a source build, or a nightly cu128/cu129 wheel with fat binaries. gsplat's CUDA extension and COLMAP must likewise be compiled for both architectures.

### 3.2 Can 3DGS use both GPUs for ONE scene? (Mostly no — don't.)
- **gsplat/Grendel-GS distributed training** exists: Grendel-GS (ICLR 2025 Oral, arXiv:2406.18533) partitions Gaussians across GPUs via sparse all-to-all communication + batched multi-view training, using "a simple sqrt(batch_size) learning rate scaling strategy, which enables efficient, hyperparameter-tuning-free training for batch sizes beyond one." Its strategy was merged into gsplat (PR #253) [GitHub](https://github.com/nyu-systems/Grendel-GS/blob/main/README.md) and is launched with `torchrun --standalone --nnodes=1 --nproc-per-node=N train.py --bsz N`.
- **But** it assumes **homogeneous GPUs and NCCL** collectives. With mismatched cards:
  - NCCL synchronization runs at the **speed of the slowest GPU**, and in heterogeneous settings performance is "consistently bounded by the slower of the two" — the 8 GB Blackwell 5060 would throttle the 3080, and the VRAM imbalance means the smaller card OOMs first, capping the whole job.
  - Mixing sm_86 + sm_120 in one NCCL job is fragile and offers little benefit for room-scale scenes that fit comfortably on one card.
- **Verdict**: distributed single-scene training targets large homogeneous clusters — Grendel's headline result is "a test PSNR of 27.28 by distributing 40.4 million Gaussians across 16 GPUs, compared to a PSNR of 26.28 using 11.2 million Gaussians on a single GPU" on the 4K Rubble aerial scene. For a real-estate prototype, **train each scene on one GPU** and parallelize across jobs/stages.

### 3.3 Recommended architecture: parallelism across jobs/stages
Pin work to GPUs and run **one Celery worker per GPU**, each with `--concurrency=1` and its own queue:

```bash
# Worker A — big GPU (RTX 3080), 3DGS render branch + heavy training
CUDA_VISIBLE_DEVICES=0 celery -A app.tasks worker \
    -Q gpu_3080 --concurrency=1 -n worker_3080@%h --loglevel=info

# Worker B — Blackwell GPU (RTX 5060), SfM + surface/floor-plan branch
CUDA_VISIBLE_DEVICES=1 celery -A app.tasks worker \
    -Q gpu_5060 --concurrency=1 -n worker_5060@%h --loglevel=info
```

Inside a task the process sees only its assigned GPU as device 0. Route tasks with Celery queues (`task.apply_async(queue="gpu_3080")`). `CUDA_VISIBLE_DEVICES` is "the canonical way for CUDA jobs to run concurrently without conflict."

**Which GPU does what** (assign by VRAM and driver maturity):
- **RTX 3080 (more VRAM, mature stack)** → **Splatfacto/gsplat 3DGS training** (most VRAM- and compute-hungry, longest-running) and Mip-Splatting rendering.
- **RTX 5060 (Blackwell, 8 GB)** → **COLMAP/GLOMAP SfM** (feature extraction/matching is GPU-light per image and fits 8 GB), the **2DGS/PGSR surface branch** (run at `-r 2` / reduced resolution to fit 8 GB, or cap Gaussians via MCMC), and **RoomFormer/PolyRoom inference** (tiny).
- This lets **SfM for the next scene run on the 5060 while the 3080 trains 3DGS for the current scene** — a straightforward pipeline-parallel throughput win of roughly 2× scenes in flight.

### 3.4 COLMAP multi-GPU
- COLMAP feature extraction/matching accept **`--FeatureExtraction.gpu_index` / `--FeatureMatching.gpu_index`** with comma-separated indices (`0,1`) to use both GPUs, "one thread per GPU. [Readthedocs](https://colmap.readthedocs.io/en/latest/faq.html) " **Caveats**: historically SiftGPU could run on only one GPU and users report little real speedup from multi-GPU; the GPU extractor doesn't support `domain_size_pooling`/`estimate_affine_shape`. For this prototype it's cleaner to **dedicate SfM to the 5060** (`--FeatureExtraction.gpu_index 1`) and keep the 3080 free for 3DGS, rather than splitting one SfM job across mismatched cards.

### 3.5 Docker / deployment
- Use the **NVIDIA Container Toolkit** and pin GPUs per container:
```bash
docker run --gpus '"device=0"' your-image:latest   # 3080 container (3DGS)
docker run --gpus '"device=1"' your-image:latest   # 5060 container (SfM/surface)
```
- Build the image on a CUDA 12.8+ base with PyTorch cu128/cu129 and gsplat/COLMAP compiled for `8.6;12.0` so the same image runs on either card. Verify inside each container with `torch.cuda.get_device_capability()` (expect (8,6) on the 3080, (12,0) on the 5060).

### 3.6 Memory & throughput considerations, pitfalls
- **8 GB on the 5060 is the binding constraint** for any Gaussian training there — cap Gaussian count (MCMC `cap_max`), downscale images (`-r 2`, `num_downscales`), and reduce batch/keyframe count. (COLMAP OOM in matching → lower `--FeatureMatching.max_num_matches`; approximate SIFT matching memory ≈ `4·num_matches² + 4·num_matches·256` bytes.)
- **Pitfalls**: (1) a PyTorch/gsplat build lacking sm_120 → 5060 silently unusable or CPU fallback; (2) mismatched CUDA between COLMAP and PyTorch; (3) attempting NCCL across the two cards → hangs/slowdowns bounded by the slower card; (4) a driver too old for Blackwell → 5060 not detected. Always install one driver that covers the newest card.
- **Throughput model**: per-GPU workers give ~2× scene throughput (two independent scenes, or SfM/3DGS pipelined) — the right optimization for a prototype rather than chasing single-scene speedups from fragile heterogeneous distribution. If you later standardize on matched GPUs, revisit Grendel-GS/gsplat distributed training.

---

## Recommendations

**Stage 1 — Capture app (build first):**
1. Ship an ARCore capture app that records video (4K if available, 30 fps) + per-frame pose + intrinsics + depth via the Recording API, with **AE/AF/AWB locked** and shutter forced fast (≤ 1/100 s).
2. Enforce the **lawnmower + perimeter orbit, 3-height, loop-closed** protocol with an on-screen overlap + tracking-state HUD; abort-and-guide on `TrackingState.PAUSED` using `getTrackingFailureReason()`.
3. Include a **known-length reference object** in each room.
4. Change trigger: if reconstructions show > 2% scale error, upgrade reference objects and enforce stronger IMU-exciting motion at session start.

**Stage 2 — SfM:**
5. hloc SuperPoint+LightGlue (or COLMAP ALIKED+LightGlue), **sequential matching + loop closure + guided matching**, shared/fixed intrinsics from ARCore, **relaxed triangulation angle**, `pose_prior_mapper` + `model_aligner` for metric scale.
6. Change trigger: if registration rate < ~90% or scale error > 2%, relax the triangulation angle further, add overlap, or switch incremental ↔ global mapper.

**Stage 3 — Gaussian branches:**
7. Train **Splatfacto** on the 3080 with quality settings (`cull_alpha_thresh=0.005`, `continue_cull_post_densification=False`, [Nerf Studio](https://docs.nerf.studio/nerfology/methods/splat.html) antialiased/Mip-Splatting, bilateral grid if exposure drifts), 30k iters, SfM-initialized.
8. Train **PGSR** (or 2DGS) on the 5060 at `-r 2` for the mesh; TSDF voxel ~6–10 mm, `depth_trunc` set to room size; normal reg from iter 7000.
9. Change trigger: floaters/aliasing → lower cull threshold, enable scale reg + Mip filter; noisy mesh → coarsen voxel; too many Gaussians for 8 GB → MCMC `cap_max`.

**Stage 4 — Mesh & floor-plan:**
10. Manhattan-align (`model_orientation_aligner`), height-slice out furniture, build a 1–5 cm/pixel top-down density map, run **PolyRoom** (fallback RoomFormer) for vectorized rooms + doors/windows.

**Stage 5 — Orchestration:**
11. Two Celery workers, one per GPU, queue-routed; single CUDA 12.8+ driver; PyTorch/gsplat/COLMAP built for `8.6;12.0`; NVIDIA Container Toolkit with per-device pinning. Pipeline SfM(next) ∥ 3DGS(current).

**Stage 6 — QA:**
12. Auto-report PSNR/SSIM/LPIPS, registration rate, Gaussian count, and tape-measure percent error per scene; gate buyer delivery on scale error < 2%.

---

## Caveats
- **Hyperparameter defaults drift between versions.** Splatfacto's `densify_grad_thresh` (0.0008 model-level vs gsplat DefaultStrategy 0.0002 vs big 0.0006) and 2DGS/PGSR loss weights differ across commits — verify against your pinned versions. The PGSR loss weights (~0.015 / 0.15 / 0.03) are corroborated from the paper plus downstream adopters, not read line-by-line from the repo's `arguments/__init__.py`; confirm indoor voxel/trunc values in `scripts/run_tnt.py`.
- **RGB-only metric accuracy is indicative, not survey-grade.** Even aligned to ARCore/LiDAR references, room-scale RMSE can be centimeters to decimeters; disclose this to buyers. ARCore VIO scale can drift on long walks and degrades with poor IMU excitation or textureless scenes. (The specific "25 cm RMSE on a 220 m² room" figure I initially cited for FIORD could not be verified and has been removed; FIORD confirms the ICP-to-Faro registration methodology but I could not confirm that exact number.)
- **ARCore Depth API** is device-limited, and the Recording API stores only smoothed (not raw) depth; depth on blank walls/glass is unreliable.
- **Blackwell support is still maturing** (mid-2026): stable PyTorch wheels for sm_120 were not yet available in the sources reviewed, forcing nightly/source builds — the single biggest setup risk for the 5060.
- **Distributed 3DGS across mismatched GPUs is not recommended**; this guide's throughput strategy is deliberately job-parallel.
- Some capture-guide figures (exact shutter values, overlap percentages, walking speed) come from vendor blogs and a single forensic study; treat them as well-supported rules of thumb, not universal constants, and calibrate to your devices.