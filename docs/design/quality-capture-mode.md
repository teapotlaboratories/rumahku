# Quality Capture Mode — locked-exposure photo pass

Status: proposed · Owner: — · Target: +2–4 dB splat PSNR (the road to ~27 dB)

## Why this, why now

The single biggest remaining lever for splat quality is the **capture**, not the
trainer. Validated this session: with the reference pipeline (COLMAP‑SfM init +
the de‑haze config) splatfacto reaches **cc_psnr 23.5**, matching Brush — and
that's near the ceiling for our data. The 27–32 dB indoor benchmark numbers
(Mip‑NeRF360 room 30.6 / bonsai 32.0) come from a **Fujifilm X100V with exposure,
ISO, aperture, white‑balance and focus locked at the first frame**.

On our Pixel‑6 handheld scans, **auto‑exposure is ON**, so per‑view ISP tone drift
breaks multi‑view photometric consistency. That caps **raw held‑out PSNR at
~18–22 dB by tone mismatch alone**, independent of geometry quality — which is why
our raw sits at 18 while cc_psnr is 20–23.5. Raw PSNR is not a valid quality proxy
on auto‑exposure data.

Two capture‑time levers dominate and **stack** (research‑anchored ranges):

1. **Lock exposure + white balance** → photometric consistency across views.
   ~1–3 dB raw; also removes the *source* of floaters. (The bilateral‑grid paper
   measures +2.36 dB affine / +0.76 dB raw recovered purely from ISP variation.)
2. **Fast shutter** → kill motion blur; each blurry test frame tanks the average.
   ~1–3 dB.

Target: **+2–4 dB → ~cc 26–27**, approaching the benchmark. This is more than the
entire splatfacto rewrite delivered. No trainer change reaches it (on clean data
the whole menu of gsplat knobs moves PSNR by only tenths of a dB).

## The ARCore‑depth constraint (learned the hard way)

The live‑mesh AR scan relies on ARCore **depth‑from‑motion**. Re‑issuing
`setRepeatingRequest` mid‑session to add an AWB lock **broke depth‑from‑motion and
killed the live mesh** (see `SharedCameraController` — the `⚠️ LOCK` note).
Therefore:

- **Quality Capture is a separate mode that forgoes the live mesh.** It's a
  deliberate photo pass, not real‑time AR, so it doesn't need depth.
- It must still get ARCore **6DoF poses** (the pipeline uses them as COLMAP
  priors). **Open question to validate FIRST:** does locking AE/AWB break only
  depth‑from‑motion, or also tracking? If tracking survives → done. If not → fall
  back to COLMAP‑only poses (no ARCore priors), a larger pipeline change.

## Camera2 design

Shared camera (ARCore SharedCamera + Camera2), as today. Options for consistent
exposure, best‑first:

1. **Converge‑then‑lock (recommended).** On entering capture, let AE/AWB
   auto‑converge on the scene ~0.5–1 s, then issue **one** repeating request with
   `CONTROL_AE_LOCK=true`, `CONTROL_AWB_LOCK=true`, a fast
   `CONTROL_AE_TARGET_FPS_RANGE` (or capped `SENSOR_EXPOSURE_TIME`), and locked
   focus (`CONTROL_AF_TRIGGER` → AF off, or fixed `LENS_FOCUS_DISTANCE`). A single
   lock at the start (not repeated mid‑scan) is less likely to break tracking than
   the mid‑session re‑issue that broke depth.
2. **Full manual (fallback, most consistent).** Meter once, then `CONTROL_AE_MODE=
   OFF` + fixed `SENSOR_EXPOSURE_TIME`/`SENSOR_SENSITIVITY` + `CONTROL_AWB_MODE=OFF`
   + fixed `COLOR_CORRECTION_GAINS`. Bulletproof consistency, but needs metering
   logic and risks a bad exposure if room lighting varies spatially.

**Fast shutter:** cap exposure to ≲ 1/100 s, raise ISO to compensate. Trade: more
sensor noise in dim rooms — bound with an ISO cap + a "need more light" hint.

## Capture protocol (the other ~half of quality)

- **Coverage:** multi‑height passes + an orbit with **loop closure** (return to the
  start) — this is what gives COLMAP dense, well‑triangulated points. Coverage
  guidance UI is **optional / minimal** — in‑scan coaching was previously rejected;
  keep it opt‑in.
- **Blur gate:** reuse the Laplacian‑variance sharpness reject already in
  `CaptureSession`; raise the threshold in quality mode.
- **Cadence:** capture on movement (existing keyframe logic) or fixed interval;
  aim for **150–400 sharp frames**.
- **Resolution:** pair with the existing high‑res 4K capture for max texture;
  COLMAP/splatfacto downscale as needed.

## Pipeline (no backend change)

Locked, sharp frames + ARCore poses → existing `transforms.json` → the now‑default
splatfacto **COLMAP‑SfM** path (or Brush). Photometric consistency means the
bilateral grid does less work and **raw PSNR converges toward cc_psnr**; expect
both trainers to gain. No `serve.py` change required.

## UX

- **Mode selector** at capture entry: *AR scan (live mesh)* vs *Quality capture
  (photo pass — best splat)*.
- Quality mode: no live mesh; a frame counter, a lock indicator (AE/WB locked ✓),
  and minimal "hold steady / more light" prompts.
- Reuse the high‑res toggle; add no new settings if avoidable.

## Risks / open questions (validate before building)

1. **Does AE/AWB lock break ARCore *tracking* (not just depth)?** — must spike
   first: lock mid‑session, watch `frame.camera.trackingState` + pose stability.
2. Rolling shutter on fast pans (Pixel 6) — mitigate with "move slowly" + blur gate.
3. Low‑light noise from fast shutter — ISO cap + light hint.
4. Focus in a room of varying depth — mid‑distance fixed focus vs one‑time AF lock.

## Phased plan

- **M1 — spike (~0.5 d).** Validate lock‑vs‑tracking, and measure the PSNR delta on
  one **locked vs auto** scan of the same room. Go/no‑go gate.
- **M2 — build (~1–2 d).** Quality Capture mode: converge‑then‑lock + fast shutter
  + blur gate, no live mesh, mode selector.
- **M3 — optional.** Minimal coverage guidance + full‑res pairing.

## Expected outcome

+2–4 dB (cc_psnr ~26–27) on a locked, well‑covered capture — the reference's 27 dB
target, which no trainer change can reach.
