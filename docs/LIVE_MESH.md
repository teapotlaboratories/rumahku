# Live mesh — TSDF fusion + marching cubes (Polycam-style capture)

Goal: while scanning, overlay a **coherent wireframe surface on the room's 3D
contour** that grows only where you've actually captured — so you can tell
scanned vs unscanned at a glance (exactly like Polycam/Scaniverse). The real
Gaussian splat is still produced by the post-capture build; this is capture-time
guidance.

## Why the earlier tries failed
- **Colored coverage dots / points** — read as scattered dots, no surface.
- **Per-keyframe depth triangulation** — each keyframe meshes its own view, so
  overlapping views either **pile up** (thickening triangle layers) or, with
  dedup, degrade to a **jagged patchwork** meshed once from whatever oblique view
  saw a patch first. No shared, coherent surface.

The root cause: without **fusion**, you can't merge many noisy depth views into
one surface. ARCore gives raw per-frame depth, not a fused mesh (ARKit/Polycam
fuse natively). So we fuse it ourselves.

## The pipeline (KinectFusion / Mobile3DRecon)
`TsdfVolume.kt` + `MarchingCubesTables.kt`, rendered by `DepthMeshRenderer`.

1. **TSDF volume** — a voxel-hashed truncated signed-distance field
   (`HashMap<voxelKey, [tsdf, weight, r,g,b]>`), 3 cm voxels, ±6 cm truncation.
2. **Fuse each keyframe** (`integrate`): for every depth pixel, walk voxels along
   the view ray within the truncation band and update a **running weighted
   average** of the signed distance (and colour, sampled from the same keyframe's
   image). Overlapping observations reinforce **one** surface instead of layering.
3. **Incremental marching cubes** (`remeshDirty`): each integrated voxel marks its
   8 incident cubes *dirty*; only dirty cubes are re-polygonised (Paul Bourke
   tables). A cube meshes **only when all 8 corners are observed**, so the surface
   conforms to the real contour and stops cleanly at the frontier — and already-
   captured cubes stay fixed ("meshed once, marked captured").
4. **Render**: the extracted triangle soup (`x,y,z,r,g,b,bary`) drawn depth-tested
   with a barycentric wireframe — the Polycam look.

`snapshot()` is cached and only rebuilt when the mesh changes (not per frame).

## Parameters (tunable in `TsdfVolume`)
- `VOXEL_M = 0.03` — surface resolution (smaller = finer + heavier).
- `TRUNC_M = 0.06` — band each depth sample writes into.
- `DEPTH_STRIDE = 2` (CaptureSession) — depth subsample fed in.
- Fusion happens **at keyframes** (sharp, ~every 0.3 m), not every frame.

## Status / open items
- ✅ Verified on Pixel 6: coherent surface conforming to walls/desk/chair/frame,
  clean frontier, ~25 k faces in a partial scan. Captured-vs-not is now legible.
- ⚠️ **Performance**: integration + incremental MC in Kotlin at keyframes causes
  brief frame skips. Options if it's too janky: coarser voxel/stride, cap dirty
  re-mesh per keyframe, move the hot loops to native (NDK, like brush-ffi), or
  fuse off the GL thread.
- ⬜ Could persist the fused mesh (export the surface) and/or seed the splat from
  it. Not needed for capture guidance.
