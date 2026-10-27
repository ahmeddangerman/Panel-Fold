# PanelFlow — Feature wishlist and decision workbook

Status: Brainstorming — selections express preferences, not completed implementation.

## How to fill this in

For each idea, check **one** decision: Want, Skip, or Later. Leave all unchecked if undecided.
Add a priority and your own notes. You can delete items, add new ones, or copy the template at the bottom.
Existing beta features are included because they can be refined; their appearance here does not mean they are missing.

## My vision

What would make me choose PanelFlow over my current reader?

> Write here.

Who should love this app first?

> Write here.

My three most important improvements:

1.
2.
3.

What should the app avoid?

> Write here.

## Preferences already discussed

These are recorded context; edit them if your preference changes.

- App name: PanelFlow.
- Keep the current logo for now.
- The cylinder-like curl effect was disliked and removed.
- Reading should preserve artwork proportions and provide responsive touch movement.
- Original image files should remain untouched, with source detail available when zooming.
- AI is a later topic.
- Kotlin and Compose are the proposed foundation; technology changes remain open for evaluation.

## Suggested order — editable

- [ ] Agree: reading quality and reader behavior first.
- [ ] Agree: library design, series, and metadata second.
- [ ] Agree: backups, server access, and optional sync third.
- [ ] Agree: guided reading and assistance later.

My preferred order / notes:

> Write here.

## 1. Reading quality

### Sharp pages during movement

Keep the page sharp during swipes and zoom transitions.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Responsive pan and pinch

Image movement follows touch reliably, with comfortable momentum.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Consistent double-tap zoom

Predictable animated zoom and reset.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Retain zoom across pages

Keep the selected zoom level and begin the next page at the top.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Orientation and background recovery

Preserve reading state through rotation, backgrounding, and process recreation.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Safe memory use

Read large images and archives with predictable memory use and useful failure recovery.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Night reading without color inversion

Keep the reader controls and page surroundings dark while preserving the comic's original colors. Consider an optional page dimmer and a separate, previewable paper-darkening mode for monochrome manga.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

> Keep original artwork unchanged by default; avoid full color inversion.

## 2. Page movement

### Finger-following flat swipe

Pages keep their proportions and move directly with the finger.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Live next-page preview

Reveal the next page continuously during the drag.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Natural release behavior

A flick completes; a short, slow drag returns smoothly.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Zoom-to-page-turn handoff

Pan within a zoomed page, then turn predictably at its horizontal edge.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Correct RTL turns

Reverse movement and reading order appropriately for manga.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Move landscape spreads together

Treat a two-page spread as one reading unit.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Reduced-motion reading

Offer immediate page changes for readers who prefer them.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Compare standard and custom paging

Evaluate Android's standard pager against our custom engine on the tablet.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Future paper-turn prototype

Only explore a new curl if desired; compare an interactive prototype before adopting it.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 3. Comics, manga, and webtoons

### Horizontal reading

Read comics one page or spread at a time.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### RTL manga reading

Save the right-to-left reading direction.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Continuous vertical reading

Support scrolling webtoons and long pages.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Wide-spread handling

Handle wide artwork and double-page spreads sensibly.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Per-series reading preferences

Remember reading mode, direction, zoom, and layout for a series.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 4. Series and metadata

### ComicInfo.xml metadata

Read embedded series, volume, issue, and other available metadata.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Series and issue organization

Understand volumes, issue numbers, and specials.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Custom reading orders

Arrange story arcs and crossover issues.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Missing-issue indicators

Identify possible gaps when issue metadata is available.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Correct next issue

Continue to the next issue using the selected series or reading order.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 5. Library appearance

### Strong typography and spacing

Create a coherent, readable visual identity.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Generous cover artwork

Make the library enjoyable to browse through artwork.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Fast search

Find books and series quickly.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Useful shelves

Surface meaningful groups rather than overwhelming the home screen.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Resume-focused home

Make returning to an unfinished story immediate.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Visible reading progress

Present progress clearly on covers and series.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Reading-time statistics

Show active reading time per comic and across the library, with daily or weekly history. Pause timing when the app is backgrounded or the screen is off; keep the data local unless sync is added later.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Consistent dark and light appearance

Maintain readable controls and artwork in both themes.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 6. Organization

### Multiple library folders

Combine collections from several selected folders.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Bulk editing

Manage several books or series together.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Smart collections

Create groups from rules and metadata.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Duplicate detection

Identify duplicate files without deleting originals automatically.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Relink moved files

Recover moved books while preserving progress and associations.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 7. Data confidence

### Automatic backups

Schedule backups of reading data and preferences.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Restore preview

Review what a backup will restore before applying it.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Dependable data migrations

Preserve library data across app updates.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Optional device sync

Continue reading across devices.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Progress conflict handling

Resolve conflicting progress clearly when devices reconnect.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 8. Personal-server access

### Komga integration

Connect to a personal Komga library.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Kavita integration

Connect to a personal Kavita library.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### OPDS catalogs

Browse supported personal-library catalogs.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Page streaming

Read pages without downloading an entire archive where supported.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Offline downloads

Download selected books for offline reading.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Server progress sync

Synchronize progress where the server and protocol support it.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 9. Comfort and accessibility

### Adjustable tap zones

Choose where taps advance, go back, or open controls.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Left-handed controls

Make controls comfortable for either hand.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Volume-key navigation

Turn pages with volume buttons when enabled.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Keyboard support

Navigate using a connected keyboard.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Stylus support

Provide useful stylus interactions.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Readable and accessible navigation

Support readable labels, contrast, scaling, and accessible library controls.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 10. Guided reading and later assistance

### Panel-by-panel navigation

Move through individual panels in a deliberate reading order.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Editable panel order

Allow readers to correct panel boundaries or order.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### On-device panel identification

Explore optional assistance later; AI remains deferred unless selected.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Text lookup assistance

Explore optional on-device text lookup later.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## 11. Engineering and quality

### Keep Kotlin and Compose

Continue with the native Android foundation.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Strengthen the custom reader engine

Isolate gestures, page state, image decoding, and loading responsibilities.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Maintain native UnRAR and ZIP support

Use deliberate engine updates and a varied archive regression collection.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Improve source-image tiling

Preserve original source bytes and reveal native source detail at zoom with safe decoding.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Real-device performance checks

Evaluate the MatePad and representative Android devices.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### No page rollback in regression scenarios

Define repeatable scenarios for flashes, stale pages, and accidental reversals.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Progress preservation checks

Verify resume behavior and update/restore safety.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### Frame-time and long-session measurements

Measure frame delivery, memory use, and sustained reading behavior.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

### App-specific performance profiles

Evaluate Baseline Profiles and Android performance benchmarks.

- [ ] Want
- [ ] Skip
- [ ] Later

Priority: <!-- Must-have / Should-have / Nice-to-have -->

My notes / changes:

>

## My additional ideas

Copy this block for each new idea:

### My idea:

Description:

>

- [ ] Want
- [ ] Skip
- [ ] Later

Priority:

My notes:

>

## References from the brainstorm

- [Android architecture recommendations](https://developer.android.com/topic/architecture/recommendations)
- [Compose pager](https://developer.android.com/develop/ui/compose/layouts/pager)
- [Compose performance guidance](https://developer.android.com/develop/ui/compose/performance)
- [CDisplayEx](https://www.cdisplayex.com/mobile/)
- [Mihon](https://mihon.app/)
- [Komga OPDS compatibility](https://komga.org/docs/guides/opds/)

## Next planning discussion

After filling this in, identify the top priorities, resolve conflicting preferences, and turn selected ideas into a scoped design.
Checking a feature here does not automatically start implementation.
