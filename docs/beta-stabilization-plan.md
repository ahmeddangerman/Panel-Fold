# Beta Stabilization and Reading Tools Implementation Plan

> Execute the user's approved audit fixes and four recommended additions continuously. The audit is the specification; no further design approval is needed.

**Goal:** Fix the current audit findings, add portable backups, per-comic fit/crop/zoom controls, adjustable motion, and cover tools, then produce a tested beta APK.
**Architecture:** Preserve the native Android/Compose application. Partition implementation by file ownership and explicit interfaces, then integrate MainActivity/ComicsScreens centrally. Keep original page bytes unchanged and make detail rendering bounded and cancellable. Use local SAF documents for JSON backups; no cloud account or AI features.
**Spec:** `docs/final-beta-audit-2026-10-25.md` plus the user's instruction to fix issues and add the four recommendations in the audit response.

## Global constraints

- Work in the existing `ui-rehaul` feature branch; preserve the user's current uncommitted work and unrelated files.
- MatePad/Android API 26+ support; full-screen reading, Photo swipe only, top-aligned preserved zoom.
- No original image rewriting or lossy compression. Optional border cropping is a display operation.
- All IO/decoding off Main, bounded memory and cooperative cancellation, no stale session publication.
- Do not invent a previous signing private key or claim on-device verification without a device.
- Root owns MainActivity.kt, ComicsScreens.kt, build configuration, release/signing integration and final documentation. Agents must not edit those files.
- Independent implementations may run concurrently only with disjoint file ownership. Root runs shared Gradle verification.

## Tasks / interfaces

- [x] Reader renderer: fix centered inverse zoom ROI, complete adaptive tile coverage, shared floating tile boundaries, loading versus true-end handling, gesture snapshot availability. Add `PageFit { PAGE, WIDTH, HEIGHT }` and PageTurnReader parameters `totalPages: Int`, `fitMode: PageFit = PAGE`, `cropBorders: Boolean = false`, `keepZoom: Boolean = true`, `panSensitivity: Float = 1.5f`, `momentumStrength: Float = 1f`. Extend `onDecodeRegions` to `(FullResolutionPage, List<Rect>, Int) -> List<Bitmap?>`, where Int is power-of-two decode sample size. Tests for ROI, edge/late neighbors, tile coverage, fit/crop geometry.
- [x] Reader session: retain source files for every page of every cached unit, serialize cancellation-aware region decoding and source cleanup, fix thumbnail epoch publication, reuse staged original bytes before rereading archives. Produce `suspend fun decodeRegions(source, regions, sampleSize: Int = 1)`. Isolate source directories by session and remove the startup whole-directory delete in root integration. Regression tests and instrumentation coverage where real bitmaps/DB are required.
- [x] Archives/native: fix solid-RAR state reuse and cancellable skip replay; enforce requested dictionary ceiling; reject ambiguous duplicate names or preserve exact entry identity; support SFX ZIP previews and entry caps; enable 16 KiB ELF alignment. Keep ArchiveReader public interface stable for the UI. Provide native host probes.
- [x] Backup/data: portable versioned JSON export/import with bounded input, validation before writes, merge without deleting current data, progress/favorites/bookmarks/collections/settings and per-comic preferences. Implement `LibraryRepository.exportBackup(prefs): ByteArray`, `importBackup(bytes, prefs): BackupImportResult`; remap unique title/folder/format matches across changed SAF URIs. Correct added_at import time. Tests for round-trip, corrupt/versioned input and remapping.
- [x] Root UI/tools: clearable collection scope, scrollable collection list, saved destination scroll position, correct restart destination, ordered startup cleanup. Add SAF export/import actions, fit/crop/keep-zoom options, global pan/momentum controls, per-comic cover retry/selection/reset, cache size/clear actions. Persist all settings.
- [x] Release: enable stable local beta signing based on an available existing key, optional external release signing, increment versionCode and label as beta; keep private keys out of Git. Search for the historical signer and explicitly document if unrecoverable. Commit complete local source after verification; remote GitHub backup cannot be created without a selected destination.
- [x] Review/verify: compile and run full JVM suite/lint/debug/release/androidTest APK assembly; compile/run compressed and solid native probes; inspect signing and ELF alignment. Independent final review, fix findings, document actual remaining device-only checks.

## Progress and rulings

- Initial state: base commit c2ad393, existing uncommitted UI/reader work and untracked original artifacts preserved.
- Ruling: the requested recommendations are the four items in the final audit response (backup, fit/crop, adjustable motion, cover tools). System schedules and reading queues remain optional suggestions, not part of this authorized batch.
- Ruling: per-comic reading preferences use existing SharedPreferences storage and are included in backups. Cover page selection is one-based in the UI and zero-based internally.
- Ruling: native libraries and portable backup validation require meaningful regression coverage; device UX cannot be certified in this environment.

### Resume ledger — 26 October

- Interrupted workers were replaced by resume_reader, resume_session, resume_archives and resume_backup. Their implementation reports are in docs/. They have completed source work and are holding edits.
- Root integrated SAF backups, per-comic reading controls, cover selection/retry/cache management, collection scope/list/navigation state, correct restart origin and ordered progress persistence.
- New source tests include Compose gestures/loading/zoom and library scope/scroll restoration; they require Android to execute.
- Independent stabilization_review found backup RTL/spreads omission, stale SAF alias defaults/associations, and queued progress/reset/import races. All were fixed and reviewed; docs/stabilization-review.md records no remaining important source finding.
- Native host regression suite and archive-only 21 JVM tests passed. Initial aggregate JVM run passed 125 tests. Full Gradle session 93356 is rebuilding native libraries/release and test APK; final rerun of all tasks is REQUIRED after this initial build because reviewed Main/backup/UI test changes landed after debug compilation began.
- Recovered historical AF83… private key at /tmp/android-user/debug.keystore and current A751… at /home/agent/.android/debug.keystore. Both copied to Git-ignored .signing/{legacy,current}.keystore; beta.lineage generated. scripts/sign-beta.sh signs with legacy->current rotation from API28; debug uses stable current key. Version17 /0.6.1-beta.
- Remaining: finish initialbuild, final full Gradle verification; fix any compilation failures, run sign-beta.sh against final unsigned release; inspect APK signatures/lineage and packaged ELF alignment plus zipalign -P16; write stabilization-results with true device caveats; commit app/code/tests/docs locally without private keys/attachments/old release binaries; link signed beta APK. No remote configured, no device connected.

### Final verification

- Initial full run found test-APK compilation using the pre-helper debug classes after source changes during that build. Final complete rerun rebuilt the debug/release sources and all test classes successfully (1m33s).
- Final full suite:125/125 JVM tests,26classes; debug/release/23-case Android test APK assembly pass; debug lint0errors7warnings1info. Android cases compiled, notexecuted.
- Signed beta releases/tablet-comics-0.6.1-beta.apk has v2/v3 proof-of-rotation and verifies at API26,28,31,35. Both historical andcurrentcertificates present withinstalled-data capability. Packaged64-bit ELFs0x4000,16KiB ZIPalignment pass.
- Devicechecks and remoteGitHubbackup explicitly pending externaldevice/destination. Source/feature implementation complete, detailed results in beta-stabilization-results.md.
