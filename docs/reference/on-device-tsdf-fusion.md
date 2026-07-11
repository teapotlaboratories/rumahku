# TSDF Fusion + Mobile3DRecon-Style Monocular Reconstruction for Real-Time On-Device Capture Feedback on Android

## TL;DR
- For a live *coverage/guidance* overlay (not the final model), the right default is **ARCore Depth API → confidence-weighted TSDF voxel-hashing volume → incremental Marching Cubes → semi-transparent mesh overlay with depth occlusion**. ARCore already gives you metric VIO poses and a depth-from-motion map, so you get most of Mobile3DRecon's value without shipping a neural MVS network.
- A **Mobile3DRecon-style multi-view SGM + CNN depth-refinement stage** is worth adding only when ARCore depth is too sparse/noisy for your scenes (textureless walls, outdoors, thin structures) — Mobile3DRecon ran its *full* pipeline at 57.21 ms/keyframe (Snapdragon 845) to 101.59 ms/keyframe (Snapdragon 710) on 2018-era phones, entirely on CPU, so it is feasible but adds real engineering and thermal cost.
- **NeuralRecon-style learned TSDF** produces the cleanest coherent live mesh but is currently a desktop-GPU technique (benchmarked on RTX-class GPUs, requiring 9 keyframes per fragment, not phone-real-time); treat it as aspirational/cloud-side, not the on-device overlay in 2026.

## Key Findings
- Mobile3DRecon (SenseTime + Zhejiang University; ISMAR 2020 Best Paper; IEEE TVCG 26(12):3446–3456) is a two-stage depth-then-fuse pipeline: keyframe-based VIO poses → multi-view semi-global matching (SGM) depth → confidence filtering → a two-stage CNN depth-refinement → TSDF voxel fusion → incremental Marching Cubes. It explicitly states any keyframe VIO/SLAM including ARCore can supply poses.
- The pipeline is CPU-only on the back end (NEON SIMD), leaving the GPU for rendering. Per-keyframe total was 101.59 ms on OPPO R17 Pro (Snapdragon 710) and 57.21 ms on Mi8 (Snapdragon 845), against a keyframe rate of ~5 keyframes/s and front-end tracking >25 FPS. Reported accuracy vs ToF ground truth was centimeter-level (per-sequence depth RMSE 5.69–10.43 cm, MAE 3.01–6.10 cm; mesh RMSE 4.17–5.58 cm). The TSDF voxel size used as an example was 0.06 m.
- The core architectural insight for your app: TSDF fusion needs *metric depth + metric pose*. ARCore provides both (poses are metric from VIO; depth-from-motion is metric in millimeters). This means you can feed ARCore depth straight into a metric TSDF with no scale estimation — the single biggest simplification versus a pure-monocular system.
- ARCore's Raw Depth API returns a 16-bit depth image (millimeters; ~160×120 typical, up to 640×480 on some devices) plus a per-pixel confidence image in Y8 format (0 = least confidence, 255 = most). [Google](https://developers.google.com/ar/develop/java/depth/raw-depth) That confidence channel maps directly onto the TSDF running-average weight — a clean, principled way to down-weight noisy depth during integration.
- Mature, portable TSDF back ends exist: CHISEL/OpenChisel (CPU-only, spatially-hashed, designed for exactly this Tango/mobile use case and does incremental Marching Cubes), Voxblox, VDBFusion, InfiniTAM, and Open3D's Voxel Block Grid. Voxel/block hashing (Nießner et al., ACM TOG 2013) is the key data structure for bounded memory.
- Newer neural methods (NeuralRecon 2021, SimpleRecon 2022, VisFusion 2023, SimpleMapping 2023) improve mesh coherence/quality but target desktop GPUs; SimpleRecon's 2D-CNN "no 3D convolution" design is the most mobile-portable of these and its depth output can feed a classical TSDF.

## Details

### 1. Mobile3DRecon — what it is and how it works

**Provenance.** "Mobile3DRecon: Real-time Monocular 3D Reconstruction on a Mobile Phone," Xingbin Yang, Liyang Zhou, Hanqing Jiang, Zhongliang Tang, Yuanbo Wang, Hujun Bao, Guofeng Zhang. SenseTime Research + State Key Lab of CAD&CG, Zhejiang University. IEEE TVCG 26(12):3446–3456, 2020; presented at ISMAR 2020, where it won Best Paper. Project page: zju3dv.github.io/mobile3drecon.

**High-level design.** Unlike RGB-D systems (KinectFusion, BundleFusion) that require a depth camera, or prior monocular systems that only produce point clouds online and meshes offline, Mobile3DRecon produces an **online incremental surface mesh** from a single RGB camera on a mid-range phone. [IEEE Xplore](https://ieeexplore.ieee.org/document/9201064/) It is explicitly a "front end = 6DoF tracking, back end = mesh generation" split [Zju3dv](https://zju3dv.github.io/mobile3drecon/) — precisely the split your app wants (capture front end, heavy reconstruction elsewhere).

**Pipeline stages:**

1. **Pose tracking (front end).** A keyframe-based visual-inertial SLAM (they use SenseTime's SenseAR SLAM) tracks 6DoF poses in real time and maintains a keyframe pool with global bundle adjustment refining keyframe poses as feedback. The paper explicitly states: *"any other keyframe-based VIO or SLAM system such as ARCore [Google] can be used at this stage."* [zju](http://www.cad.zju.edu.cn/home/gfzhang/papers/Mobile3DRecon/mobile-3d-recon.pdf) This is the hook for your app — **ARCore replaces SenseAR SLAM wholesale.**

2. **Reference keyframe selection.** For each incoming keyframe it selects neighboring keyframes that (a) provide enough parallax/baseline for stable triangulation and (b) still overlap enough for matching. [zju](http://www.cad.zju.edu.cn/home/gfzhang/papers/Mobile3DRecon/mobile-3d-recon.pdf) It uses a baseline score (Gaussian centered at bm = 0.6 m, δ = 0.2) and a viewing-angle score to pick reference frames. [zju](http://www.cad.zju.edu.cn/home/gfzhang/papers/Mobile3DRecon/mobile-3d-recon.pdf)

3. **Multi-view depth via SGM (not an end-to-end MVSNet).** This is the paper's key contrarian choice. Rather than an end-to-end deep MVS network (DPSNet, MVDepthNet), which they show generalizes poorly under real SLAM pose error (epipolar errors >2 px are common) and in textureless regions, they run a **generalized multi-view semi-global matching** [Zju3dv](https://zju3dv.github.io/mobile3drecon/) directly in a uniformly-sampled *inverse-depth* space (a cost volume of size W×H×L). They compute patch similarity with a **weighted Census Transform**, aggregate costs across multiple neighboring keyframes (weighted by each frame's score), apply Winner-Take-All, then sub-pixel refine by parabola fitting. NEON SIMD is used heavily — NEON gave ~2× speedup on cost-volume computation and ~8× on aggregation. [zju](http://www.cad.zju.edu.cn/home/gfzhang/papers/Mobile3DRecon/mobile-3d-recon.pdf) Then a **confidence-based filter** removes unreliable depths from pose error/texturelessness.

4. **CNN depth refinement (denoising).** The residual noise after SGM + filtering is cleaned by a **two-stage CNN**: (a) an image-guided sub-network that fuses the filtered depth with the grayscale image to produce a coarse refinement, then (b) a **residual U-Net** that further refines to the final denoised depth. The paper shows this "SGM + learned refinement" beats an end-to-end learned MVS in generalization [zju](http://www.cad.zju.edu.cn/home/gfzhang/papers/Mobile3DRecon/mobile-3d-recon.pdf) — an important lesson: use classical geometry for the raw estimate and use the network only to denoise. (This is conceptually the same idea later formalized by RoutedFusion/DFusion as learned depth-map fusion/denoising.)

5. **TSDF fusion + incremental Marching Cubes.** Each refined keyframe depth map is integrated into a **scalable TSDF voxel volume** (they avoid voxel-hashing conflicts). Crucially they maintain a **status flag per voxel** (newly-added / updated / unchanged) and run **incremental Marching Cubes** that only re-extracts/updates triangles from newly-added and updated cubes each keyframe — this is what keeps meshing real-time and topology concise, and is the direct improvement over CHISEL's chunk-based re-meshing. They also do a **dynamic-object handling** step: existing voxels are projected into the current frame for depth-visibility checking to carve away moved objects.

**Performance (from the paper, Table 2 — confirmed via primary source):**

| Substep | OPPO R17 Pro (SD710) | Mi8 (SD845) |
|---|---|---|
| Cost-volume computation | 16.75 ms | 11.92 ms |
| Cost-volume aggregation | 28.55 ms | 17.68 ms |
| Confidence filtering | 2.26 ms | 1.1 ms |
| CNN depth refinement | 22.9 ms | 7.62 ms |
| **Depth subtotal** | **70.46 ms** | **38.32 ms** |
| Incremental meshing | 31.13 ms | 18.89 ms |
| **Total / keyframe** | **101.59 ms** | **57.21 ms** |

Keyframe rate ~5 keyframes/s; front-end tracking >25 FPS; paper caps back-end at "no more than 125 ms/keyframe." [zju](http://www.cad.zju.edu.cn/home/gfzhang/papers/Mobile3DRecon/mobile-3d-recon.pdf) Depth accuracy vs ToF ground truth was centimeter-level (per-sequence depth RMSE 5.69–10.43 cm, MAE 3.01–6.10 cm; mesh RMSE 4.17–5.58 cm). Devices targeted were explicitly "mid-range" 2018-era phones. **The entire back end (depth + meshing) runs on CPU** (a single back-end thread for depth, [zju](http://www.cad.zju.edu.cn/home/gfzhang/papers/Mobile3DRecon/mobile-3d-recon.pdf) a single thread for meshing), deliberately leaving the GPU free for rendering. The paper does not name the on-device neural inference engine used for the refinement CNN, nor an explicit depth-map pixel resolution or the numeric value of L; the TSDF voxel size is given as an example value of 0.06 m.

**Limitations reported:** dependent on VIO pose quality (mitigated with multi-view SGM robustness); textureless/low-parallax regions remain hard; dynamic scenes need the visibility-carving step; reconstruction is centimeter-scale, not millimeter — fine for AR occlusion/coverage, not for a final deliverable.

### 2. TSDF fusion fundamentals and mobile variants

**TSDF basics.** A Truncated Signed Distance Function stores, per voxel, the signed distance to the nearest surface (negative behind, positive in front), truncated to ±τ near the surface, plus a running-average weight (and optionally color). Integration (Curless & Levoy 1996): for each depth pixel, project the voxel into the depth image, compute sdf = depth(pixel) − distance(voxel to camera), truncate, and update via weighted running average: D ← (W·D + w·d)/(W+w), W ← W+w. The surface is the zero-crossing. **Marching Cubes** extracts a triangle mesh from the zero level set; [Open3D](https://www.open3d.org/html/tutorial/t_reconstruction_system/integration.html) **incremental Marching Cubes** only re-triangulates voxels whose TSDF changed this frame.

**Memory management — the central problem.** A dense uniform grid is O(n³) and blows up. The solutions:
- **Voxel/spatial hashing (Nießner et al., ACM TOG 2013):** allocate small voxel *blocks* (e.g. 8³) only where surfaces exist, addressed by a hash of block coordinates. This is the foundational technique; BundleFusion, InfiniTAM, and Open3D's Voxel Block Grid all use it. [arxiv](https://arxiv.org/pdf/1709.03763) Enables whole-room/house reconstruction in bounded memory and GPU streaming.
- **Octrees / OpenVDB:** hierarchical sparse storage (VDBFusion builds TSDF fusion on OpenVDB). More general but higher traversal cost and weaker GPU behavior for real-time integration. [arXiv](https://arxiv.org/html/2511.21459)

**Named systems and their portability to Android:**
- **KinectFusion (ISMAR 2011):** original dense GPU TSDF; fixed-volume, doesn't scale; needs RGB-D. Reference only.
- **Voxel Hashing / BundleFusion (Nießner 2013 / Dai 2017):** GPU, scalable, de-integration for online correction. Great algorithms; GPU-CUDA-centric, so needs porting to Vulkan/GL compute for Android.
- **CHISEL / OpenChisel (Klingensmith, Dryanovski, Srinivasa & Xiao, RSS 2015, DOI 10.15607/RSS.2015.XI.040):** *the* mobile-relevant reference — built for Google Tango, it performs "real-time house-scale (300 square meter or more) dense 3D reconstruction onboard a Google Tango mobile device," [Robotics: Science and Systems](https://www.roboticsproceedings.org/rss11/p40.html) and per the paper is "able to reconstruct and render very large scenes at a resolution of 2-3 cm in real time on a mobile device without the use of GPU computing," [Carnegie Mellon University](https://www.ri.cmu.edu/publications/chisel-real-time-large-scale-3d-reconstruction-onboard-a-mobile-device/) [Robotics: Science and Systems](https://www.roboticsproceedings.org/rss11/p40.html) using a dynamic spatially-hashed SDF + space carving + incremental polygonal meshing for only the parts that need rendering. OpenChisel (github.com/personalrobotics/OpenChisel) is the open C++ re-write; it takes external poses (perfect — ARCore supplies them) and does no pose estimation itself. **This is the single best starting point to port/adapt.**
- **InfiniTAM (Oxford):** highly efficient voxel-hashing, has ARM/mobile builds; CPU and GPU paths.
- **Voxblox (Oleynikova et al., IROS 2017):** block-hashed TSDF+ESDF for MAV planning, CPU, ROS-centric; good incremental meshing, easy to read.
- **VDBFusion (Vizzo et al. 2022):** OpenVDB-based, simple API, CPU; more batch-oriented.
- **Open3D Voxel Block Grid / TSDF:** clean reference implementation with a frustum hash map + block hash map for activation, and `extract_triangle_mesh` for Marching Cubes; ~25 Hz prototyping to ~100 Hz (GTX 1070) on desktop [Open3D](https://www.open3d.org/docs/0.15.1/tutorial/t_reconstruction_system/integration.html) — useful as an algorithmic reference, not a drop-in mobile lib.

### 3. Fusing TSDF with a Mobile3DRecon-style approach (your core idea)

**The key realization: ARCore already does the hard front-end half of Mobile3DRecon.** Mobile3DRecon's two expensive novelties were (a) getting robust metric depth from a monocular camera and (b) getting metric 6DoF poses. ARCore gives you (b) for free and gives you a serviceable (a) via the Depth API. So the design question reduces to: **is ARCore's depth-from-motion good enough to feed the TSDF directly, or do you need to compute your own MVS depth?**

**Where ARCore helps vs. where an MVS network helps:**
- **ARCore Depth API** uses a depth-from-motion algorithm (multi-frame stereo + selective ML), integrating hardware ToF if present. [Google](https://developers.google.com/ar/develop/depth) It outputs metric depth every frame, plus a **confidence image**. Per Google's documentation, "the algorithm can get robust, accurate depth estimates from 0 to 65 meters away," [Google](https://developers.google.com/ar/develop/depth) and "the most accurate results come when the device is half a meter to about five meters away from the real-world scene"; [Google](https://developers.google.com/ar/develop/depth) error grows quadratically with distance, and textureless surfaces (white walls) get imprecise depth [Google](https://developers.google.com/ar/develop/depth) — exactly the failure mode Mobile3DRecon's multi-view SGM + refinement was built to fight. Google's docs contrast the two depth modes directly: "Raw depth maps provide depth estimates with higher accuracy, but raw depth images might not include depth estimates for all pixels... the smooth depth maps provide estimated depth for every pixel, but per-pixel depth data might be less accurate due to smoothing and interpolation." [Android Developers](https://developer.android.com/develop/xr/jetpack-xr-sdk/arcore/depth) [Google](https://developers.google.com/ar/develop/java/depth/raw-depth) Raw Depth's compute cost is "about half of the compute cost for the full Depth API." [Google](https://developers.google.com/ar/develop/java/depth/raw-depth)
- **A Mobile3DRecon-style MVS stage** buys you: higher-resolution, sharper-edged depth; better textureless handling (via multi-view aggregation and a learned prior); and control over the confidence model. But it costs a cost-volume computation + a CNN inference per keyframe.

**Recommendation on the depth source:** For a *coverage overlay*, start with **ARCore depth fed directly into the TSDF**, using the ARCore **confidence image as the integration weight**. The confidence image is Y8 format (0 = least, 255 = most confident); [Google](https://developers.google.com/ar/develop/java/depth/raw-depth) dropping pixels below a threshold of half confidence (128) is Google's recommended practice. Only add an MVS/refinement stage if field testing shows the overlay is too holey/noisy in your target scenes.

**Depth denoising/refinement before fusion.** Whether depth comes from ARCore or your own MVS, clean it before integrating — Mobile3DRecon's central lesson. Cheap-to-expensive options:
- Confidence thresholding + temporal consistency check (reject pixels whose depth disagrees with the reprojected previous TSDF — this is Mobile3DRecon's visibility check and also carves dynamics).
- Edge-aware bilateral / joint-bilateral filter guided by the RGB image (mirrors Mobile3DRecon's image-guided sub-network at a fraction of the cost).
- A small learned refinement U-Net (the Mobile3DRecon approach) or a learned fusion (RoutedFusion/DFusion) if you go neural.

**Concrete data flow (recommended):**
```
ARCore frame
  ├─ camera pose (metric, world frame)      ┐
  ├─ camera intrinsics                      │
  ├─ RGB image                              │
  └─ depth image (mm) + confidence (0–255)  │
        │                                    │
   [optional MVS depth net / refinement]     │  keyframe gate (parallax + overlap)
        │                                    │
   depth denoise / bilateral / conf-threshold│
        │                                    │
   TSDF integration (voxel-block hashing) ◄──┘  weight = f(confidence)
        │
   incremental Marching Cubes (only changed blocks)
        │
   semi-transparent mesh overlay, rendered with ARCore depth occlusion + a "coverage heat" shader
```

**Scale and weighting.** Because ARCore poses are metric and its depth is in millimeters, **the TSDF is metric with zero scale estimation** — you set voxel size in real units (2–4 cm is a good coverage-overlay resolution; go coarser only if you need speed, versus the 0.06 m Mobile3DRecon example). Use ARCore confidence to set per-pixel integration weight; optionally decay old voxel weights so the overlay reflects recent coverage.

### 4. Implementation on Android

**Running a depth/MVS network on-device (2024–2026 tooling):**
- **LiteRT (formerly TensorFlow Lite)** with the **GPU delegate** (OpenCL/OpenGL ES) or **NNAPI** delegate. Caveat well-documented in the literature: NNAPI does not always accelerate — for many networks it is slower or unsupported op-by-op, falling back to CPU with copy overhead. Benchmark per device.
- **MNN (Alibaba)** and **ncnn (Tencent)** — both purpose-built mobile inference engines with custom GEMM and good ARM CPU + GPU (Vulkan) paths; frequently faster and more predictable than TFLite for custom vision nets, and easier to ship without NNAPI-driver fragmentation. (Mobile3DRecon was from SenseTime, whose in-house engine family is PPL/OpenPPL/`ppl.nn`; the paper itself does not name which engine ran the refinement CNN.)
- Use **FP16** (GPU delegate) or **INT8 quantization** for the refinement net; a SimpleRecon-style 2D-CNN is far more quantization-friendly than any 3D-convolution network.
- Tooling to benchmark: the AI-Benchmark app (loads custom TFLite, tests NNAPI/GPU/Hexagon/vendor delegates).

**TSDF + incremental Marching Cubes on mobile:**
- **CPU path (recommended first):** port OpenChisel or a Voxblox-style block-hashed TSDF. CHISEL's whole premise is that this runs in real time on a phone CPU with no GPU — proven on Tango. Keep the GPU for camera + mesh rendering.
- **GPU compute path (for higher res / frame rate):** implement integration and Marching Cubes as **Vulkan compute** or **OpenGL ES 3.1 compute shaders**. This is more work and contends with ARCore's own GPU use for the camera background; profile carefully. Vulkan is preferred over GL ES compute in 2026 for predictable scheduling.
- Marching Cubes: maintain per-block dirty flags, run MC only on dirty blocks, and stream vertex buffers incrementally to the renderer.

**Threading / pipeline design.** Mirror Mobile3DRecon's front/back split so you never stall ARCore's frame loop:
- **Thread A (ARCore / render, 30–60 FPS):** `session.update()`, acquire pose/RGB/depth/confidence, render camera + current overlay mesh with depth occlusion. Never blocks on reconstruction.
- **Thread B (keyframe depth):** gated at ~5 keyframes/s (Mobile3DRecon's rate). Runs optional MVS/refinement + denoise. Producer/consumer queue of at most 1–2 keyframes; drop if backed up.
- **Thread C (TSDF integrate + incremental MC):** consumes cleaned depth+pose, updates hashed volume, re-meshes dirty blocks, publishes an immutable mesh snapshot (double/triple-buffered) for Thread A.
- Use lock-free/triple-buffered handoff for the mesh so rendering reads a stable copy while C writes the next.

**Newer (2021–2026) approaches — viability for an on-device overlay:**
- **NeuralRecon (CVPR 2021 Oral, ZJU+SenseTime):** directly regresses a **sparse TSDF volume per video fragment** (9 keyframes per fragment) with a 3D sparse CNN + **GRU-based learned fusion** across fragments — coherent, no per-frame depth fusion artifacts. Reported at ~41 keyframes/s but **on an RTX-class desktop GPU**; the 3D sparse convolutions are not mobile-real-time today. Best as a *cloud-side* live-preview or future on-device target.
- **SimpleRecon (ECCV 2022, Niantic):** "3D reconstruction without 3D convolutions" — a **2D CNN** with a plane-sweep cost volume enriched by cheap keyframe/geometric **metadata**, then off-the-shelf TSDF fusion. State-of-the-art depth, low memory, online. **The most mobile-portable modern depth net**: its 2D-only design is the realistic path if you want to replace Mobile3DRecon's SGM+refinement with a single modern network feeding your TSDF.
- **VisFusion (CVPR 2023):** visibility-aware online volumetric reconstruction; improves on NeuralRecon's occlusion handling; still 3D-volume/GPU.
- **SimpleMapping (ISMAR 2023, TUM):** VIO → noisy sparse points → single-view depth completion → **SPA-MVSNet** (SimpleRecon-derived) → TSDF fusion; real-time incremental mesh but benchmarked on desktop GPU — the paper reports "an approximate rate of 20 frames per second when executed on a desktop equipped with an NVIDIA GeForce RTX 3070 graphics card with 8GB of VRAM and an Intel i5-10600 CPU." [arxiv](https://arxiv.org/pdf/2306.08648) Architecturally it is "Mobile3DRecon, modernized" and a great blueprint if you build the MVS route.
- **On-device 3DGS / Gaussian-SLAM (2024–2026):** RTG-SLAM, GS-SLAM, Photo-SLAM, and mobile splat renderers (Mobile-GS, Voyager) show splats *rendering* on phones, and 3DGS SLAM running on desktop GPUs. On-device *training* of splats in real time is not yet a practical coverage-overlay technique in 2026 — and it is redundant with your cloud stage anyway. Keep GS in the cloud.

### 5. Practical recommendation

**Recommended architecture for THIS use case (live coverage overlay; final GS/photogrammetry in cloud):**

**Stage A — ship first (simplest, robust): ARCore depth → confidence-weighted TSDF → incremental MC.**
- Enable ARCore Depth (full Depth API for dense coverage; Raw Depth if you want accuracy + explicit confidence and can tolerate sparsity — remember Raw Depth costs ~half the compute of full Depth).
- Port OpenChisel or a Voxblox-style block-hashed TSDF on the CPU; voxel size 2–4 cm.
- Weight integration by ARCore confidence; threshold at 128; reproject-and-check to reject fliers.
- Incremental Marching Cubes on dirty blocks; render as a translucent mesh with ARCore depth occlusion and a color ramp that encodes "already captured" vs. "thin/low-confidence" coverage.
- This alone delivers the Polycam-style live coverage guidance and reuses ARCore's metric poses/depth. Lowest battery/thermal load; no ML.

**Stage B — add only if Stage A's overlay is too noisy/holey in your scenes: a depth-refinement network.**
- Cheapest: RGB-guided bilateral/temporal filtering (no ML).
- If needed, a small learned refinement U-Net (Mobile3DRecon style) or a SimpleRecon-style 2D MVS net run via MNN/ncnn (GPU or INT8), gated at ~5 keyframes/s. This is the true "fuse TSDF with Mobile3DRecon" configuration.

**Stage C — do NOT put on device: NeuralRecon-style learned-TSDF and Gaussian-splat training.** Keep these in the cloud alongside your final reconstruction.

**When the MVS-network complexity is worth it.** Add the Mobile3DRecon-style MVS/refinement stage when: (1) target scenes are large/outdoor/textureless where depth-from-motion degrades; (2) your capture UX can't rely on the user generating enough parallax for ARCore; (3) you need sharper mesh edges for convincing occlusion; or (4) you must support devices where ARCore Depth quality is poor. If your scenes are room-scale, well-textured, and users naturally move the phone, **ARCore-depth-only TSDF is sufficient** and the network is not worth the battery/thermal/maintenance cost.

**Performance & fallback strategies.**
- Gate reconstruction at a keyframe rate (≈5/s), never per frame; always prioritize ARCore's 30–60 FPS loop.
- Cap the active TSDF region (stream far blocks out, à la voxel hashing) to bound memory and heat.
- Provide graceful fallback where Depth API is unsupported: fall back to sparse ARCore feature points / plane detection for a coarser coverage hint, or disable the mesh overlay and show a 2D coverage map.
- Thermal governor: monitor frame time / device temperature and dynamically drop meshing resolution or keyframe rate before ARCore tracking is affected.
- Because the final model is built in the cloud, the on-device mesh only needs to be *good enough to guide the user* — bias every trade-off toward frame-rate stability and battery, not mesh fidelity.

## Recommendations
1. **Build Stage A first** (ARCore depth → OpenChisel-style hashed TSDF → incremental MC overlay). Benchmark coverage quality on 8–10 representative scenes. Threshold to change course: if >~20–30% of surfaces you care about are missing/holey or the mesh flickers badly, proceed to Stage B.
2. **Adopt OpenChisel/Voxblox as the TSDF back end** (CPU, external poses from ARCore) rather than writing a volume from scratch; only move integration/MC to Vulkan compute if CPU meshing can't keep the ~5 keyframes/s budget on your min-spec device.
3. **Use ARCore confidence as the TSDF weight and reproject-check to reject dynamics** — this reproduces Mobile3DRecon's robustness cheaply.
4. **If you add a network, prefer a SimpleRecon-style 2D-CNN or a small Mobile3DRecon-style refinement U-Net, deployed via MNN or ncnn** (GPU delegate or INT8), not a 3D-convolution / NeuralRecon model. Benchmark NNAPI vs GPU delegate per SoC; don't assume NNAPI is faster.
5. **Keep NeuralRecon and Gaussian-splat generation in the cloud.** On device, they add cost without helping the guidance overlay.
6. **Engineer the front/back thread split up front** so reconstruction can never stall ARCore's frame loop; use triple-buffered mesh handoff.

## Caveats
- Mobile3DRecon's numbers are from 2018-era mid-range phones (SD710/845); 2024–2026 SoCs are far faster, so its 57.21–101.59 ms/keyframe CPU budget is comfortably beatable today — but its accuracy (centimeter-level) is a realistic ceiling for this class of method, not millimeter photogrammetry.
- ARCore Depth API is device-gated (not all ARCore devices support it) and depth quality varies widely by device and by whether a ToF sensor is present; you must handle unsupported/low-quality cases.
- The Mobile3DRecon paper does not publish a public code repo, an explicit depth-map resolution, the inverse-depth sample count L, or the name of its on-device inference engine — those must be reconstructed/chosen by you. Timing, voxel-size example (0.06 m), keyframe rate (~5/s), and accuracy figures are from the paper.
- "Real-time" for NeuralRecon/SimpleRecon/VisFusion/SimpleMapping means real-time on a desktop GPU in the source papers; none is demonstrated as a phone-real-time on-device system, so treating them as on-device is speculative.
- The on-device mesh is for guidance only; final geometry/appearance comes from the cloud Gaussian-splatting/photogrammetry pass, so do not over-invest in on-device fidelity.