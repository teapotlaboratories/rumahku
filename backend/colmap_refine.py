#!/usr/bin/env python3
"""Pose-prior COLMAP refinement — turn ARCore poses into a refined, SfM-seeded
COLMAP model that Brush (or Splatfacto) can train on for higher quality.

Cold SfM fails on our captures: indoor rooms have low view overlap, so COLMAP's
incremental mapper registers only a handful of images. Instead we treat the
ARCore poses as *known* and only ask COLMAP to (a) triangulate a point cloud and
(b) refine the poses. Given a nerfstudio dataset (transforms.json + images/):

  1. SIFT feature extraction + sequential matching (CPU — GPU SIFT needs an
     OpenGL context that isn't available headless in the container).
  2. Write a COLMAP "prior" model straight from the ARCore poses.
  3. point_triangulator with a relaxed min triangulation angle — every view
     keeps its known pose, so even low-parallax indoor frames triangulate.
  4. bundle_adjuster — refine the poses against the triangulated points.

Output: <ds>/colmap/refined_ds/ with sparse/0/*.bin + an images/ symlink, ready
for `brush-cli <refined_ds>`. On success the model path is printed on the last
stdout line (prefixed "REFINED "); on failure nothing is printed and the exit
code is non-zero, so the caller can fall back to the raw ARCore poses.

Measured (295-image scene, controlled A/B, same iters/eval-split): refine +
dense COLMAP init = 23.65 PSNR / 0.806 SSIM vs 23.37 / 0.768 for raw ARCore
poses + the ARCore seed.ply — a modest +0.3 dB / +0.04 SSIM (the SSIM bump is the
more visible win). The *dominant* lever is having SfM points at all: with no
seed points the same scene trains to only ~16.7 PSNR. Refine adds ~5 min (CPU
SIFT + matching). Needs: `colmap` on PATH, numpy. Run: colmap_refine.py <ds_dir>
"""
import json
import os
import subprocess
import sys

import numpy as np


def _run(cmd, log):
    """Run a COLMAP step, appending output to `log`. Raises on non-zero exit."""
    with open(log, "ab") as lf:
        lf.write(("\n$ " + " ".join(cmd) + "\n").encode())
        lf.flush()
        subprocess.run(cmd, stdout=lf, stderr=subprocess.STDOUT, check=True)


def _q_from_R(R):
    """Rotation matrix -> (qw, qx, qy, qz), COLMAP's quaternion convention."""
    t = np.trace(R)
    if t > 0:
        s = np.sqrt(t + 1.0) * 2
        return (0.25 * s, (R[2, 1] - R[1, 2]) / s,
                (R[0, 2] - R[2, 0]) / s, (R[1, 0] - R[0, 1]) / s)
    if R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = np.sqrt(1 + R[0, 0] - R[1, 1] - R[2, 2]) * 2
        return ((R[2, 1] - R[1, 2]) / s, 0.25 * s,
                (R[0, 1] + R[1, 0]) / s, (R[0, 2] + R[2, 0]) / s)
    if R[1, 1] > R[2, 2]:
        s = np.sqrt(1 + R[1, 1] - R[0, 0] - R[2, 2]) * 2
        return ((R[0, 2] - R[2, 0]) / s, (R[0, 1] + R[1, 0]) / s,
                0.25 * s, (R[1, 2] + R[2, 1]) / s)
    s = np.sqrt(1 + R[2, 2] - R[0, 0] - R[1, 1]) * 2
    return ((R[1, 0] - R[0, 1]) / s, (R[0, 2] + R[2, 0]) / s,
            (R[1, 2] + R[2, 1]) / s, 0.25 * s)


def _write_prior(ds, model):
    """Write cameras/images/points3D .txt for the ARCore poses into `model`."""
    tj = json.load(open(os.path.join(ds, "transforms.json")))
    frames = sorted(tj["frames"], key=lambda f: f["file_path"])
    w = int(tj.get("w") or frames[0].get("w"))
    h = int(tj.get("h") or frames[0].get("h"))
    fx = tj.get("fl_x") or frames[0].get("fl_x")
    fy = tj.get("fl_y") or frames[0].get("fl_y")
    cx = tj.get("cx", w / 2)
    cy = tj.get("cy", h / 2)
    os.makedirs(model, exist_ok=True)
    with open(os.path.join(model, "cameras.txt"), "w") as f:
        f.write(f"1 PINHOLE {w} {h} {fx} {fy} {cx} {cy}\n")
    # ARCore/nerfstudio store camera-to-world in OpenGL axes; COLMAP wants the
    # world-to-camera pose in its own axes (flip Y and Z).
    flip = np.diag([1, -1, -1, 1.0])
    lines = []
    for i, fr in enumerate(frames, start=1):
        c2w = np.array(fr["transform_matrix"], float) @ flip
        w2c = np.linalg.inv(c2w)
        qw, qx, qy, qz = _q_from_R(w2c[:3, :3])
        tx, ty, tz = w2c[:3, 3]
        name = os.path.basename(fr["file_path"])
        # trailing blank line = this image's (empty) POINTS2D list
        lines.append(f"{i} {qw} {qx} {qy} {qz} {tx} {ty} {tz} 1 {name}\n\n")
    with open(os.path.join(model, "images.txt"), "w") as f:
        f.writelines(lines)
    open(os.path.join(model, "points3D.txt"), "w").close()
    return len(lines), w, h


def refine(ds):
    """Run the full pose-prior pipeline. Returns the refined model dir."""
    images = os.path.join(ds, "images")
    if not os.path.isdir(images):
        raise SystemExit(f"no images/ dir in {ds}")
    C = os.path.join(ds, "colmap")
    if os.path.isdir(C):
        subprocess.run(["rm", "-rf", C], check=True)
    os.makedirs(C, exist_ok=True)
    log = os.path.join(C, "refine.log")
    db = os.path.join(C, "database.db")

    _run(["colmap", "feature_extractor", "--database_path", db,
          "--image_path", images, "--ImageReader.single_camera", "1",
          "--SiftExtraction.use_gpu", "0"], log)
    _run(["colmap", "sequential_matcher", "--database_path", db,
          "--SiftMatching.use_gpu", "0"], log)

    prior = os.path.join(C, "prior")
    n, w, h = _write_prior(ds, prior)

    tri = os.path.join(C, "tri")
    os.makedirs(tri, exist_ok=True)
    # Relaxed triangulation angles let low-parallax indoor views contribute
    # points instead of being discarded (the poses are fixed/known here).
    _run(["colmap", "point_triangulator", "--database_path", db,
          "--image_path", images, "--input_path", prior, "--output_path", tri,
          "--Mapper.tri_min_angle", "0.5",
          "--Mapper.filter_min_tri_angle", "0.5"], log)

    refined = os.path.join(C, "refined_ds")
    sparse0 = os.path.join(refined, "sparse", "0")
    os.makedirs(sparse0, exist_ok=True)
    _run(["colmap", "bundle_adjuster", "--input_path", tri,
          "--output_path", sparse0], log)

    # SfM health (QA gate) — triangulated points / mean reprojection error / mean
    # track length are the earliest predictors of a bad reconstruction. Emit a
    # machine-readable line the backend parses; never fail the refine over it.
    try:
        import re as _re
        an = subprocess.run(["colmap", "model_analyzer", "--path", sparse0],
                            capture_output=True, text=True)
        txt = an.stderr + an.stdout
        pts = _re.search(r"Points:\s*(\d+)", txt)
        rep = _re.search(r"Mean reprojection error:\s*([\d.]+)", txt)
        trk = _re.search(r"Mean track length:\s*([\d.]+)", txt)
        print("SFMSTATS points=%s reproj=%s track=%s" % (
            pts.group(1) if pts else 0, rep.group(1) if rep else 0, trk.group(1) if trk else 0))
    except Exception as e:  # noqa: BLE001
        print("SFMSTATS points=0 reproj=0 track=0 err=%s" % e)

    # Brush reads a colmap dataset as <dir>/{images,sparse/0}. Symlink the images
    # in rather than copy (they can be hundreds of MB).
    link = os.path.join(refined, "images")
    if not os.path.exists(link):
        os.symlink(images, link)
    print(f"prior images={n} intr={w}x{h}")
    return refined


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("usage: colmap_refine.py <dataset_dir>")
    out = refine(sys.argv[1])
    # Machine-readable last line for the caller to parse.
    print(f"REFINED {out}")
