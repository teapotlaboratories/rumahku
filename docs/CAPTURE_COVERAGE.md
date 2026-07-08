# Capture coverage overlay (Polycam-style)

Goal: while scanning, overlay a rough "processed" surface on the areas you've
already captured, so **gaps are obvious** — exactly like Polycam's live mesh.
The Pixel 6 has no LiDAR, so we use ARCore's **Depth API** (depth-from-motion).

## Approach (build on the existing coverage pipeline)

Today: one teal dot per keyframe (`CoverageBuffer` → `CoverageRenderer`, drawn
world-anchored each frame). We make that coverage **dense and surface-shaped**:

1. **Enable depth**: `Config.DepthMode.AUTOMATIC` when
   `session.isDepthModeSupported(...)` (Pixel 6: yes; degrade gracefully to the
   keyframe dots otherwise).
2. **Sample depth each captured frame** (`DepthCoverage`): `acquireDepthImage16Bits()`,
   walk a strided grid, unproject each pixel to camera space using the camera
   intrinsics (scaled to the depth resolution), then to **world** via the camera
   pose. Range-gate to 0.3–5 m.
3. **Voxel-dedup** into `CoverageBuffer` (~8 cm grid): covered surfaces persist
   and the point count stays bounded (a room ≈ 10–40 k voxels). This is what
   makes "already captured" stick as you move.
4. **Render** the accumulated points as a translucent **teal wash** (reuse
   `CoverageRenderer`, tuned to smaller overlapping splats so they read as a
   rough surface). Uncaptured areas have no wash → plain camera → obvious gap.

## Coordinate math (depth pixel → world)
Camera space (ARCore/OpenGL: +X right, +Y up, −Z forward), for pixel (u,v),
depth z (m), intrinsics (fx,fy,cx,cy) scaled to depth res:
`x=(u−cx)·z/fx`, `y=−(v−cy)·z/fy`, `z_cam=−z`; then `world = pose.toMatrix() · [x,y,z_cam,1]`.

## Scope / limits
- v1: no occlusion (overlay draws over everything) and no true meshing — a dense
  voxel point wash reading as a rough surface. Good enough to see gaps.
- Bench can only verify the **static** case (surface in front of a stationary
  phone fills in); the accumulate-as-you-move behaviour needs a real scan.
- Follow-ups: real triangle mesh (marching cubes / per-frame depth mesh), depth
  occlusion, color the wash by "confidence" or fade old coverage.

## Files
- `ar/CoverageBuffer.kt` — add voxel dedup
- `capture/DepthCoverage.kt` — depth → world points (new)
- `capture/CaptureSession.kt` — sample depth each stable frame (throttled)
- `CaptureActivity.kt` — enable `DepthMode.AUTOMATIC`
- `ar/gl/CoverageRenderer.kt` — tune splat size/alpha for a surface wash
