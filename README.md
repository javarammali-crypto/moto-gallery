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

## Phase 2
- Search by date ("march 2024", "last week", "12 jan"), place, things in photos (food, dog, beach…), text inside photos, album and file names
- Places map with photo pins (OpenStreetMap)
- On-device analysis with ML Kit; continues in the background while charging
- Clearer glass with stronger blur and a specular edge
- In-app updates from GitHub Releases

## Phase 3
- People: faces found and grouped on the phone (ML Kit + FaceNet, see `models/NOTICE.md`), name people, combine, hide, "not this person"
- Search by a person's name
- Set any photo as home, lock or both wallpapers
- Back from a search result returns to the search page

## Phase 4 & 5
- Photo editor: crop/rotate/flip/straighten, light and colour adjustments, 12 filters with strength, on-device background removal/replace; save as copy or replace original (date and location kept)
- Private safe: AES-encrypted with an Android Keystore key, unlocked by fingerprint/PIN, locks when you leave the app
- Cleanup: duplicates, similar shots, blurry photos, large videos, old screenshots
- Collage maker (2–9 photos, layouts, ratios, spacing, corners, background)
- Memories (on this day, trips, people, last 30 days, last year) with a story-style slideshow
- Glass 30% more transparent

Builds run on GitHub Actions (`.github/workflows/build.yml`).
