# UX — Home + scan library (and visual direction)

Goal: replace the developer "Phase 1 diagnostics" screen with a real,
**bright & friendly consumer** product: a home that shows your scans and makes
the scan → reconstruct → walkthrough journey obvious. First of a broader UX pass.

Chosen direction (2026-07-07): **Home + scan library**, **bright & friendly**
visual style.

---

## 1. Information architecture

Three screens, one clear loop:

```
 Home (scan library)
   │  New scan ──────────────▶ Capture (existing) ──▶ back to Home (new "captured" scan)
   │  tap a "captured" scan ─▶ Reconstruct (foreground service) ──▶ card flips to "ready"
   │  tap a "ready" scan ────▶ Walkthrough viewer (existing, fullscreen)
```

## 2. Home screen

- **Header**: app name "rumahku" + a friendly one-liner ("Scan a room, walk it
  in 3D"). Light, warm.
- **Primary action**: a big rounded **"New scan"** button (or FAB) — the most
  prominent thing on the screen.
- **"Your scans"**: a 2-column grid of scan **cards**, newest first. Each card:
  - **Thumbnail**: `images/000000.jpg` if present; else a placeholder tile with
    an icon (for synthetic/import scans with no images).
  - **Title**: a friendly name — a formatted date/time from the folder
    timestamp (e.g. "Jul 7, 1:42 PM"); non-numeric ids shown as-is.
  - **Status chip**: **Ready** (has a splat → tap opens the walkthrough),
    **Captured** (tap → reconstruct), **Reconstructing…** (while the service
    runs). Colour-coded, soft.
  - Tap routes by status (view / reconstruct).
- **Empty state**: friendly illustration/emoji + "No scans yet — start your
  first scan" + the New-scan button.
- Readiness (ARCore available, camera permission) handled inline/only when
  needed (a gentle prompt), not as a wall of diagnostics.

## 3. Scan status (derived from the folder)

`captures/<id>/`:
- `splat/*.ply` present → **Ready**
- else `transforms.json` + `images/` → **Captured**
- reconstruction running (ReconstructionService.running) → **Reconstructing…**
  (v1: a global "a scan is reconstructing" flag; per-scan tracking is a
  follow-up)

## 4. Visual system — bright & friendly

- **Light**, warm, rounded, soft shadows; content-forward.
- Fixed brand palette (turn OFF wallpaper dynamic-color so the brand is
  consistent): warm **coral** primary + **teal** secondary on a warm off-white
  background. Friendly rounded shapes; generous spacing.
- Typography: the Material3 defaults, slightly larger/rounder headers.
- The fullscreen viewer stays dark/immersive (content is the hero) — the
  chrome around it is the bright part.

Palette (starting point, tune in-app):
- primary `#FF6B5E` (coral), onPrimary white, primaryContainer `#FFD9D2`
- secondary `#12B5A6` (teal)
- background `#FFF7F3` (warm off-white), surface `#FFFFFF`, onSurface `#2B2B2B`

## 5. Build order
1. Brand theme (fixed light palette + shapes).
2. `HomeScreen` (header, New-scan, scan grid, empty state) + scan model.
3. Wire routing (New scan → Capture; card → view/reconstruct).
4. Verify on device (screenshots), iterate on the look.

## 6. Follow-ups
- Per-scan "reconstructing" state + progress on the card.
- Rename/delete scans; scan detail sheet.
- Splat-rendered thumbnails for ready scans (vs first photo).
- Broader visual polish of Capture + Reconstruct screens.
