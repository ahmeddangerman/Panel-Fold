# Reader implementation report

Implemented the Reader task in `docs/beta-stabilization-plan.md`, covering audit R1/R3/R4/R5 and the fit/crop/zoom/motion additions.

## Integration interface

`PageTurnReader` now requires `totalPages: Int` between `next` and `rightToLeft`. Its additional named options are:

- `fitMode: PageFit = PageFit.PAGE`, with `PageFit { PAGE, WIDTH, HEIGHT }`.
- `cropBorders: Boolean = false`.
- `keepZoom: Boolean = true`.
- `panSensitivity: Float = 1.5f`.
- `momentumStrength: Float = 1f`.

`onDecodeRegions` is now `suspend (FullResolutionPage, List<android.graphics.Rect>, Int) -> List<Bitmap?>`. The third argument is a power-of-two decode sample size. Pass it to `ComicReaderSession.decodeRegions`.

The saved zoom scale is relative to the selected fit. Each page starts at the top when its displayed content exceeds the viewport height. Turning retains the relative scale when `keepZoom` is enabled, or returns to relative scale 1 at the selected fit otherwise.

## Changes

- Shared `ReaderGeometry` describes crop and destination rectangles for previews, original-source tiles, pan limits, double-tap width fitting and visible-region selection. The inverse zoom transform includes the viewport-center term.
- `DetailTilePlanner` increases a power-of-two sample size until every visible page region fits a single 20-tile allocation budget. Each decoded tile is at most 512 by 512 pixels; both halves of a spread are included before any tiles are published. Large regions are fully covered rather than truncated.
- Preview and tile rendering use Android Canvas floating `RectF` destinations derived from shared source boundaries. Adjacent tiles do not round their origins and sizes independently.
- A missing/failed neighbor is eligible for the end action only if the captured current unit actually contains the final page according to `totalPages`.
- Gesture snapshots preserve both present and absent neighbors. Drawing, drag eligibility and release all read the captured availability; late prefetch can be used by the next gesture.
- `TurnState` retains a settled incoming frame while waiting for the parent's page publication and prevents another gesture during that handoff. Parent acknowledgement or an explicit seek releases the retained frame. All cancellation/layout-reset paths clear the gesture.
- Automatic border detection runs on `Dispatchers.Default`, samples at most a 512 by 512 grid, considers only near-white or near-black opaque uniform edge strips, preserves a safety rim, and refuses trims over 10% on any side. Only display geometry changes; original bytes and bitmap content remain untouched.
- Pan sensitivity controls direct pan and fling velocity; momentum controls glide travel and accepts zero to disable additional travel. Width/height fit can pan at relative scale 1 when its chosen geometry exceeds the viewport.

## Verification

Added 21 regression tests across `ReaderGeometryTest`, `DetailTilePlannerTest`, `BorderCropTest`, `TurnStateTest`, `ZoomBoundsTest` and `ZoomMotionTest`. These include the numerical R1 portrait reproduction, panned/centered inverse transforms, both spread slots, width/height fit and crop mapping, shared floating tile boundaries, randomized partition/coverage tests and giant source dimensions, late neighbors in both reading directions, delayed parent acknowledgement, explicit seek during handoff, conservative edge trimming and adjustable motion.

`git diff --check` passed after implementation. Per shared-worktree instructions, no Gradle task was run by the reader worker; the root integration run must compile and execute these tests. No device verification is claimed. Device checks remain necessary for GPU seam appearance, gesture feel, crop choices on real pages, and memory behavior with large scans.
