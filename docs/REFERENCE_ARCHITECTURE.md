# End-to-End System Architecture: Cloud 3D Gaussian Splatting for an Android Mapping App

## TL;DR
- **Build a "thin capture, fat cloud" pipeline**: an ARCore-based Android capture app that ships de-blurred keyframes plus poses/intrinsics/IMU and (where available) depth over resumable uploads; a GPU batch cloud that runs COLMAP/GLOMAP refinement + gsplat (Splatfacto) training to produce a splat in minutes; and a serving layer that streams SPZ/SOG-compressed, LOD-tiled splats back for native Vulkan or WebView rendering. This is essentially the production pattern used by Luma AI, Polycam, and Kiri Engine (cloud) and Niantic Scaniverse (on-device). [Radiance Fields](https://radiancefields.com/scaniverse-introduces-gaussian-splatting)
- **Recommended default stack (2025-2026)**: ARCore poses shipped alongside frames (cloud SfM as refinement/fallback), tus/S3-multipart resumable upload to object storage, containerized gsplat workers on single-GPU L4/L40S instances orchestrated by a queue + managed batch (AWS Batch/GCP Batch/Cloud Run GPU Jobs) or Argo on a GPU Kubernetes pool, output compressed to Niantic SPZ (~10× smaller than PLY) [Medium](https://medium.com/@Jamesroha/gaussian-splatting-a-complete-student-guide-to-3d-capture-in-2026-1195a6265870) and PlayCanvas SOG (~20× smaller) with 3D Tiles LOD, distributed via CDN.
- **Economics**: a typical room/object scene trains in ~10-40 min on one modern GPU; realistic all-in GPU cost is on the order of **$0.30-$1.00 per scene** using spot/preemptible single-GPU instances, making a "1,000 scenes/day for under ~$850" throughput achievable.

## Key Findings

1. **ARCore poses are worth shipping, but keep cloud SfM as a refinement/fallback stage.** ARCore gives you metric-scale 6DoF camera poses and intrinsics per frame for free on-device, which lets you skip or dramatically shorten COLMAP. Research on mobile/panorama capture (LighthouseGS) confirms AR poses + monocular depth can eliminate the SfM step, but AR poses carry error, so the best practice is a residual pose-refinement / bundle-adjustment pass in the cloud rather than blind trust.

2. **gsplat (Nerfstudio's Splatfacto backend) is the pragmatic production trainer**; the INRIA reference is the baseline, and Brush is the notable CUDA-free cross-platform option. Per the gsplat paper (Ye et al., arXiv 2409.06765, MipNeRF360 avg over 7 scenes on A100), at 30k iterations gsplat reached PSNR 29.00 in 19.39 min using 5.6 GB vs. the original 3DGS at 28.95 PSNR in 26.19 min using 9.0 GB — roughly **26% faster and ~38% less memory at equal quality** (at 7k iters both hit 27.23 PSNR; gsplat 3.36 min/4.3 GB vs 4.64 min/7.7 GB).

3. **Compression is the linchpin of the serving layer.** Raw PLY splats are hundreds of MB to 1 GB. Niantic SPZ (~10×), PlayCanvas SOG/SOGS (~20×), [Medium](https://medium.com/@Jamesroha/gaussian-splatting-a-complete-student-guide-to-3d-capture-in-2026-1195a6265870) and 3D-Tiles-based LOD streaming are what make mobile delivery viable.

4. **Mobile rendering is feasible but splat-count-bound.** Expect smooth rendering only up to a few hundred thousand to ~1-1.5M Gaussians on current phones without aggressive LOD/culling; memory (~180-220 MB per 1M Gaussians with full SH) is the hard constraint.

5. **Batch, single-GPU, spot-priced compute is the right cost model** — not always-on H100s. L4/L40S/T4 single-GPU instances (or Cloud Run GPU Jobs / serverless GPU) match the minutes-long, bursty job profile.

## Details

### Data Flow Overview
Capture (ARCore) → on-device keyframe selection + compression → resumable upload → object storage (raw bucket) → job queue → GPU worker (SfM refine → point-cloud init → gsplat train → compress) → asset storage (SPZ/SOG + LOD tiles) → CDN → Android viewer (native Vulkan or WebView).

---

### Layer 1 — Android Client / Capture

**RGB capture best practices.** Photogrammetry/3DGS quality is dominated by input quality: sharp focus, consistent exposure, uniform lighting, and 70-90% overlap between views are the standard requirements [Agisoft](https://www.agisoftmetashape.com/how-to-capture-the-best-photos-for-photogrammetry-drone-and-dslr-tips/) (Agisoft, Pix4D). Concretely on Android:
- **Frame selection / blur detection**: capture video (or burst) and select the sharpest frame within each interval. Standard techniques are variance-of-Laplacian and FFT-based frequency-domain sharpness scoring; combine with the phone's gyroscope to drop frames captured during fast rotation (motion blur). This is exactly the "minimum fps + sharpness analysis to pick sharpest frames" pipeline used in video-based photogrammetry. [Copernicus](https://isprs-archives.copernicus.org/articles/XLVIII-M-9-2025/533/2025/isprs-archives-XLVIII-M-9-2025-533-2025.pdf)
- **Exposure/white-balance locking**: lock AE/AWB (Camera2 `CONTROL_AE_LOCK`/`CONTROL_AWB_LOCK`) after an initial settle so brightness/color are consistent across frames — critical because 3DGS bakes view-dependent color and flickering exposure creates floaters.
- **Resolution/framerate tradeoffs**: 1080p-1600px-wide frames are typically sufficient; higher resolution increases COLMAP and training time super-linearly. Prefer a lower, steady capture rate with high sharpness over high fps. Fixed focal length (no zoom) keeps intrinsics stable.

**ARCore for pose/intrinsics.** Use `Frame`/`Camera` to export per-keyframe: `Camera.getPose()` (world-space 6DoF, OpenGL convention), `getImageIntrinsics()`, timestamp, and tracking state (only keep `TRACKING` frames). Ship these as a sidecar (e.g., a transforms.json à la Nerfstudio) alongside frames plus raw IMU.
- **vs. cloud SfM (COLMAP/GLOMAP)**: Shipping poses lets the cloud skip the slowest, most failure-prone stage. COLMAP (incremental) is robust but slow — GLOMAP's authors report it achieves accuracy on par with or superior to COLMAP "while being 1-2 orders of magnitude faster." Newer GPU-based FastMap is up to 10× faster than both on large scenes. [arXiv](https://arxiv.org/abs/2505.04612) Best practice: if ARCore poses are present, run only a fast pose-refinement/bundle-adjust (or a global-SfM pass seeded by the AR poses); if absent or tracking was lost, fall back to full GLOMAP/COLMAP.

**On-device depth.** ARCore Depth API (depth-from-motion, works without ToF but uses it when present) and the Raw Depth API (higher-accuracy sparse depth + per-pixel confidence, ~half the compute of full Depth) [Google](https://developers.google.com/ar/develop/java/depth/raw-depth) can be exported per keyframe. Depth is most accurate 0.5-5 m out. [Google](https://developers.google.com/ar/develop/depth) Reproject high-confidence raw-depth pixels into a metric point cloud to **bootstrap the 3DGS point-cloud initialization**, replacing/augmenting the SfM sparse cloud — this is the CF-3DGS/LighthouseGS pattern and markedly improves geometry and convergence, especially in textureless indoor scenes.

**Capture UX / coverage guidance.** Use ARCore's tracked feature points and anchors to render a real-time coverage map — e.g., a shell/dome around the subject that fills in as viewpoints are covered, arrows guiding the user to gaps, and warnings for too-fast motion (from IMU) or low light. This is the guidance model behind Scaniverse/Polycam capture UIs.

**On-device compression + upload.** Encode keyframes as high-quality JPEG or a lightweight HEVC/H.264 stream (keep quantization high to avoid compression artifacts feeding into training). Bundle frames + poses + depth into a manifest. Use **chunked, resumable upload** (see Layer 2).

---

### Layer 2 — Upload / Ingestion

- **API gateway + auth**: mobile client authenticates (OAuth2/JWT), backend acts as control plane only — it never proxies the file bytes.
- **Resumable upload**: two standard patterns. (1) **tus protocol** — open resumable standard using PATCH + Upload-Offset; [Beefed](https://beefed.ai/en/large-file-uploads-limits-chunking-workarounds) good client/server libraries; used by Supabase Storage for 50 GB files. [Supabase](https://supabase.com/blog/storage-v3-resumable-uploads) (2) **S3/GCS multipart + presigned URLs** — backend issues an UploadId and per-part presigned URLs; client uploads 8-16 MB parts in 4-8 parallel streams with exponential-backoff retry, then calls CompleteMultipartUpload. Presigned URLs should be short-lived; persist part manifest client-side (uploadId, ETags) to resume after crashes. Combine with S3 Transfer Acceleration — the AWS Compute Blog benchmark showed a multipart upload complete in 28 seconds, **61% faster**, with acceleration enabled. Set an AbortIncompleteMultipartUpload lifecycle rule (e.g., 7 days) so orphaned parts don't accrue storage cost. [Medium](https://medium.com/@singh.jyoti768/securely-upload-large-files-to-s3-using-aws-multipart-uploads-combined-with-presigned-url-5677400999db)
- **Object storage layout**: separate prefixes/buckets by lifecycle — `raw/{captureId}/frames|poses|depth`, `intermediate/{jobId}/sfm|pointcloud`, `artifacts/{sceneId}/ply|spz|sog|tiles`. Apply lifecycle rules: raw frames to cold/Glacier or delete after N days; keep final compressed splats hot.
- **Job submission**: on upload completion, enqueue a job message (SQS/Pub/Sub/Cloud Tasks) with capture metadata; a scheduler/orchestrator picks it up. Store job state in a DB (Postgres/DynamoDB).

---

### Layer 3 — Cloud Processing / Training Pipeline

**Pipeline steps per job:**
1. **Ingest & validate** frames/poses/depth.
2. **SfM / pose handling**: if poses provided → fast bundle-adjust/pose-refine (optionally global SfM seeded by AR poses); else → GLOMAP (default, fast global SfM) or COLMAP (fallback for hard scenes). FastMap is an emerging GPU-accelerated option for large captures.
3. **Point-cloud init**: from SfM sparse points and/or reprojected on-device depth.
4. **Gaussian training**: gsplat/Splatfacto (recommended), INRIA reference (baseline), or Brush (CUDA-free). Default ~30k iterations; a 7k "preview" pass gives a fast low-quality result. MCMC densification (Brush) auto-manages splat count.
5. **Compression + LOD generation** (see Layer 4).
6. **Publish** artifacts + update DB; notify client.

**Reference implementations (2025-2026):**
- **INRIA `graphdeco-inria/gaussian-splatting`** — reference, PyTorch + CUDA, single-GPU, ships SIBR viewer. Baseline quality.
- **gsplat (Nerfstudio)** — CUDA-accelerated, modular, faster and lighter than reference (MipNeRF360 A100: 30k iters 19.39 min / 5.6 GB / 29.00 PSNR vs INRIA 26.19 min / 9.0 GB / 28.95 PSNR). Supports batching over scenes, 3DGUT (distorted cameras), and an experimental fast inference path. **Recommended default trainer.**
- **Brush** — Rust + WebGPU/Burn, runs on NVIDIA/AMD/Intel/Android/browser, [PyShine](https://pyshine.com/Brush-3D-Reconstruction-For-All/) MCMC training; valuable where CUDA lock-in is undesirable or for edge/on-device experiments.
- **SOTA extensions**: Taming-3DGS, Mini-Splatting, Scaffold-GS/Octree-GS (compact/LOD), FastGS (claims SOTA in ~100 s), [GitHub](https://github.com/fastgs/FastGS) 3DGUT (CVPR 2025).

**GPU orchestration & instance choice.** The workload is many short (minutes-long), single-GPU, embarrassingly parallel jobs — favor **queue-based single-GPU workers on spot/preemptible instances**, not multi-GPU clusters.
- **Instance types**: T4 (cheapest, slowest), L4 (best price/perf for training + serving), L40S (48 GB, fastest single-GPU short of datacenter cards), A100/H100 (only worth it for large scenes or latency-critical throughput). A common split: train on A100/H100/L40S, serve rendering on L4/L40S.
- **AWS on-demand (single-GPU shapes)**: g4dn.xlarge (T4) ≈ $0.526/hr; g6.xlarge (L4) ≈ $0.805/hr; g6e.xlarge (L40S) ≈ $1.861/hr. A100/H100 only in 8-GPU instances (p4d ≈ $32.77/hr, p5.48xlarge ≈ $55.04/hr ≈ $6.88/GPU). **GCP**: L4 (g2) ≈ $0.70/GPU-hr; A100 (a2-highgpu-1g) ≈ $3.67/hr; H100 (a3) available as single-GPU. **Spot/preemptible cuts 60-90%** (GCP officially "up to 91%").
- **Serverless GPU**: Modal (A100 80GB ~$5.59/hr, H100 ~$10/hr, [BuildMVPFast](https://www.buildmvpfast.com/blog/scale-to-zero-serverless-gpu-modal-runpod-ai-hosting-2026) sub-second-to-seconds cold start, per-second billing), RunPod (H100 SXM ~$2.69/hr, A100 ~$1.39-1.64/hr, [Deploybase](https://deploybase.ai/articles/modal-vs-runpod-serverless-which-is-cheaper) cheaper but rougher DX), Replicate, Baseten. **Cloud Run GPU Jobs** (L4, GA June 2025, ~$0.84/GPU-hr, sub-5s cold start, scale-to-zero) is an excellent match for bursty minutes-long jobs with minimal infra.
- **Managed batch vs K8s**: **AWS Batch** and **GCP Batch** add no service fee (pay only compute), give queues + Spot retry + instance/image reuse, and are the lowest-ops default. **EKS/GKE + Karpenter** gives more control, GPU time-slicing/MIG to pack multiple short jobs on one GPU, and multi-cloud portability, but per-job node provisioning latency (10-30s+ plus driver init) and Karpenter consolidation disrupting running GPU jobs are real drawbacks for very short jobs — mitigate with a warm node pool. **Vertex AI / SageMaker** custom training add MLOps lifecycle at a premium; overkill for a pure containerized worker.
- **Pipeline orchestration**: **Argo Workflows** (K8s-native DAGs, ideal for containerized ML/batch pipelines) [ZenML](https://www.zenml.io/blog/temporal-alternatives) if you run Kubernetes; **Temporal** for durable, code-first long-running orchestration with retries/human steps; [DevOps AI ToolKit](https://devopsaitoolkit.com/blog/orchestrating-workflows-with-temporal-and-argo/) **Airflow/Dagster** for scheduled data-pipeline style; cloud-native Step Functions as a managed option. For a GPU batch pipeline, Argo (on the GPU cluster) or the managed-batch queue itself is the natural fit.

**Training time & cost.** Reference points: INRIA/gsplat 30k iters ≈ 20-50 min on a single A100 depending on scene (MipNeRF360 ~51 min for ~2.6M Gaussians; Tanks&Temples ~20 min for ~1.8M); [arxiv](https://arxiv.org/pdf/2412.03378) RTX 4090 ~10 min to 7k (preview), ~30-40 min to 30k. [Clore](https://docs.clore.ai/guides/3d-generation/gaussian-splatting) COLMAP preprocessing adds 5-30 min [Clore](https://docs.clore.ai/guides/3d-generation/gaussian-splatting) (GLOMAP much less). A worked example (Spheron): 300-image indoor scene, COLMAP + training on A100 80GB (~$1.10/hr on-demand) ≈ 45 min ≈ **~$0.83/scene**, i.e. ~1,000 scenes/day for under ~$850 GPU time. [Spheron](https://www.spheron.network/blog/deploy-3d-gaussian-splatting-gpu-cloud/) On spot L4/L40S the per-scene cost drops further. **Optimizations**: fewer iterations (7k preview + optional refine), spot instances, on-device-depth init to converge faster, distillation/pruning (LightGaussian 15× reduction, Mini-Splatting), FastGS-class accelerators, and splat compression (below).

---

### Layer 4 — Storage / Asset Management & Compression

**Output formats & compression (2025-2026):**
- **PLY** — universal, uncompressed, 150-400+ MB typical (up to ~1 GB for 4M Gaussians). Master/interchange only.
- **`.splat`** (antimatter15) — simple packed binary, widely supported, lower quality than SPZ.
- **SPZ (Niantic)** — open-source (MIT), described in the `nianticlabs/spz` repo as "about 10x smaller than the PLY equivalent with virtually no perceptible loss in visual quality," preserves full spherical harmonics, [Polyvia3d](https://polyvia3d.com/formats/spz) decompresses <100 ms; [Polyvia3d](https://www.polyvia3d.com/guides/spz-format-deep-dive) Scaniverse's native format. **SPZ 4 (May 2026)** per Niantic Spatial "compresses about 3-5x faster, loads roughly 1.5-2x faster end-to-end, still produces files that are 10x smaller than uncompressed PLYs" (replacing single GZip with six parallel ZSTD streams, one per attribute), removes the 10M-Gaussian cap (now tens of millions), [Radiance Fields](https://radiancefields.com/niantic-spatial-releases-spz-v4.0) and embeds metadata. [Nianticspatial](https://www.nianticspatial.com/blog/spz4) Being proposed to Khronos alongside KHR_gaussian_splatting. [Polyvia3d](https://www.polyvia3d.com/guides/spz-format-deep-dive) **Recommended primary delivery format.**
- **SOG / SOGS (PlayCanvas, from Fraunhofer HHI "Self-Organizing Gaussians", ECCV 2024, led by Wieland Morgenstern)** — lays Gaussian attributes into 2D grids compressed as (lossless WebP) images; [Evergine](https://evergine.com/gaussian-splatting-new-features/) PlayCanvas' own demo shrank a "1GB PLY file containing 4 million Gaussians... to just 55MB!" — a **~20× reduction** with full SH; ~15-20× typical. [PlayCanvas](https://developer.playcanvas.com/user-manual/gaussian-splatting/formats/sog/) Use SplatTransform CLI. Best where maximum compression for web/mobile is the priority.
- **LOD**: generate hierarchical LOD via Octree-GS / Hierarchical-3DGS / LODGE (depth-aware smoothing + importance pruning + spatial chunking with opacity blending at boundaries) [OpenReview](https://openreview.net/forum?id=Iqu63cYI3z) and package as **OGC 3D Tiles** (Cesium supports 3DGS-in-glTF with hierarchical LOD, [Cesium](https://cesium.com/blog/2026/04/27/3d-gaussian-splats-lod/) using SPZ internally). This enables progressive, view-dependent, camera-distance-driven streaming [bert](https://bertt.wordpress.com/2026/05/19/from-phone-to-3d-map-creating-and-visualising-gaussian-splat-3d-tiles/) for large/city-scale scenes.
- **Progressive loading**: for scenes >~2M Gaussians (>50 MB SPZ), split into spatial chunks (octree/grid), load nearest chunk first, stream the rest in distance order. [Polyvia3d](https://polyvia3d.com/formats/spz)

**CDN distribution.** Serve compressed splats/tiles from a CDN with long-lived immutable caching (content-addressed filenames); byte-range support for progressive/tiled loading. Fallback chain: SPZ primary → `.splat` fallback → PLY last resort for viewers lacking SPZ support. [Polyvia3d](https://polyvia3d.com/formats/spz)

---

### Layer 5 — Serving / Viewing on Android

**Rendering options & tradeoffs:**
- **Native Vulkan/OpenGL ES**: best performance/control. Reusable open-source cores: **3DGS.cpp** (cross-platform Vulkan Compute renderer), [GitHub](https://github.com/shg8/3DGS.cpp) **MCGS** (Vulkan compute, runs on Android), [GitHub](https://github.com/MouseChannel/MCGS) and Rust/WebGPU crates (`wgpu-3dgs-viewer`, supports PLY + SPZ). [GitHub](https://github.com/LioQing/wgpu-3dgs-viewer) NVIDIA's `vk_gaussian_splatting` is a reference testbed (desktop). **Recommended for a polished first-party app.**
- **Engine-based (Unity/Unreal)**: **UnityGaussianSplatting** (Aras Pranckevičius) supports Metal/Vulkan and thus Android, but mobile is limited to ~200-500K splats at 30 fps; [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-unity-unreal) ~180-220 MB GPU memory per 1M Gaussians with full SH. [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-unity-unreal) Luma has an Unreal plugin. Good if the app is already engine-based, but heavier.
- **Web-based in a WebView**: **PlayCanvas/SuperSplat** (native SOG support), **mkkellogg GaussianSplats3D** (Three.js, `.ksplat` streaming format; note it's no longer actively developed — its author recommends World Labs' **Spark**), [GitHub](https://github.com/mkkellogg/GaussianSplats3D/blob/main/README.md) **gsplat.js/antimatter15**. Fastest to ship and cross-platform, but WebView adds overhead and memory pressure (a single .splat scene can consume >1 GB RAM in-browser). [GitHub](https://github.com/mkkellogg/GaussianSplats3D/issues/314) Best for embeds/sharing, not the highest-performance path.

**Mobile GPU performance & LOD.** The dominant cost is per-frame back-to-front alpha sorting [ACM Digital Library](https://dl.acm.org/doi/10.1145/3721251.3734056) plus overdraw from overlapping splats. Levers to hit 30-60 fps on phones:
- **Sorting**: GPU radix sort; or **sorting-free / order-independent** approaches (Mobile-GS's depth-aware OIT, Weighted Sum Rendering). Mobile-GS reports being "the first real-time Gaussian Splatting method that can reach 116 FPS rendering speed in the 1600×1063 resolution on the mobile equipped with the Snapdragon 8 Gen 3 GPU," reducing storage to 4.8 MB via SH distillation + pruning + vector quantization. [arXiv](https://arxiv.org/abs/2603.11531)
- **Culling**: frustum + distance culling (Max Distance param), [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-unity-unreal) and LOD chunk loading so only near Gaussians are resident.
- **SH reduction**: drop to SH degree 0 (view-independent color) to halve memory bandwidth [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-unity-unreal) where specular highlights aren't essential.
- **Overdraw reduction**: aggressive alpha clipping, stencil-based layer limits [ACM Digital Library](https://dl.acm.org/doi/10.1145/3721251.3734056) (per the SIGGRAPH mobile-VR optimization course).
Budget mobile scenes to a few hundred K-~1.5M Gaussians resident; use LOD/tiling for anything larger. Existing mobile SDKs are still maturing — Scaniverse/PlayCanvas viewers are the most polished; expect to integrate an open-source Vulkan core and add your own LOD/streaming.

---

### Layer 6 — Cross-Cutting Concerns

**Cost optimization by layer**: capture (compress on device, cull frames to reduce upload + SfM cost); ingestion (multipart + lifecycle rules to abort orphaned parts, cold-tier raw frames); training (spot single-GPU, 7k previews, on-device-depth init, compression); serving (SPZ/SOG + CDN caching + LOD to cut egress and client memory).

**Scalability & multi-tenancy**: stateless GPU workers scaling off queue depth (scale-to-zero when idle); per-tenant storage prefixes and IAM scoping; per-tenant quotas/priority queues; content-addressed assets for dedup. Managed batch or Cloud Run scale naturally with bursty demand.

**Iteration / model-improvement data pipeline**: retain raw captures (with consent) as a training corpus; log per-scene quality metrics (PSNR/SSIM/LPIPS on held-out views) and failure modes (tracking loss, textureless scenes) to a warehouse; A/B new trainers/hyperparameters offline against this corpus before rollout. Reprocessing from retained raw data (as Polycam/Scaniverse allow) lets you re-run improved pipelines on old captures.

**Reference architectures (publicly known):**
- **Niantic Scaniverse** — on-device Gaussian training; Niantic reports it could "fully process splats on a smartphone in about a minute" by late 2023, and Scaniverse 3.0 launched local on-device iOS processing in March 2024, "just seven months after the initial paper came out" (community tests report ~60-90 s, [Radiance Fields](https://radiancefields.com/platforms/scaniverse) ~1.5-3M Gaussians, no cloud upload required). Open-sourced SPZ; newer enterprise Scaniverse adds cloud fusion of multiple scans into a unified model + VPS. [Nianticspatial](https://www.nianticspatial.com/blog/scaniverse) Proof the trainer can run on-device, but cloud is preferred for higher quality/larger scenes.
- **Luma AI** — cloud video/photo → GS, ~20-60 min, [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-tools-comparison) known for strong outdoor/vegetation quality (proprietary pipeline); [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-tools-comparison) web + Unreal integration. [3DGS Viewer](https://www.3dgsviewers.com/learn/guide/luma-ai)
- **Polycam** — cloud pipeline, 15-45 min, [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-tools-comparison) LiDAR-enhanced on supported devices, 15+ export formats incl. PLY; [THE FUTURE 3D](https://www.thefuture3d.com/software/polycam/) Android + iOS + web.
- **Kiri Engine** — mobile + cloud, 30-90 min. [Polyvia3d](https://www.polyvia3d.com/guides/gaussian-splatting-tools-comparison)
- **PlayCanvas/Snap** — SuperSplat editor + SOG compression + web engine; the reference for web serving/compression.
- **Postshot** — desktop + cloud, strong architectural/real-estate.

**Open-source / managed toolbox**: SfM — COLMAP, GLOMAP, FastMap; training — gsplat/Nerfstudio, INRIA, Brush; compression — Niantic SPZ, PlayCanvas SOGS/SplatTransform; viewers — 3DGS.cpp, wgpu-3dgs-viewer, UnityGaussianSplatting, PlayCanvas/SuperSplat, Spark; LOD/geo — Cesium 3D Tiles; upload — tus, Uppy, S3/GCS multipart; orchestration — Argo, Temporal, Airflow/Dagster, AWS/GCP Batch, Cloud Run GPU, Modal/RunPod.

## Recommendations

**Stage 1 — MVP (validate quality & UX):**
- Android capture with ARCore poses + sharpness-based keyframe selection + AE/AWB lock; ship frames + transforms.json.
- Resumable upload via tus or S3 multipart to an object-storage `raw/` bucket; job enqueued on completion.
- Single containerized worker: GLOMAP pose-refine (fallback COLMAP) → gsplat/Splatfacto 30k → export PLY → convert to SPZ. Run on **Cloud Run GPU Jobs (L4)** or **AWS/GCP Batch** on spot L4/L40S.
- Serve SPZ over CDN; view in a WebView (PlayCanvas/Spark) to ship fastest.
- **Benchmark to hit**: end-to-end capture→viewable in <10 min for a room-scale scene; per-scene GPU cost <$1.

**Stage 2 — Scale & polish:**
- Add on-device Raw Depth point-cloud init and coverage-guidance UX.
- Move to a native Vulkan renderer (3DGS.cpp / wgpu core) with LOD/culling for higher fps and lower memory.
- Add SOG + 3D Tiles LOD for large scenes; progressive chunked streaming.
- Autoscaling queue workers (scale-to-zero) with spot + on-demand fallback; per-tenant quotas.

**Stage 3 — Optimize & differentiate:**
- 7k preview + async 30k refine for instant feedback.
- Distillation/pruning (LightGaussian/Mini-Splatting) and Mobile-GS-style sorting-free rendering for weak devices.
- Quality-metrics data pipeline + offline A/B of trainers; reprocess old captures with improved models.

**Thresholds that change the plan:**
- If scenes routinely exceed ~2-3M Gaussians or go city-scale → mandatory 3D Tiles LOD + chunked streaming; consider Cesium.
- If per-scene latency must be seconds not minutes, or you need offline capture → evaluate on-device training (Brush/Scaniverse-style) instead of cloud.
- If GPU utilization is consistently high (>50%) → shift from serverless per-second (Modal) to reserved/spot dedicated instances or K8s + Karpenter with GPU time-slicing.

## Caveats
- **Forward-looking / vendor claims**: SPZ 4 speed/size figures, FastGS "~100 s", Mobile-GS fps, and "20×" SOG numbers come from the projects' own announcements/papers and specific benchmark scenes — real-world results vary with scene complexity and device. Treat as directional.
- **Pricing volatility**: GPU prices are moving fast (AWS cut P5 44% in June 2025; GCP cut A3 through 2025). GCP per-GPU H100 figures conflict across sources ($3 vs $11/GPU) due to price cuts and whole-VM vs per-GPU accounting — verify against live pricing before committing.
- **Company stacks are partially inferred**: Luma/Polycam/Kiri internal pipelines are not fully public; their processing times, quality characteristics, and "cloud pipeline" descriptions come from reviews and their own marketing, not architecture disclosures.
- **Mobile rendering limits are real**: million-Gaussian smooth rendering on mid-range phones is still emerging (some sources project 5M by 2027); [Swyvl](https://swyvl.io/blog/best-gaussian-splat-viewers/) plan LOD/compression accordingly rather than assuming desktop-class splat counts.
- **ARCore device coverage**: Depth API and higher-end capture features are limited to a subset of ARCore-certified Android devices; [GitHub](https://github.com/googlesamples/arcore-depth-lab/blob/master/README.md) you must gracefully degrade (e.g., cloud SfM when poses/depth are poor or unavailable).