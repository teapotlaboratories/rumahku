# Evaluating 3D Gaussian Splatting Quality: A Practical QA Guide for a Real-Estate 3D Mapping Platform

## TL;DR
- **A "good" 3DGS reconstruction for real estate must clear three independent bars, not one**: photorealistic rendering (target held-out PSNR ≥ ~28 dB indoors, SSIM ≥ 0.9, LPIPS ≤ ~0.2), geometric fidelity of the extracted mesh/point cloud (use a surface-accurate variant like 2DGS/GOF/PGSR, not raw 3DGS), and — most critically for buyers — **metric/scale accuracy of dimensions** (target ≤1–3 cm or ≤2% error per wall against laser ground truth). Raw 3DGS optimizes only appearance and is not surface- or scale-accurate by default.
- **In production you almost never have ground truth**, so gate delivery with a layered automated system: SfM health (registration rate, reprojection error, track length), training health (loss, Gaussian count, opacity/floater checks), no-reference image quality on rendered views (pyiqa: MUSIQ, TOPIQ, BRISQUE, NIQE, CLIP-IQA), and cross-validation by holding out ~1 in 8 views to compute full-reference PSNR/SSIM/LPIPS. Periodically validate metric accuracy against real tape/laser measurements.
- **Combine the signals into a staged go/no-go gate** (reject → review → accept), with scale/metric accuracy as a hard gate because a dimensionally wrong model is worse than a blurry one for a buyer making purchase decisions.

## Key Findings

**1. The rendering trio (PSNR/SSIM/LPIPS) is necessary but insufficient.** These are full-reference metrics computed on held-out test views (standard convention: every 8th image held out, Mip-NeRF 360 protocol). The original 3DGS paper (Kerbl, Kopanas, Leimkühler & Drettakis, "3D Gaussian Splatting for Real-Time Radiance Field Rendering," ACM ToG Vol. 42 No. 4, Aug 2023) reports 30K-iteration averages of PSNR 27.21 / SSIM 0.815 / LPIPS 0.214 on Mip-NeRF 360, 23.14 / 0.841 / 0.183 on Tanks & Temples (2 scenes: Truck, Train), and 29.41 / 0.903 / 0.243 on Deep Blending (2 scenes: DrJohnson, Playroom). Indoor scenes score higher than outdoor: Mip-NeRF360 indoor rooms (room, counter, kitchen, bonsai) reach ~29–32 dB while outdoor scenes sit at ~22–27 dB. Crucially, these metrics only measure quality at camera poses near the training trajectory and do not detect floaters or artifacts seen from off-path novel viewpoints, and they are sensitive to exposure/color shifts.

**2. Raw 3DGS geometry is poor; you must extract meshes with surface-accurate variants.** On the DTU object benchmark, mean Chamfer distance (mm) per the PGSR paper (TVCG 2024, Table II) is: raw 3DGS ~1.96, SuGaR 1.33, 2DGS 0.80, GOF 0.74, and PGSR 0.53 (reduced to 0.47 with the tuned official code, zju3dv/PGSR "Code_V1.0", 2024.07.18). On Tanks & Temples (F-score, official per-scene threshold, 6-scene subset), the PGSR paper reports SuGaR 0.19, 2DGS 0.32, GOF 0.46, and PGSR 0.52.

**3. Phone RGB-only reconstruction of rooms is centimeter-scale at best.** Published smartphone photogrammetry of interiors is typically 3–5 cm per measurement under practical conditions and can degrade to 10–20 cm (2σ) with low-texture walls; LiDAR-equipped devices reach ~1–2 cm. A 2024 study (Abbas et al., "Indoor mapping accuracy comparison between the apple devices' LiDAR sensor and terrestrial laser Scanner," Applied Geomatics, 10.1080/16874048.2024.2408839) found iPad Pro 2021 static LiDAR vs. a Trimble TX8 TLS achieved length/width/height within 1–2 cm, while dynamic acquisition gave an RMS of 16.17 cm and errors degrading to ±30 cm beyond 4 m; the widely cited Spreafico et al. (2021) study found iPad Pro LiDAR RMS deviations "on the order of 2 centimeters at close range." RGB-only SfM is scale-ambiguous and must be metrically anchored (ARCore/IMU poses, a known reference object, or a fiducial) before dimensions mean anything.

**4. The AEC industry has a formal accuracy standard you should map to.** The USIBD Level of Accuracy (LOA) Specification (Guide C120; derived from the DIN 18710 survey standard; Version 3.1 released 2025) defines tolerances at 95% confidence: LOA10 = user-defined to 5 cm; **LOA20 = 15 mm–5 cm**; **LOA30 = 5–15 mm**; **LOA40 = 1–5 mm**; LOA50 = 0–1 mm. Phone-based 3DGS will realistically land around or below LOA20 — adequate for buyer-facing "indicative" measurements, not for fabrication.

**5. No-reference and multi-view-consistency QA is the frontier for production gating.** New research (NeRF-NQA, NVS-SQA, GS-QA, Explicit-NeRF-QA) shows standard NR metrics correlate only weakly (~0.4–0.6) with human judgment [Radiance Fields](https://radiancefields.com/papers/explicit-nerf-qa-a-quality-assessment-database-for-explicit-nerf-model-compression) of NeRF/GS artifacts, motivating purpose-built NR metrics and multi-view reconstruction-consistency checks.

## Details

### 1. Novel-view / rendering quality metrics (photorealism)

**PSNR (Peak Signal-to-Noise Ratio).** Defined as PSNR = −10·log₁₀(MSE) for images normalized to [0,1] (equivalently 20·log₁₀(MAX) − 10·log₁₀(MSE)). It measures pixel-level fidelity of a rendered view against the held-out ground-truth photo. Strength: simple, interpretable in dB. Weakness: poorly correlated with perceived quality — it over-rewards blur and is dominated by low-frequency error and exposure/color offsets. Typical "good" 3DGS: ~27 dB average on Mip-NeRF 360, ~29–33 dB on well-textured indoor rooms; below ~25 dB indoors usually signals a problem.

**SSIM (Structural Similarity).** Compares local luminance, contrast, and structure over sliding windows; ranges [0,1], higher is better. More perceptual than PSNR. Good 3DGS: ~0.81 average on Mip-NeRF 360, ~0.90+ on indoor rooms and Deep Blending. Below ~0.8 indoors is weak.

**LPIPS (Learned Perceptual Image Patch Similarity).** Distance between deep features (AlexNet or VGG) of the two images; lower is better; correlates best with human perception of the three. Note the backbone matters — the original 3DGS uses VGG-LPIPS; torchmetrics defaults differ, so results are not directly comparable across implementations. Good 3DGS: ~0.21 on Mip-NeRF 360, ~0.12–0.18 on garden/kitchen, ~0.24 on Deep Blending.

**Train/test conventions.** The Mip-NeRF 360 / 3DGS convention holds out every 8th image for testing (7/8 train, 1/8 test); nerfstudio uses evenly-spaced 1-in-8 or 10%. Report metrics on the held-out set only. The original 3DGS repo computes L1+PSNR on the test set at 7K and 30K iterations by default (`--eval` flag for the MipNeRF360-style split). (The 3DGS paper's ablation/eval used Bicycle, Garden, Stump, Counter and Room from Mip-NeRF360; Playroom and DrJohnson from Deep Blending; and Truck and Train from Tanks & Temples.)

**How to compute in practice:**
- **Original 3DGS eval:** `train.py --eval`, then `render.py` and `metrics.py` from graphdeco-inria/gaussian-splatting produce per-scene PSNR/SSIM/LPIPS.
- **gsplat:** `examples/simple_trainer.py` reproduces 3DGS PSNR/SSIM/LPIPS; note gsplat evaluates LPIPS via `torchmetrics.image.lpip.LearnedPerceptualImagePatchSimilarity`, which differs from the paper's lpipsPyTorch.
- **nerfstudio:** `ns-eval --load-config config.yml --output-path eval_results.json` renders held-out views and dumps PSNR/SSIM/LPIPS to JSON.
- **torchmetrics:** `PeakSignalNoiseRatio`, `StructuralSimilarityIndexMeasure`, `LearnedPerceptualImagePatchSimilarity` for a custom harness.

**Limitations specific to 3DGS.** (a) They only sample poses near the capture trajectory, so floaters/popping visible when a buyer orbits off-path go undetected. (b) They are sensitive to auto-exposure/white-balance drift between phone frames — a well-reconstructed scene can score low PSNR purely from color mismatch (nerfstudio addresses this with per-image appearance embeddings at eval time). (c) A single blurry test frame or a moving object (person walking through) tanks the average.

**No-reference and perceptual metrics (no ground truth needed).** Available in **pyiqa (IQA-PyTorch)**, [Readthedocs](https://iqa-pytorch.readthedocs.io/) one pip install, GPU-accelerated, calibrated to reference MATLAB:
- **NIQE** — training-free natural-scene-statistics model; [arxiv](https://arxiv.org/pdf/2411.17787) lower = more "natural."
- **BRISQUE** — trained NSS model; lower is better.
- **MUSIQ** — multi-scale ViT, robust to resolution/aspect; [arxiv](https://arxiv.org/pdf/2411.17787) higher is better.
- **TOPIQ (topiq_nr)** — top-down semantic-to-distortion NR metric; higher is better.
- **CLIP-IQA / QualiCLIP** — CLIP-based, context-aware.
- **FID** — distribution distance between rendered and real image sets (needs a reference set of real photos, not per-image GT); useful for batch-level drift monitoring.
These score single frames; for a fly-through render, run per-frame and pool. **VMAF** (Netflix) and **FAST-VQA** score whole video clips [Fora Soft](https://www.forasoft.com/learn/video-quality/articles-vqm/open-source-no-reference-tools) for fly-through delivery QA. FFmpeg has built-in NR filters (`blockdetect`, `blurdetect`, `freezedetect`) [Fora Soft](https://www.forasoft.com/learn/video-quality/articles-vqm/open-source-no-reference-tools) for cheap artifact screening. Caveat: pyiqa is under a NonCommercial + NTU S-Lab license [GitHub](https://github.com/chaofengc/IQA-PyTorch/tree/main?tab=readme-ov-file) — check licensing before commercial deployment.

**3DGS-specific quality research (2023–2026).** NeRF-NQA (Qu, Liang, Chen, Chung & Shen, IEEE TVCG, DOI 10.1109/TVCG.2024.3372037, published 4 Mar 2024) is "the first no-reference quality assessment method for densely-observed scenes synthesized from the NVS and NeRF variants," validated against 23 mainstream visual-quality methods. It combines a viewwise module (spatial quality + inter-view consistency) and a pointwise module (angular consistency of surface points [arXiv](https://arxiv.org/html/2412.08029v1) sampled via COLMAP, using Pointwise Normalized Spherical Gradients). NVS-SQA learns NR quality via self-supervision without human labels. GS-QA and Explicit-NeRF-QA are subjective databases; Explicit-NeRF-QA found best full-reference correlation ~0.85 but all NR metrics only 0.4–0.6 — a warning that no single NR number is trustworthy alone. [Radiance Fields](https://radiancefields.com/papers/explicit-nerf-qa-a-quality-assessment-database-for-explicit-nerf-model-compression) Floater detectors are an active area: StableGS, TIDI-GS, EFA-GS, and "Low-Frequency First" target floater suppression, and statistical-outlier-removal (SOR) tools (e.g., on opacity + spatial isolation, default 2.0σ) [Polyvia3d](https://www.polyvia3d.com/splat-cleanup) can flag/remove stray Gaussians as a post-hoc QA signal.

### 2. Geometric / surface accuracy metrics

**Chamfer Distance (CD).** Symmetric mean nearest-neighbor distance between reconstructed point set P and ground-truth Q: d_CD = (1/|P|)Σ_p min_q‖p−q‖ + (1/|Q|)Σ_q min_p‖q−p‖ (some use squared distances). On DTU it is reported in mm as the average of accuracy (P→Q) and completeness (Q→P). Compute with Open3D (`compute_point_cloud_distance`), the official DTU MATLAB/Python script, or CloudCompare.

**Hausdorff distance.** Maximum of nearest-neighbor distances — the worst-case deviation; very sensitive to outliers. Useful as a max-error bound, available in CloudCompare and Open3D.

**Accuracy / completeness (precision/recall) and F-score.** The Tanks & Temples protocol: for a distance threshold d, Precision P(d) = % of reconstructed points within d of GT (accuracy), Recall R(d) = % of GT points within d of the reconstruction (completeness), and F-score = 2PR/(P+R). [tanksandtemples](https://www.tanksandtemples.org/tutorial/) The threshold is set per-scene by the official evaluation scripts (not a single global constant), based on scene scale; reconstructions must be uploaded to the official site for the hidden test set. ETH3D uses the same harmonic-mean F1 formulation.

**Cloud-to-cloud (C2C) and cloud-to-mesh (C2M) distances.** CloudCompare's standard tools for comparing a reconstruction against a reference scan; C2M is the industry method for verifying a modeled surface fits a reference point cloud within LOA tolerance.

**Normal consistency.** Mean cosine similarity of normals between matched surface points; penalizes rough/bent surfaces that CD alone misses. Used by 2DGS and indoor-reconstruction papers.

**Evaluating 3DGS geometry specifically.** Because raw 3DGS produces noisy, non-surface geometry, evaluate the extracted mesh from surface-accurate variants: **2DGS** (flattens Gaussians to disks + normal consistency), **GOF** (Gaussian Opacity Fields), **PGSR** (planar-based), **SuGaR** (surface-aligned). Benchmark numbers (see Key Findings #2): DTU CD ranges from ~1.96 mm (raw 3DGS) down to ~0.47–0.53 mm (PGSR); T&T F-scores 0.32 (2DGS) → 0.52 (PGSR). For indoor scenes specifically, texture-adaptive/planar-prior methods report F-scores ~0.77–0.83 on ScanNet++/Replica/MuSHRoom.

**Depth accuracy.** Compare rendered/estimated depth to GT depth (LiDAR or RGB-D). Standard metrics:
- **AbsRel** = (1/N)Σ|dᵢ−d*ᵢ|/d*ᵢ (relative absolute error).
- **RMSE** = √((1/N)Σ(dᵢ−d*ᵢ)²).
- **δ<1.25** = fraction of pixels where max(dᵢ/d*ᵢ, d*ᵢ/dᵢ) < 1.25 (and δ²<1.25², δ³<1.25³); higher is better.
The SceneSplat-7K indoor 3DGS dataset (Li et al., arXiv:2503.18052 — 7,916 scenes, 11.27 billion Gaussians, 4.72M RGB frames from ScanNet/ScanNet++/Replica/etc.) reports an average PSNR of 29.64 dB, depth loss of 0.035 m, SSIM 0.897, and LPIPS 0.212 — a useful sanity benchmark for indoor rendering and depth error.

### 3. Metric measurement accuracy (the real-estate crux)

**Validation against ground truth.** Take a set of known real-world distances (wall lengths, door widths, floor-to-ceiling heights) measured with a laser distance meter (Leica/Bosch, ±1.5 mm), total station, or terrestrial laser scan (TLS), and compare against the same distances measured in the reconstruction. Report **absolute error** (cm) and **percent error** per dimension, plus RMSE across a set. This is the single most decision-relevant metric for buyers.

**Scale accuracy.** RGB-only SfM reconstructs only up to an unknown similarity (scale) factor. Recover metric scale by (a) ARCore/ARKit poses or IMU (inertial scale typically ~1% error), (b) a known reference object / fiducial of measured length (s = d_real/d_model, then scale all vertices V′=sV; areas scale by s², volumes by s³), or (c) known camera-rig geometry. Validate scale by checking multiple independent known distances across the scene to detect **scale drift** (error growing with distance from the anchor) — a common failure in large or elongated spaces.

**Published expectations vs. laser ground truth.** Smartphone photogrammetry interiors: 3–5 cm typical, ±5–15 cm on low-texture walls, degrading to 10–20 cm (2σ) in some Scan-to-BIM pipelines. iPhone/iPad LiDAR: 1–2 cm linear at close range (static acquisition vs. Trimble TX8 TLS), ~1 m² on area, degrading to ±30 cm beyond ~4 m and in dynamic capture. Photogrammetry SfM point clouds can reach ~1–2 cm RMSE under controlled capture with good texture. Industry tolerance mapping: USIBD LOA20/30/40 as above.

### 4. Practical / operational QA signals (automatable, no GT)

**SfM health (COLMAP) — the earliest predictor of a bad reconstruction:**
- **Image registration rate** = registered images / total; low rate ⇒ capture gaps or texture-poor walls. COLMAP's `model_analyzer` prints statistics.
- **Mean reprojection error** (px) — expect sub-pixel to ~1 px for a healthy model; higher indicates bad matches/poses.
- **Mean track length** — number of images observing each 3D point; longer tracks ⇒ more reliable geometry.
- **Number of 3D points / completeness** — sparse points in a region predict holes.

**Training / reconstruction health:**
- Final L1/photometric loss and convergence at 7K vs 30K.
- **Number of Gaussians** (typically 1–6 M per scene) — abnormally low ⇒ under-reconstruction; runaway growth ⇒ overfitting/floaters.
- **Train vs held-out PSNR gap** — a large gap signals overfitting to training views (floaters that look fine on-path, bad off-path).
- **Opacity distribution** — many low-opacity, spatially isolated Gaussians ⇒ floaters; near-plane culling (z-threshold, default 0.2) and opacity regularization mitigate them.

**No-GT quality assessment (the production default):**
- **Cross-validation hold-out:** withhold ~1-in-8 views from training, render them, compute PSNR/SSIM/LPIPS — full-reference quality without external GT.
- **Multi-view consistency:** render from a training pose, warp to a neighbor using COLMAP depth/pose, and measure photometric/depth agreement; floaters break consistency (the mechanism StableGS exploits). The Multi-view Reconstruction Consistency (MRC) idea generalizes this. [arxiv](https://arxiv.org/pdf/2605.18052)
- **No-reference IQA** on rendered path + off-path views via pyiqa (MUSIQ/TOPIQ/BRISQUE/NIQE).
- **Floater/outlier detection** via statistical outlier removal on opacity + isolation.

**Coverage / completeness:** check camera pose coverage (angular/spatial gaps), detect unobserved regions (holes) in the mesh, and flag surfaces seen from too few views. Poor coverage is the leading cause of holes and floaters in phone captures of rooms.

### 5. Datasets and benchmarks

- **Mip-NeRF 360** (9 scenes, indoor+outdoor, unbounded): the primary **rendering** benchmark; no GT geometry. Good-3DGS ≈ PSNR 27.2 / SSIM 0.81 / LPIPS 0.21.
- **Tanks & Temples** (real indoor/outdoor, LiDAR GT): both rendering (2-scene Truck/Train subset) and **surface** (6-scene subset: Barn, Caterpillar, Courthouse, Ignatius, Meetingroom, Truck) via F-score.
- **Deep Blending** (real indoor, challenging): rendering; 3DGS ≈ PSNR 29.4 / SSIM 0.90.
- **DTU** (object-scale, structured-light GT): the standard **surface** (Chamfer, mm) benchmark; controlled, object-centric, 15-scan subset.
- **ScanNet / ScanNet++** (real indoor, sub-mm laser GT + DSLR + iPhone RGB-D): the most relevant benchmark for real-estate indoor geometry and novel view synthesis; has an official Gaussian Splatting leaderboard (TUM).
- **Replica** (synthetic indoor, high-fidelity): clean indoor geometry/depth benchmark.
- **ETH3D** (multi-view stereo, laser GT): F1 surface benchmark.
Leaderboards to watch for "good": the ScanNet++ NVS/GS benchmark, the Tanks & Temples official site, and the per-paper tables above.

## Recommendations

**Stage 0 — Pre-flight (SfM gate).** Reject/re-capture before training if COLMAP registration rate < ~90% of images, mean reprojection error > ~1.0–1.5 px, or there are large unregistered clusters (coverage holes). This is cheap and catches most bad captures.

**Stage 1 — Training health gate.** After 30K iterations, require: held-out PSNR ≥ 28 dB (indoor rooms), SSIM ≥ 0.90, LPIPS ≤ 0.20; train−test PSNR gap < ~3 dB; Gaussian count within an expected band; floater/opacity-outlier fraction below threshold. Compute via `ns-eval` or the 3DGS metrics script on 1-in-8 held-out views.

**Stage 2 — No-GT rendering QA (every reconstruction).** Render both on-path and deliberately off-path (orbit/ceiling) views; run pyiqa MUSIQ + TOPIQ + NIQE and a multi-view-consistency check; run VMAF/FAST-VQA on the delivered fly-through. Flag for human review if NR scores fall in a bottom percentile calibrated on your own accepted scenes (do not use absolute NR thresholds — they don't generalize).

**Stage 3 — Geometry gate (if delivering mesh/floor-plan).** Extract the mesh with a surface-accurate variant (PGSR or 2DGS/GOF), not raw 3DGS. Where you have periodic TLS/LiDAR reference scans, compute Chamfer/F-score/C2M in CloudCompare or Open3D; target indoor F-score and cm-level C2M consistent with LOA20.

**Stage 4 — Metric/scale gate (HARD gate, buyer-facing).** Verify metric scale is anchored (ARCore/reference object). On a rolling QA sample, physically measure ≥5 known distances per property with a laser meter and require median absolute error ≤ 2–3 cm and ≤ 2% per dimension; investigate any scale drift. **Fail the whole delivery if scale is unverified** — and show measurements to buyers with an explicit tolerance disclaimer.

**Go/No-Go combination.** Accept only if Stage 0, 1, and 4 all pass and Stage 2/3 are within band. Route borderline cases (one soft metric failing) to human review. Reject on any hard-gate failure (scale unverified, registration collapse, held-out PSNR far below band).

**Thresholds that change the plan.** If you add LiDAR-capable devices, tighten the metric gate toward 1–2 cm (LOA20 → near LOA30) and geometry becomes measured rather than inferred. If buyers begin using measurements for anything contractual, move to laser/TLS verification and publish LOA compliance.

**Tooling summary.** COLMAP (SfM health, `model_analyzer`); nerfstudio `ns-eval` + gsplat/3DGS scripts + torchmetrics (PSNR/SSIM/LPIPS); pyiqa (NR: MUSIQ/TOPIQ/BRISQUE/NIQE/CLIP-IQA — check license); Open3D + CloudCompare (Chamfer/Hausdorff/C2C/C2M/F-score); FFmpeg + VMAF/FAST-VQA (video QA); PGSR/2DGS/GOF (surface mesh extraction).

## Caveats
- **Metric numbers vary by implementation.** LPIPS backbone (VGG vs AlexNet), image resolution/downscaling, exposure handling, and train/test split all shift PSNR/SSIM/LPIPS; compare only within a fixed harness. gsplat and the original 3DGS repo differ on LPIPS.
- **Original 3DGS T&T and Deep Blending averages are 2-scene numbers**, and the T&T surface F-score uses a different 6-scene subset — do not conflate rendering and surface benchmarks.
- **Tanks & Temples F-score threshold is per-scene** (set by official scripts), not a single published constant.
- **PGSR numbers differ between paper and code** (DTU 0.53 mm paper vs 0.47 mm tuned code; the paper's T&T 6-scene mean F1 is 0.52; some third-party tables quote slightly different values).
- **No-reference metrics correlate only weakly with human judgment** (~0.4–0.6 in NeRF QA studies); use them as relative screens calibrated on your own data, never as sole absolute gates.
- **Object-scale benchmarks (DTU) don't transfer directly** to room-scale phone capture; treat DTU mm-scale CD as a method-ranking signal, not an expected accuracy for houses.
- **Smartphone accuracy figures are context-dependent** on texture, lighting, and capture discipline; low-texture walls are the dominant failure mode for RGB-only interiors.