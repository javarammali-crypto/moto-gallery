# Gallery

A personal Android gallery app for the Moto Edge 70 Fusion, styled after the ColorOS 17 gallery: frosted-glass floating bars, blur throughout, and springy animations.

## Install
1. Open the latest release on the **Releases** page of this repo.
2. Download `Gallery-v0.1.x.apk` on your phone and open it. Allow "Install unknown apps" for your browser or file manager if asked.
3. Later builds install over the previous one (same signing key).

## Phase 1 (this build)
- Photos timeline grouped by day (or month at 6 per row), pinch to change the grid, fast date scroller
- Long-press and drag to select many photos; share, favourite, delete
- Floating glass tab bar (Photos / Albums / Create) with layout and filter buttons
- Albums with Favourites, Videos, Screenshots, Recently deleted; pin albums; sort
- Full-screen viewer: zoom, swipe between photos, swipe down to close, video player, details with camera info and location
- Recently deleted: Android's 30-day trash with restore and delete forever
- Opens photos and videos from other apps

## Coming next
2. Search by date, place (map), objects and text in photos
3. People: face grouping and naming
4. Editor: crop, adjust, filters, background removal
5. Private safe, cleanup, collage, memories

Builds run on GitHub Actions (`.github/workflows/build.yml`).
