# Production Architecture: RGB-Only 3D Mapping Platform with Gaussian Splatting and Floor-Plan Export

## TL;DR
- **Build a capture-only Android app + self-hosted cloud pipeline (COLMAP/GLOMAP → gsplat/Splatfacto for the splat, plus a parallel 2DGS/surface-reconstruction branch for a metric mesh → floor-plan vectorization with RoomFormer/PolyRoom), served to web (PlayCanvas SuperSplat / Spark) and mobile viewers.** This is fully achievable with open-source tools in 2025-2026; no commercial SaaS is required.
- **The single hardest requirement is metric scale for real-estate measurements.** RGB-only SfM is scale-ambiguous; you MUST inject scale from ARCore VIO poses (fed to `colmap model_aligner`), monocular metric depth (UniDepth V2 / Metric3D V2 class models ≈ 5-6% relative depth error indoors), and/or a known reference object. Expect ~1-3% linear error with ARCore VIO, sub-1% with a physical scale bar. Be transparent with buyers about tolerances.
- **Prototype on a single RTX 4090/3090 box with a Celery+Redis queue; scale to Kubernetes with GPU autoscaling (Karpenter + NVIDIA device plugin) and Argo Workflows for the multi-stage DAG.** A room-scale scene trains in ~20-40 min and costs pennies-to-a-few-dollars of GPU time per scene.

## Key Findings

1. **SfM for RGB indoor capture:** Use **GLOMAP** (global SfM; per "Global Structure-from-Motion Revisited," ECCV 2024/arXiv:2407.20219, it achieves "results on-par or superior to COLMAP, the most widely used incremental SfM, while being orders of magnitude faster," with "approximately 8% higher recall and scores 9 and 8 additional points in AUC at the 0.1m and 0.5m thresholds") on top of COLMAP feature extraction/matching, with **hloc** (SuperPoint+SuperGlue/LightGlue) for robust indoor matching in low-texture rooms. VGGT/MASt3R are promising learned front-ends but capped at ~512-518px input; use as fallback/initializer, not the primary metric backbone yet. FastMap (2025) is an even faster option but newer/less proven.
2. **3DGS training:** Use **gsplat** (Nerfstudio backend) via **Splatfacto**. Per the gsplat paper (arXiv:2409.06765, JMLR 2025), "the training takes up to 4x less GPU memory with up to 15% less time to finish than the official implementation" (A100 benchmark: 30k-iter gsplat 5.6 GB / 19.39 min vs original 3DGS 9.0 GB / 26.19 min). For the deliverable *floor plan*, run a parallel **2D Gaussian Splatting (2DGS)** or **MILo/PGSR/GOF** surface branch — these produce geometrically accurate meshes, unlike vanilla 3DGS.
3. **Metric scale:** ARCore camera poses are already metric (visual-inertial odometry). Feed per-frame camera centers as Cartesian references to `colmap model_aligner --ref_is_gps 0 --alignment_type custom` to rescale the reconstruction to true meters. Supplement with UniDepth V2/Metric3D V2 metric-depth priors and a printed scale bar for verification.
4. **Floor plan:** Extract a mesh/point cloud from the 2DGS branch → project to a top-down density map → vectorize rooms with **RoomFormer** (CVPR 2023) or **PolyRoom** (ECCV 2024), both open-source and trained on Structured3D. Geometric fallback: RANSAC plane detection + Manhattan-world wall fitting.
5. **Viewers:** Web — **PlayCanvas SuperSplat viewer** (WebGPU with WebGL2 fallback, streamed SOG LOD) as the primary; **Spark** (Three.js, .spz) for mobile-first. Delivery format: **SPZ** (Niantic; ~10× smaller than PLY). Mobile — embed the same web viewer in a WebView, or PlayCanvas/Unity native.
6. **Orchestration:** Prototype = Celery + Redis/RabbitMQ + single GPU. Scale = Argo Workflows on Kubernetes with Karpenter GPU autoscaling on spot instances, MinIO/S3 object storage, tus resumable uploads.

## Details

### 1. Reconstruction Pipeline (the hard core)

**1a. Structure-from-Motion / pose estimation (RGB-only).**
- **COLMAP** remains the robust, battle-tested baseline: incremental SfM with feature extraction, matching, geometric verification, incremental registration + bundle adjustment. Best accuracy/robustness but poor scalability — on large image sets it can be one to two orders of magnitude slower than global methods, [arXiv](https://arxiv.org/abs/2505.04612) and on some benchmark scenes COLMAP took thousands of seconds vs. tens for global methods.
- **GLOMAP** (Pan, Baráth, Pollefeys & Schönberger, 2024, `github.com/colmap/glomap`) is the recommended primary: a global SfM that consumes a COLMAP database (features + matches) and outputs a COLMAP-compatible sparse reconstruction. [DeepWiki](https://deepwiki.com/colmap/glomap) It performs global rotation + translation averaging then a single global bundle adjustment. Its key advantage over older global SfM is the robust translation-averaging step.
- **hloc** (hierarchical localization) with learned features (SuperPoint + SuperGlue or LightGlue) is strongly recommended for **indoor** capture where walls/ceilings are low-texture — learned matching dramatically improves registration where classic SIFT fails. Feed hloc matches into the COLMAP database, then run GLOMAP.
- **Learned/foundation SfM (VGGT, MASt3R, DUSt3R):** VGGT (Wang et al., `facebookresearch/vggt`, **CVPR 2025 Best Paper Award**) "directly infers all key 3D attributes of a scene, including extrinsic and intrinsic camera parameters, point maps, depth maps, and 3D point tracks, from one, a few, or hundreds of its views, within seconds." MASt3R-SfM is a fully-integrated unconstrained SfM. **Caveat:** DUSt3R/MASt3R are limited to ~512px max dimension and VGGT to ~518px on mainstream 2025 GPUs, which limits fine metric detail; they can produce local drift/collapse on long indoor trajectories. Use them as a robust initializer for sparse-overlap captures or as a fallback when COLMAP/GLOMAP fail to register, not as the primary metric backbone in v1.
- **FastMap (2025, arXiv:2505.04612):** first-order-optimization global SfM — "FastMap is up to 10 times faster than COLMAP and GLOMAP with GPU acceleration and achieves comparable pose accuracy" (v3 claims one to two orders of magnitude faster on large scenes). Worth benchmarking but newer/less proven.
- **Recommendation:** hloc (SuperPoint+LightGlue) → COLMAP database → GLOMAP → `model_aligner` for scale. Keep COLMAP incremental as a fallback for hard scenes.

**1b. 3D Gaussian Splatting training.**
- **gsplat** (`github.com/nerfstudio-project/gsplat`) is the recommended CUDA rasterization backend: reproduces official INRIA 3DGS quality (PSNR/SSIM/LPIPS) with up to 4× less GPU memory and up to 15% less time. Drive it through Nerfstudio's **Splatfacto** method (`ns-train splatfacto`), which blends multiple 3DGS techniques and exports `.ply`.
- **Variants and when to use them:**
  - **Mip-Splatting** — anti-aliasing for varying resolution/zoom; important for a viewer where buyers zoom in/out.
  - **2D Gaussian Splatting (2DGS, Huang et al. SIGGRAPH 2024)** — collapses one Gaussian dimension into view-consistent 2D disks + TSDF fusion, giving geometrically accurate surfaces. [arxiv](https://arxiv.org/pdf/2502.20154) **This is the branch to use for mesh/floor-plan extraction**, not vanilla 3DGS.
  - **GOF (Gaussian Opacity Fields)** — extracts geometry directly from a level-set of the opacity field, no Poisson/TSDF needed; improved completeness vs 2DGS.
  - **PGSR** — planar-aligned Gaussians encoding normals + signed distance; strong indoor geometric accuracy. [arxiv](https://arxiv.org/pdf/2511.13278)
  - **SuGaR (CVPR 2024)** — surface-aligned regularization + Poisson meshing, minutes to extract an editable mesh. [GitHub](https://github.com/Anttwo/SuGaR)
  - **MILo (SIGGRAPH Asia 2025)** — differentiable mesh-in-the-loop extraction; base model 0.1-0.5M Gaussians / ~10GB VRAM / ~25 min bounded, dense model up to 4M Gaussians / ~17GB / up to ~2h.
  - **Scaffold-GS / Hierarchical-3DGS / CityGaussianV2** — for large or multi-floor buildings where a single flat 3DGS struggles.
- **Recommendation:** Two branches from the same posed images — (1) Splatfacto/gsplat 3DGS for the photorealistic viewer deliverable; (2) 2DGS (or PGSR/GOF) for the metric mesh used to derive the floor plan.

**1c. Metric scale (critical for real-estate measurement).**
- RGB-only SfM/3DGS is inherently **scale-ambiguous** — the reconstruction is correct up to an unknown similarity transform. You must inject metric scale from an external source. Three complementary methods:
  1. **ARCore VIO poses (primary).** ARCore fuses camera + IMU to produce metric camera poses. Export per-frame camera center positions (meters) and feed them as Cartesian references to COLMAP's `model_aligner`, which fits a 7-DoF similarity (rotation+translation+**scale**) via RANSAC, requiring ≥3 images with reference positions. Exact command:
     ```
     colmap model_aligner \
       --input_path sparse/0 --output_path sparse_aligned \
       --ref_images_path ref.txt \
       --ref_is_gps 0 --alignment_type custom \
       --alignment_max_error 0.05   # meters; set to expected VIO noise
     ```
     where `ref.txt` has lines `image_name X Y Z`. **Critical gotcha:** if the max-error threshold is not set (>0), the model is output unchanged; `--estimate_scale 1` (default) must remain on; ≥3 matching image names (`--min_common_images`) are required. Newer COLMAP also offers a `pose_prior_mapper` that ingests position priors *during* mapping (`--prior_position_std_x/y/z`). (COLMAP FAQ "Geo-registration"/"Reconstruction with pose priors"; GitHub issues #2398, #2314.)
  2. **Monocular metric depth priors.** UniDepth V2 (Piccinelli et al., arXiv:2502.20110, 2025) is a zero-shot metric-depth model evaluated across ten datasets; Metric3D V2 is a state-of-the-art zero-shot metric depth/normal foundation model; Depth Anything V2 (metric) is another option. These give roughly ~5-6% relative depth error indoors (≈±5-10 cm at 1-2 m) — good for seeding/scale sanity-check, noisier than VIO for absolute room dimensions. (Note: a specific "5.78% NYUv2 AbsRel" figure circulates for UniDepth but I could not confirm it from the primary paper/repo — treat as indicative, not sourced.)
  3. **Known reference object / scale bar.** A printed scale bar or a door of measured width placed in the scene yields the lowest scale error (sub-1%) when it spans a large fraction of the scene and endpoints are sharply localized; % error scales inversely with reference length.
- **Accuracy expectations:** ARCore VIO room-scale linear scale error ~1-3%. Published VIO benchmarks (Sensors/PMC9785098) find Apple **ARKit** tighter than ARCore for scale/shape (ARKit relative drift ~0.02 m/s, the most stable of four tested VIO systems). Straight-line-only motion degrades monocular VIO scale (up to +9% distance overestimate) because it under-excites the IMU; capture with curved/figure-eight motion to condition scale. Combine methods and cross-check.
- **Error sources:** VIO drift over long trajectories, motion blur breaking tracking, straight-line under-excitation, textureless walls, and reflective/glass surfaces. Mitigate with guided capture UX, loop closures (revisit the start), and a physical reference object.
- **Skip-SfM shortcut (evaluate):** Tools like NeRFStudio's `ns-process-data record3d` / `polycam`, `NeRFCapture`, and instant-ngp's `record3d2nerf.py` ingest device (ARKit/ARCore) metric poses directly into a NeRF/GS `transforms.json`, bypassing SfM and preserving metric scale — but Nerfstudio dataparsers **auto-normalize poses into a unit box by default** (`auto_scale_poses`), so you must disable that and record the applied scale factor to keep true meters. These are iOS/ARKit-centric today; on Android, running GLOMAP + `model_aligner` with ARCore poses is the more reliable metric path.

**1d. Mesh/surface extraction and floor-plan generation.**
- From the 2DGS branch, extract a mesh via TSDF fusion (2DGS's `mesh_extract`) or GOF level-set; clean floaters in SuperSplat.
- **Floor plan:** project the metric mesh/point cloud to a **top-down density map**, then vectorize rooms:
  - **RoomFormer** (`github.com/ywyue/RoomFormer`, CVPR 2023) — single-stage Transformer with two-level (polygon/corner) queries, predicts a variable-size set of room polygons in parallel; SOTA on Structured3D and SceneCAD, and can be extended to predict doors/windows and room types.
  - **PolyRoom** (`github.com/3dv-casia/PolyRoom`, ECCV 2024) — room-aware Transformer with uniform sampling + room-aware queries/self-attention; improves on RoomFormer's missing corners/self-intersections.
  - **FRI-Net** (ECCV 2024) — room-wise implicit representation with structural regularization.
  - **Geometric fallback (no ML):** RANSAC plane detection → classify floor/ceiling/walls by normal → project walls to 2D → fit line segments under a Manhattan-world assumption → close polygons. Robust and interpretable; good default when learned models don't generalize to your building types. (PlanarGS/Manhattan-SDF-style planar priors also help the upstream splat geometry in textureless rooms.)
- Overlay wall dimensions from the metric scale; export SVG/DXF/PDF floor plan with measurements.

### 2. Android Capture App

- **Video vs burst photos:** For 3DGS, **high-frame-rate video with aggressive frame selection** is the pragmatic choice for room-scale coverage (faster capture, guaranteed overlap), but each extracted frame must be sharp. Professionals often prefer photos because casual video "inevitably exhibits motion blur." Practical rule: capture video at max resolution, low frame rate (~24fps), and **lock exposure with a fast shutter (1/200-1/1000s)** to kill motion blur; or shoot burst photos. Aim for 70-80% overlap, multiple heights, ~200-500+ frames for a room-scale scene.
- **Frame selection & blur detection:** On-device or server-side, discard blurry frames using variance-of-Laplacian or an FFT-based blur metric (EsFFT-style), and select keyframes that balance sufficient overlap against long-enough baselines (avoid tiny ~3cm baselines from 30fps walking).
- **Metadata to capture (ARCore):**
  - **Camera pose** per frame — `Frame.getCamera().getPose()` (OpenGL world-space, metric).
  - **Camera intrinsics** — `Camera.getImageIntrinsics()` / `getTextureIntrinsics()` (focal length, principal point, image dimensions via `CameraIntrinsics`).
  - **IMU** — raw accelerometer/gyro via Android SensorManager, timestamped to frames.
  - **Depth** — ARCore **Depth API** (depth-from-motion on RGB, no LiDAR needed) for per-frame depth to aid scale/geometry.
  - **GPS** — coarse geo-tag for organizing captures.
  - Record everything with a common high-resolution timestamp for temporal consistency (MCAP or a simple JSON + binary sidecar).
- **Capture guidance UX:** overlay coverage map / "paint the walls" progress, warn on fast motion (blur risk) and tracking loss (`getTrackingFailureReason()`), prompt curved motion and a return-to-start loop for scale conditioning, and encourage capturing a placed reference object.
- **Upload strategy:** **tus resumable upload protocol** (`tus.io`, `tus/tusd` Go reference server) over HTTP, backed by S3/MinIO multipart uploads. Chunked/resumable is essential for large image sets/video over mobile networks; tus survives network interruptions and drops into existing load balancers/auth. Client: `tus-js-client`/Uppy or a native Kotlin tus client. Compress/transcode video on-device before upload where possible.

### 3. Cloud Backend & Processing Architecture

**Prototype (single GPU box):**
- One workstation/server with an **RTX 4090 or 3090 (24GB)**.
- **FastAPI** REST API for auth, job submission, status.
- **Celery** workers + **Redis** (or RabbitMQ) broker for the job queue. Celery is simple and flexible for asynchronous long-running tasks.
- **MinIO** (S3-compatible) for raw captures, SfM intermediates, splats, floor plans.
- Docker + **NVIDIA Container Toolkit** for the GPU pipeline image (COLMAP+GLOMAP+hloc+gsplat+2DGS+RoomFormer).
- Postgres for job/scene metadata and multi-tenant records.

**Scalable production build:**
- **Kubernetes** with GPU node pools. **NVIDIA device plugin** advertises `nvidia.com/gpu`; GPU requested via `limits` (integer only; use time-slicing/MIG for sharing). **NVIDIA GPU Operator** manages the driver/toolkit stack.
- **Karpenter** for just-in-time GPU node provisioning on **spot instances** with broad instance-family NodePools (g5/g6/p4d/p5), `consolidationPolicy: WhenEmptyOrUnderutilized`, scale-to-zero when idle — critical because idle GPUs are expensive (e.g., a 4-GPU node ~$3,359/mo if always-on vs ~$57/mo at 1h/day on spot, per the OpenFaaS/Karpenter analysis). Keep an on-demand floor + spot for burst.
- **Argo Workflows** for the multi-stage DAG (upload → SfM → 3DGS + 2DGS → mesh → floor plan → package/serve). Argo is Kubernetes-native (workflows as CRDs), gives per-step containers, retries, fault tolerance, spot-friendliness — one team (CloudRaft) reported an 11× compute-cost reduction migrating Celery→Argo for long GPU jobs. Alternatives: Prefect (Python-native), Flyte (typed ML workflows), Kubeflow Pipelines.
- **Storage:** S3/MinIO object storage with lifecycle policies (raw captures → cold tier), CDN (CloudFront/Cloudflare) for splat/floor-plan delivery with long immutable cache on content-addressed SPZ files.
- **API layer:** REST (FastAPI) for clients; optionally gRPC for internal service-to-service. JWT/OAuth auth; per-tenant buckets/prefixes and row-level DB isolation for multi-tenancy.
- **Autoscaling pattern:** KEDA scales worker pods on queue depth; Karpenter/Cluster Autoscaler scales GPU nodes underneath. Cache the pipeline image / model weights on nodes (Bottlerocket data volume or pre-pull) to cut GPU cold-starts (AI images often >10GB).

### 4. Viewers (web + mobile)

- **Web (primary): PlayCanvas SuperSplat viewer** (`github.com/playcanvas/supersplat-viewer`, MIT). PlayCanvas Engine 2.19+ added a compute-based **WebGPU** renderer with automatic **WebGL2 fallback** (WebGPU ~85% of users per caniuse), plus **Streamed SOG** LOD (auto-decimated levels) for near-instant loads on phones. SuperSplat also doubles as the cleanup/crop editor. [arXiv](https://arxiv.org/html/2601.15431v1)
- **Web (mobile-first alt): Spark** (`sparkjsdev/spark`, Three.js/React, WebGL2) — lean, built around Niantic's **.spz**, best when file size/battery matter. Note a 2025 analysis (Visionary, arXiv:2512.08478) found Spark uses CPU/lazy sorting that can show artifacts under fast rotation vs. a true global GPU sort; validate for your camera paths.
- **Other options:** mkkellogg **GaussianSplats3D** (Three.js, introduced `.ksplat` streaming format), **antimatter15/splat** (WebGL1, excellent mobile compatibility), **Babylon.js** (native .ply + .spz 8.0+), PlayCanvas model-viewer.
- **Formats:** deliver **SPZ** as primary. Per Niantic's `nianticlabs/spz`, "spz encoded splats are typically around 10x smaller than the corresponding .ply files, with minimal visual differences"; SPZ is on the Khronos glTF standardization track (`KHR_gaussian_splatting_compression_spz`), and **SPZ 4** (released ~May 2026) uses six parallel ZSTD streams, removes the ~10M-point ceiling, and supports SH degree 4. (License note: Scaniverse's launch announcement stated MIT; the enricher flagged the repo as Apache 2.0 — verify the current license before shipping.) Use SPLAT/`.ksplat` as fallback and PLY as last resort. Keep deliveries **under ~3M Gaussians for mobile, ~5M for desktop**; use SuperSplat/splat-transform to decimate.
- **Mobile (Android):** embed the same WebGPU/WebGL viewer in a WebView/PWA (simplest, one codebase) or go native (Unity via `aras-p/UnityGaussianSplatting`, or PlayCanvas). WebView is recommended for v1.
- **Measurement/annotation in-viewer:** because the splat/mesh is metric-scaled, implement point-to-point distance measurement (ray-cast to nearest splat/mesh surface, Euclidean distance in meters), area, and annotations. Display the extracted 2D floor plan alongside the 3D view with clickable room dimensions.
- **Streaming/LOD:** SOG streamed LODs (SuperSplat) or spatial-chunk progressive loading (octree/grid of SPZ chunks, nearest-first) for large multi-room scenes.

### 5. End-to-End Architecture Synthesis

**Data flow (textual diagram):**
```
[Android App]
  RGB video/burst + ARCore poses + intrinsics + IMU + depth + GPS
        │ (tus resumable upload, chunked)
        ▼
[API Gateway: FastAPI + Auth]───►[Object Storage: S3/MinIO raw/]
        │ enqueue job
        ▼
[Orchestrator: Celery(proto) / Argo Workflows(prod)]
        ▼
  Stage 1  Frame extraction + blur filtering + keyframe selection
        ▼
  Stage 2  SfM: hloc (SuperPoint+LightGlue) → COLMAP DB → GLOMAP
           → model_aligner (ARCore poses → METRIC scale)
        ▼
  ├─Stage 3a  gsplat/Splatfacto 3DGS  → .ply → SPZ  (viewer deliverable)
  └─Stage 3b  2DGS/PGSR/GOF          → TSDF mesh   (geometry)
        ▼
  Stage 4  Mesh → top-down density map → RoomFormer/PolyRoom
           → vectorized floor plan (SVG/DXF/PDF + dimensions)
        ▼
[Artifact Storage: S3/MinIO  splats/ meshes/ floorplans/]
        │
        ▼
[CDN]───►[Web Viewer: SuperSplat/Spark]  &  [Android WebView Viewer]
          + measurement tools + floor-plan overlay
```

**Recommended tech stack:**

| Layer | Prototype | Scalable production |
|---|---|---|
| Capture | Android + ARCore (Kotlin) | same + hardened capture UX/QA |
| Upload | tus + MinIO | tus/tusd + S3 multipart + CDN |
| API | FastAPI + Postgres | FastAPI + Postgres + gRPC internal |
| Queue/orchestration | Celery + Redis | Argo Workflows on K8s (+KEDA) |
| GPU compute | 1× RTX 4090/3090, Docker+NVIDIA toolkit | K8s GPU nodes, NVIDIA GPU Operator, Karpenter spot autoscaling |
| SfM | hloc + COLMAP + GLOMAP | same, parallelized per-scene |
| Splat | gsplat/Splatfacto | same + Hierarchical/Scaffold-GS for large |
| Geometry | 2DGS/PGSR + TSDF | same + GOF/MILo |
| Floor plan | RoomFormer or geometric | RoomFormer/PolyRoom + geometric fallback |
| Storage | MinIO | S3 + MinIO + lifecycle + CDN |
| Viewer | SuperSplat (SPZ) | SuperSplat + Spark, LOD/streaming |

**Key risks & mitigations:**
- **Metric accuracy (top risk):** RGB-only scale is error-prone. Mitigate with ARCore-pose alignment + reference object + depth priors; display tolerance ranges; offer an optional "measure a known object to calibrate" step. Do not market millimetre precision.
- **Textureless/reflective indoor surfaces:** walls, glass, mirrors break SfM. Mitigate with hloc learned matching, planar/Manhattan priors (PlanarGS-style), depth priors, and capture guidance.
- **Motion blur / tracking loss:** enforce fast shutter, blur filtering, and real-time UX warnings.
- **Large multi-room scenes:** single 3DGS degrades; use hierarchical/scaffold methods and chunked LOD streaming.
- **GPU cost blowout:** scale-to-zero, spot instances, cost circuit breakers (KEDA), per-scene budgets.
- **ARCore vs ARKit:** ARCore VIO is measurably weaker than ARKit for scale; consider a physical reference object mandatory on Android, or add iOS later.

**GPU requirements & cost (guidance):**
- **VRAM:** 24GB (RTX 3090/4090/A10) comfortably handles room-scale 3DGS; base surface models ~10GB, dense ~17GB (per MILo). 12GB (RTX 3060) works with reduced iterations. Large buildings → 48-80GB (A100/L40S/RTX 6000 Ada).
- **Training time:** 7k-iteration preview ~10 min on a 4090; full 30k iterations ~20-40 min for a room (gsplat A100 benchmark: 19.39 min at 5.6 GB). COLMAP/GLOMAP SfM adds ~5-30 min depending on frame count. 2DGS mesh + floor-plan adds ~25 min-2h.
- **Cost:** cloud GPU rentals ~$0.25-0.40/hr (RTX 4090), ~$0.45-1.10/hr (A100); a 30-min run costs ~$0.04-0.20. A team doing ~10 scenes/week reported ~$80/week training + ~$120/week serving ≈ $200/week fully cloud, vs $35k-50k CapEx for dedicated hardware (Spheron). Serving: one L40S handles a single large scene for ≤30 concurrent users; use CDN for static SPZ delivery to avoid per-view GPU cost.

## Recommendations

**Stage 0 — Prototype (weeks 1-6):**
1. Build the Android capture app: ARCore session recording RGB video + per-frame pose/intrinsics/IMU/depth/GPS to a timestamped bundle; tus upload to MinIO.
2. Stand up a single 24GB-GPU box with FastAPI + Celery + Redis + MinIO + Postgres.
3. Pipeline v1: frame extraction/blur filter → hloc+COLMAP+GLOMAP → `model_aligner` with ARCore poses → Splatfacto (gsplat) → SPZ. Serve in SuperSplat web viewer.
4. Add the 2DGS branch → TSDF mesh → geometric (RANSAC+Manhattan) floor plan; add point-to-point measurement in the viewer.
5. **Benchmark metric accuracy** against tape-measured rooms; this gates everything.

**Stage 1 — Harden (weeks 7-14):**
6. Swap geometric floor plan for RoomFormer/PolyRoom; add door/window detection.
7. Add capture-guidance UX (coverage, blur/tracking warnings, loop-closure prompt, reference-object step).
8. Add Android WebView viewer with measurement + floor-plan overlay.
9. Containerize the full pipeline; add job status, retries, per-scene QA metrics (PSNR, registration rate, scale residual).

**Stage 2 — Scale (as volume grows):**
10. Migrate orchestration to Argo Workflows on Kubernetes; add Karpenter GPU spot autoscaling + KEDA queue-based scaling + scale-to-zero.
11. Move to S3 + CDN; add SOG/SPZ LOD streaming; multi-tenant auth and quotas.
12. Add hierarchical/Scaffold-GS for large buildings; evaluate VGGT/FastMap front-ends.

**Thresholds that change the plan:**
- If measured scale error >3% on real rooms → make a physical reference scale bar mandatory and/or add iOS/ARKit capture.
- If SfM registration rate <90% on typical captures → invest more in hloc/learned matching or a VGGT/MASt3R initializer.
- If web viewer FPS <30 on target phones → decimate below 3M Gaussians and enable SOG LOD streaming.
- If GPU queue wait exceeds your SLA at peak → enable Karpenter burst to on-demand.

## Caveats
- **RGB-only metric reconstruction is fundamentally approximate.** All scale figures (~1-3% ARCore, ~5-6% monocular depth, sub-1% reference object) are context-dependent and degrade with poor capture (blur, straight-line motion, textureless/reflective surfaces). Validate per-device and per-building-type; disclose tolerances to buyers.
- ARCore VIO scale is device-dependent and, per published VIO benchmarks, generally less accurate than ARKit; some cited drift numbers come from small studies.
- The specific UniDepth V2 "5.78% NYUv2 AbsRel" figure could not be confirmed against the primary paper/repo — treat monocular-depth accuracy as ~5-6% indicative, not a verified benchmark number.
- Learned SfM (VGGT/MASt3R/DUSt3R) and FastMap are fast-moving 2024-2026 research; resolution caps and long-trajectory drift make them supplementary rather than the primary metric backbone today.
- Vanilla 3DGS is for rendering, NOT geometry — always use a surface-oriented variant (2DGS/PGSR/GOF/SuGaR/MILo) for the floor-plan/mesh branch.
- SPZ licensing is reported inconsistently (MIT in Scaniverse's announcement vs Apache 2.0 flagged from the repo) — confirm the current license before distribution.
- Training-time and cost figures are order-of-magnitude, drawn from 2026 community/vendor benchmarks on specific scenes; your numbers will vary with frame count, resolution, and scene size.