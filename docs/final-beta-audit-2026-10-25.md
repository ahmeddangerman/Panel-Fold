# Tablet Comics — beta code audit, 25 October 2026

Historical findings before stabilization. For current status, see [beta stabilization results](beta-stabilization-results.md).

## Assessment

Keep this build in beta. The current working tree compiles and its existing JVM tests pass, but the audit found reader correctness bugs and missing integration coverage. Passing those tests does not establish that full-resolution zoom, asynchronous swipes, or update installation work in every case.

Scope: the current `/workspace` working tree, including the recent uncommitted swipe, zoom, theme, thumbnail and image-budget changes; base commit `c2ad393`. This audit did not modify application behavior. No tablet or emulator is connected.

## Verification

- Full debug JVM suite: **75 tests in 17 classes; 0 failures, 0 errors, 0 skipped**.
- Debug lint: **0 errors, 8 warnings, 1 informational finding**. Warnings include ignoring `Rect.intersect`'s result, outdated dependencies, modifier ordering and launcher resources. These are not evidence of a crash by themselves.
- Debug APK, unsigned release APK and instrumentation APK: **all built successfully**. The combined verification run completed with `BUILD SUCCESSFUL in 5m 14s` (139 actionable tasks). The release build also passed its lint-vital checks.
- Android instrumentation tests were not executed: `adb devices -l` returned no devices, and no installed emulator/system image was found. The existing instrumentation suite covers stored RAR4/RAR5 extraction, not reader gestures or UI integration.
- A host probe compiled the current UnRAR sources and successfully exercised compressed RAR4, compressed RAR5, and solid RAR5 fixtures. This checks the engine on Linux, not Android JNI, SAF access, memory pressure or HarmonyOS.
- APK certificates were inspected with Android `apksigner`; they differ between the historical local 0.6.0 artifact and today's debug artifact (details below).

## Reader findings — fix first

### R1. Zoom detail requests use the wrong inverse transform

**Priority:** P2. **Confidence:** confirmed by source and numerical reproduction.

[PageTurnReader.kt:177](/workspace/app/src/main/java/dev/tabletcomics/reader/PageTurnReader.kt:177) computes the visible image region using `(screen - pan) / scale`. Drawing scales about the viewport center at [PageTurnReader.kt:372](/workspace/app/src/main/java/dev/tabletcomics/reader/PageTurnReader.kt:372). The inverse therefore needs the center term too.

For a 1:5 portrait page in a 1000×1000 viewport, its fitted horizontal bounds are 400–600. At 5× top-aligned zoom, the correct visible unscaled bounds are `(400, 0, 600, 200)`. Current code requests `(0, -400, 200, -200)`, which does not intersect the page. It decodes no detail tiles in that example. Other aspect ratios can sharpen only part of the visible image.

**Remedy:** share the forward/inverse transform math between drawing and tile selection; test top alignment, centered zoom, pan, spreads and both reading directions.

### R2. Source-file cleanup deletes originals still owned by cached pages

**Priority:** P2. **Confidence:** confirmed by source and a deterministic file/set reproduction.

[ComicReaderSession.kt:395](/workspace/app/src/main/java/dev/tabletcomics/reader/ComicReaderSession.kt:395) retains neighboring spread *start indices*, rather than every page in cached units. Opening cover 0 with spread 1+2 prefetched retains source 1 but deletes source 2. Navigating to that cached spread skips decoding, so its missing original is not regenerated. Solid archives can similarly lose the retained previous page's source.

**Impact:** one half of a spread, or a page revisited backward in a solid archive, silently loses full-resolution zoom detail. Original archive data is unaffected.

**Remedy:** derive retained source IDs from all retained `ReaderUnit.pages` and coordinate eviction with active tile decoders.

### R3. Missing prefetch is mistaken for the actual end of the comic

**Priority:** P2. **Confidence:** confirmed control flow.

[PageTurnReader.kt:320](/workspace/app/src/main/java/dev/tabletcomics/reader/PageTurnReader.kt:320) treats an absent forward neighbor as a book boundary. [PageTurnReader.kt:279](/workspace/app/src/main/java/dev/tabletcomics/reader/PageTurnReader.kt:279) then calls the finish action after a qualifying swipe. But [ComicReaderSession.kt:369](/workspace/app/src/main/java/dev/tabletcomics/reader/ComicReaderSession.kt:369) publishes the current page before prefetch finishes.

**Trigger:** swipe quickly after opening or advancing, while the next page is still decoding. The app can display “You reached the end” in the middle of a comic, with actions that mark it finished.

**Remedy:** distinguish actual boundary, loading neighbor and failed neighbor using page indices/count and load state.

### R4. A neighbor arriving mid-swipe can appear and then roll back

**Priority:** P2. **Confidence:** confirmed control flow.

[PageTurnReader.kt:218](/workspace/app/src/main/java/dev/tabletcomics/reader/PageTurnReader.kt:218) falls back to a newly loaded neighbor when the gesture's captured neighbor was null. Drawing uses the same fallback. However, [TurnState.kt:49](/workspace/app/src/main/java/dev/tabletcomics/reader/TurnState.kt:49) decides completion from the original captured snapshot, whose neighbor is still null.

**Impact:** a page that visibly followed a valid swipe returns to the previous page. This is a separate remaining cause of apparent swipe rollback, despite the recent clipping/handoff fix.

**Remedy:** consistently freeze availability for the whole gesture, or explicitly adopt the loaded page into its snapshot.

### R5. Full-resolution tile coverage is incomplete and can show seams

**Priority:** P2 for coverage; P3 for rounding. **Confidence:** confirmed from loops and geometry; visual prominence needs a device.

[PageTurnReader.kt:203](/workspace/app/src/main/java/dev/tabletcomics/reader/PageTurnReader.kt:203) stops after 20 tiles and fills them from the top-left. A visible source region needing more tiles leaves the remainder at base-image resolution; the first page of a spread can consume the entire tile allowance.

[PageTurnReader.kt:411](/workspace/app/src/main/java/dev/tabletcomics/reader/PageTurnReader.kt:411) rounds tile origins and widths independently. Adjacent 512-pixel source tiles at a 0.2 fitted scale can map to `[102,204)` and `[205,307)`, leaving a one-pixel base-image strip. Zoom can magnify it further.

**Remedy:** cover the entire visible region at an appropriate bounded sampling level, prioritize fairly across spreads, and derive adjacent rectangles from shared boundaries.

### R6. Cancelled zoom tile jobs can overlap and continue allocating

**Priority:** P2 performance/memory risk. **Confidence:** scheduling path confirmed; peak memory and crashes not measured.

[ComicReaderSession.kt:83](/workspace/app/src/main/java/dev/tabletcomics/reader/ComicReaderSession.kt:83) runs a synchronous decode loop with no cancellation checks. Panning cancels the calling effect, but its IO loop may continue. A replacement batch can start after the 90 ms debounce before the old batch finishes.

Each batch permits approximately 20 MiB of tiles. That is a per-batch allowance, not a bound on all concurrent in-flight requests. On slow/large-image decoding, obsolete work can consume CPU and memory even though its results will not be displayed.

**Remedy:** check cancellation between regions and limit concurrent tile batches; measure total memory including obsolete jobs and GPU resources on the tablet.

## Lifecycle races

### L1. Startup cleanup can remove newly restored reader sources

**Priority:** P2. **Confidence:** concrete ordering defect; Android reproduction pending.

[MainActivity.kt:301](/workspace/app/src/main/java/dev/tabletcomics/MainActivity.kt:301) launches cleanup that deletes the entire shared source directory. An independent effect at [MainActivity.kt:331](/workspace/app/src/main/java/dev/tabletcomics/MainActivity.kt:331) reopens a restored comic. There is no ordering gate between cleanup and source creation. If restoration creates sources first, cleanup can delete them while cached references remain.

**Remedy:** finish startup cleanup before reopening, or isolate source directories by session and remove only inactive sessions.

### L2. A thumbnail decode can publish into the next book's cache

**Priority:** P2. **Confidence:** concrete interleaving; Android reproduction pending.

[ComicReaderSession.kt:262](/workspace/app/src/main/java/dev/tabletcomics/reader/ComicReaderSession.kt:262) inserts a bitmap after synchronous decode without rechecking session epoch. Switching books clears the index-keyed cache, but an already-running old decode can repopulate it afterward. The next book can return that old page image for the same index.

**Remedy:** validate epoch after decode before cache publication, and key thumbnails by document/session as well as page.

## Library and UI findings

### U1. Opening a collection permanently narrows the Library view

**Priority:** P2. **Confidence:** confirmed assignments and UI paths.

[MainActivity.kt:391](/workspace/app/src/main/java/dev/tabletcomics/MainActivity.kt:391) applies selected folder/custom-collection scope. Collection selection sets that scope; no UI action clears it. Library navigation only changes destination, and “All” only changes reading-status filter.

**Trigger:** open any collection, then tap Library or All. Other comics remain hidden. Saved scope can survive activity recreation. Selecting a different folder can produce an unexpectedly empty library.

**Remedy:** show the active collection and an explicit “All comics”/clear action; reset incompatible scope on folder change.

### U2. Lower collection cards cannot be reached

**Priority:** P2. **Confidence:** confirmed layout structure.

[ComicsScreens.kt:174](/workspace/app/src/main/java/dev/tabletcomics/ComicsScreens.kt:174) renders every collection inside non-scrollable Columns. Once the cards exceed the screen height, lower entries are inaccessible, especially in landscape.

**Remedy:** use a lazy scrolling list with a stable header.

### U3. Returning from reading loses Library scroll position

**Priority:** P2 UX defect. **Confidence:** high from Compose lifetime; device check pending.

[ComicsScreens.kt:154](/workspace/app/src/main/java/dev/tabletcomics/ComicsScreens.kt:154) owns grid state locally. Opening a reader removes that screen from composition at [MainActivity.kt:445](/workspace/app/src/main/java/dev/tabletcomics/MainActivity.kt:445), without a hoisted state or saveable destination holder. Returning recreates the grid at its beginning.

**Remedy:** retain scroll state per destination/collection across reader navigation.

### U4. “Start from beginning” can return to the wrong screen

**Priority:** P3. **Confidence:** confirmed call path.

[MainActivity.kt:694](/workspace/app/src/main/java/dev/tabletcomics/MainActivity.kt:694) calls `openBook` directly instead of `startReading`, bypassing capture of the originating destination. Restarting from Library details can return to Home or a stale destination.

### U5. “Recently added” uses the file modification date

**Priority:** P3. **Confidence:** confirmed data write.

[LibraryRepository.kt:55](/workspace/app/src/main/java/dev/tabletcomics/library/LibraryRepository.kt:55) uses `lastModified` for `added_at`. Adding an old file today can place it behind previously imported comics. Store import time separately from source modification time.

## Archive findings

### A1. Solid RAR extraction repeatedly replays earlier pages and cannot cancel that replay promptly

**Priority:** P2. **Confidence:** source plus current-engine host probe.

[native_unrar.cpp:199](/workspace/app/src/main/cpp/native_unrar.cpp:199) opens a fresh extraction handle for each requested page. [native_unrar.cpp:227](/workspace/app/src/main/cpp/native_unrar.cpp:227) skips all preceding entries; the engine must decompress earlier data for solid archives. The callback used for Kotlin cancellation is not emitted for this prefix replay.

The host solid-RAR5 probe skipped six entries with zero data callbacks, then extracted the target successfully. This confirms the callback gap; it does not measure tablet latency.

**Impact:** late-page jumps and thumbnail-strip loading can be slow; cancelling or switching comics can wait for obsolete solid decoding to finish while the shared archive lock is occupied.

**Remedy:** reuse staged page bytes where available and design serialized sequential extraction/cache handling for solid archives, with cancellation checks at entry boundaries and engine-supported interruption during replay.

### A2. Duplicate archive filenames can repeat one page and omit another

**Priority:** P2 for affected archives. **Confidence:** Java/native host probes.

[ZipArchiveReader.kt:59](/workspace/app/src/main/java/dev/tabletcomics/archive/ZipArchiveReader.kt:59) lists duplicate names as separate pages, but [ZipArchiveReader.kt:66](/workspace/app/src/main/java/dev/tabletcomics/archive/ZipArchiveReader.kt:66) reopens by name. The ZIP probe returned the second entry's content for both identically named pages. Native extraction similarly stops at the first matching name at [native_unrar.cpp:222](/workspace/app/src/main/cpp/native_unrar.cpp:222).

**Remedy:** preserve entry identity/ordinal, or reject ambiguous duplicate paths with an explicit explanation.

### A3. Accepted self-extracting ZIP files can have blank covers

**Priority:** P3 compatibility defect. **Confidence:** source plus Java host probe.

Detection accepts ZIP signatures behind a leading stub, but [ZipArchiveReader.kt:20](/workspace/app/src/main/java/dev/tabletcomics/archive/ZipArchiveReader.kt:20) starts `ZipInputStream` at byte zero. With a prepended stub, the probe returned no first entry. The seekable reader can still open such a file.

**Remedy:** align signature detection and streaming-preview behavior, or use a bounded seekable fallback.

### A4. A low-memory RAR dictionary limit can be bypassed

**Priority:** P2 under low system memory. **Confidence:** confirmed native control flow; device impact unmeasured.

[options.cpp:7](/workspace/app/src/main/cpp/unrar/options.cpp:7) retains a default 32 MiB window. [extract.cpp:1847](/workspace/app/src/main/cpp/unrar/extract.cpp:1847) accepts dictionaries up to that threshold before asking the app's budget callback. If the requested budget is lower than 32 MiB, that callback does not enforce the stated ceiling on these allocations.

**Remedy:** enforce the requested dictionary limit before allocation rather than relying on the default engine threshold.

### A5. Empty ZIP entries bypass the preview work cap

**Priority:** P3 hardening. **Confidence:** confirmed loop structure.

[ZipArchiveReader.kt:139](/workspace/app/src/main/java/dev/tabletcomics/archive/ZipArchiveReader.kt:139) bounds preview work by unpacked bytes, but does not enforce the entry-count cap used by the main reader. Arbitrarily many empty entries consume no content budget and can monopolize the shared archive lock. Cancellation is still checked for each entry.

**Remedy:** enforce the same entry-count ceiling during preview scanning.

## Release and maintenance gaps

1. **Update signing mismatch — release blocker for the affected artifact pair.** Local `releases/tablet-comics-0.6.0.apk` is signed with SHA-256 `af83e47650b7ce108b9c74749e02f7e444f72e18fcf7a3901d96e45463ee727e`. Current `app-debug.apk` uses `a751cfc1f12ff43c69f4f322bb412349cb9a548b0d6a474887ec98ed6b5b45a9`. Both are Android Debug certificates under the same app ID. Android will reject a direct update between these signatures. Preserve a stable signing key and protect saved reading data before any migration; uninstalling to bypass this can delete it. The certificate installed on the user's tablet was not inspected.
2. **Modern Android native page-size compatibility needs work.** The ARM64 library has 4 KiB ELF LOAD alignment; NDK r27 build configuration does not enable flexible/16 KiB page support. This needs correction and testing for 16 KiB-page Android devices before broader distribution. It does not establish a defect on this Android-12-based MatePad.
3. **Release packaging/signing/versioning is unfinished.** The Gradle file has no configured release signing, and recent builds share versionCode 16/versionName 0.6.0. Adopt stable signed beta builds with increasing codes. A successful unsigned release assembly is not an installable production release.
4. **Latest work is not committed or remotely backed up.** The working tree includes modified files and new untracked reader-budget files. `git remote -v` is empty. A local Git repository exists, but these latest fixes are not a GitHub backup.
5. **Integration coverage is missing.** Add tests for actual Compose gesture sequences, async neighbor arrival/failure, page-strip/session switching, original-file retention, process restoration, and on-device peak memory. Current helper tests cannot cover these interactions.
6. **Some documentation is stale.** Older audits describe Junrar and superseded budgets. The README/guide's statement that bounded preview scanning returns no cover if it cannot establish the natural first image also predates the recent best-found-cover fallback. Label historical documents and update the active guide before a public release.

Native/ZIP host probe commands and complete outputs are saved in [audit archive evidence](final-beta-audit-archive-evidence.txt). These probes do not certify Android device behavior.

## Suggested additions after the fixes

- **Export/import reading data:** progress, favorites, bookmarks, collections and per-comic settings in a user-controlled backup. This is the most useful protection before signing or installation changes.
- **Per-comic fit controls:** fit width/height, optional border cropping, and a “keep zoom between pages” switch. Keep the current full-screen reader uncluttered; expose these only in controls/options.
- **Adjustable gesture feel:** pan sensitivity and momentum strength, plus optional volume-button page turns. Use device-tested defaults rather than more animation styles.
- **Cover tools:** retry a failed cover, choose a different cover page, and show/manage cache storage.
- **Follow system/scheduled theme:** extend the existing saved dark/light choice without adding more permanent reader controls.
- **A reading queue:** user-controlled issue order within an existing collection, building on the current next-issue feature rather than duplicating it.

## Recommended order

1. Correct tile transforms, source retention and bounded cancellable tile decoding.
2. Distinguish loading from boundaries; make swipe neighbor availability consistent.
3. Fix collection escape/scrolling, navigation state and lifecycle races.
4. Stabilize solid-RAR cancellation/reads and archive edge cases.
5. Establish signing/versioning/backup, then run a real tablet regression pass.
6. Add backup/export, fit controls and cover tools before optional polish.

No application fixes or new features were implemented as part of this audit.
