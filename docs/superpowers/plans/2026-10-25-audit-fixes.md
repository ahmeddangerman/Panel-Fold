# Tablet Comics Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Resolve the ten prioritized reliability and correctness findings before starting new library features.

**Architecture:** Keep the existing Kotlin/Compose app and ZIP/Junrar engines. Add resource bounds and cancellation at the archive boundary, make session/cache transitions safe, then harden reader state, library indexing and packaging.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose, Android SDK 35, min API 26, coroutines 1.9.0, SAF, Junrar 8.1.2, JUnit 4.13.2.

**Spec:** [Audit fixes design](/workspace/docs/superpowers/specs/2026-10-25-audit-fixes-design.md)

## Global Constraints

- Stay offline, use persisted SAF grants, and request no broad storage access.
- RAR dictionary ceiling: min(32 MiB, maxHeap / 10); bitmap cache remains heap-bounded.
- Preserve the 24 MiB per-image entry cap, 8 GiB staged archive cap, and 100 MP image ceiling.
- Never delete source comics; startup cleanup only removes old app-owned inactive staging files.
- Preserve per-comic progress/settings, ascending logical page order, and current reader styles.
- Do not build cover-grid, search, bookmark, cloud or discovery features in this pass.
- Report MatePad behavior as unverified until physically tested.

## Review Focus

- RAR dictionaries above the budget fail before allocation: boundary test in Task 2.
- Cancelling stage/copy closes streams and releases the archive lock: blocking-stream test in Task 3.
- Solid RAR forward reading avoids decoding current twice: sequence test in Task 4.
- Zero release, boundary swipe, fast turn and neighbor update cleanly settle on the previewed page: tests in Task 5.
- Revoked/deleted SAF source and activity recreation recover without crash: tests in Task 8.

---

### Task 1: Archive contracts, format detection, and shared limits

**Files:** Modify app/src/main/java/dev/tabletcomics/archive/ArchiveReader.kt, ZipArchiveReader.kt and RarArchiveReader.kt. Create ArchiveFormat.kt and app/src/test/java/dev/tabletcomics/archive/ArchiveFormatTest.kt.

**Interfaces:** Add enum class ArchiveFormat { ZIP, RAR }; fun detectArchiveFormat(prefix: ByteArray, extension: String?): ArchiveFormat; object ArchiveLimits with MAX_PAGE_BYTES = 24L * 1024 * 1024, MAX_ARCHIVE_BYTES = 8L * 1024 * 1024 * 1024, MAX_IMAGE_PIXELS = 100_000_000L, MAX_ENTRIES = 20_000, and fun rarDictionaryBudget(maxHeapBytes: Long): Long; and OpenArchive.isSolid: Boolean defaulting to false.

- [x] Write tests: detects ZIP local/empty signatures => ZIP; detects RAR4/RAR5 markers => RAR; rejects unknown/mismatched markers; shared limits enforce their boundaries.
- [x] Run the focused unit test; verify new cases fail.
- [x] Implement classification with signature first and extension as hint; unknown/mismatched signatures throw ArchiveFormatException, an IOException subtype.
- [x] Move ZIP/RAR page, archive, entry, page-count, preview and free-space values into ArchiveLimits and replace duplicate constants at both reader boundaries.
- [x] Add isSolid to the archive contract, defaulting to false.
- [x] Rerun focused and full unit tests; all classification/limit assertions pass.

### Task 2: Heap-bounded RAR extraction

**Files:** Modify RarArchiveReader.kt. Create app/src/test/java/dev/tabletcomics/archive/RarArchiveReaderTest.kt.

**Interfaces:** Use ArchiveLimits. Construct Junrar with ArchiveOptions.maxDictionarySize set to min(32 MiB, maxHeap / 10).

- [x] Write tests: rarDictionaryBudgetUsesHeapFractionAndCeiling => min(32 MiB, maxHeap / 10); over-budget RAR header => readable IOException with UnsupportedDictionarySizeException cause.
- [x] Run focused test and confirm the default unbounded Junrar setting fails the assertion.
- [x] Pass an explicit Junrar ArchiveOptions budget and map UnsupportedDictionarySizeException to a readable page error.
- [x] Verify stored RAR4/RAR5 fixture bytes still match and the over-budget case fails recoverably.

### Task 3: Cancellable archive I/O and staging recovery

**Files:** Modify ArchiveReader.kt, ZipArchiveReader.kt, RarArchiveReader.kt, ComicReaderSession.kt and MainActivity.kt. Create StagedArchive.kt and app/src/test/java/dev/tabletcomics/archive/StagedArchiveTest.kt.

**Interfaces:** Change ArchiveReader.open(uri: Uri, cancellationCheck: () -> Unit): OpenArchive and ArchiveReader.readPreview(uri: Uri, cancellationCheck: () -> Unit): ByteArray?. Change OpenArchive.readPage(page: ComicPage, cancellationCheck: () -> Unit): ByteArray. ComicReaderSession passes its active coroutine Job.ensureActive as the check for open/read calls. StagedArchive owns a File, active registration, and idempotent close. Implementations also receive a coroutine-backed resource registrar so cancellation closes a stream even while a blocking read is in progress.

- [x] Write tests: copyCancellationClosesInputAndOutput; stagedArchiveCloseIsIdempotent; cleanupSkipsActiveAndUnrelatedFiles; cleanupDeletesOnlyExpiredOwnedFiles.
- [x] Run focused tests and verify copy/cleanup failures.
- [x] Check cancellation between copy/read chunks; close all SAF and RAR pipe streams in finally; release archiveIoLock.
- [x] Add startup sweep for old inactive generated staging files, preserving active/current files.
- [x] Track/cancel folder scans by Job or generation token.
- [x] Verify cancellation, storage cleanup and stale scan tests pass. (Stale-scan assertion is in Task 9.)

### Task 4: Solid RAR forward-read scheduling

**Files:** Modify RarArchiveReader.kt and ComicReaderSession.kt. Create app/src/test/java/dev/tabletcomics/reader/SolidArchiveSessionTest.kt.

**Interfaces:** Consumes Task 1 OpenArchive.isSolid and Task 3 cancellation-aware readPage signature. For solid archives decode current once at display size and prefetch only the next logical unit; reuse the previous unit only if already cached.

- [x] Add solidAdvanceDecodesCurrentOnceAndReadsNextInOrder; assert extraction sequence [current, next], with no second current decode, and previous remains reusable from cache.
- [x] Run test and confirm current-neighbor-current behavior fails.
- [x] Set isSolid from Junrar headers; bypass duplicate-resolution current upgrade and backward prefetch for solid RAR.
- [x] Keep only current and already-decoded neighbors within the existing session window.
- [x] Verify sequential order, fixture pages and replay behavior on a cache-miss backward seek. (Junrar replay on backward seek is retained as designed; device latency remains unverified.)

### Task 5: Gesture cleanup and stable turn commit

**Files:** Modify PageTurnReader.kt. Create TurnState.kt and app/src/test/java/dev/tabletcomics/reader/TurnStateTest.kt.

**Interfaces:** TurnState owns the active source/neighbor snapshot, settle target, offset and cleanup. Completion accepts a captured ReaderUnit; onTurn(target: ReaderUnit) remains.

- [x] Write tests: releaseAtZeroClearsSnapshot; boundaryGestureClearsSnapshot; cancelledGestureClearsSnapshot; layoutResetClearsSnapshot; completionCommitsCapturedTarget.
- [x] Verify new reducer tests fail against current ad hoc state.
- [x] Implement one cleanup path with finally and clear state on zero-offset/boundary gestures.
- [x] Hold the captured page through animation and commit that same page.
- [x] Run reducer and navigation tests. Compose physical gesture tests remain unverified because this project has no Compose UI test harness and no MatePad is attached.

### Task 6: Seeking and loading/error feedback

**Files:** Modify MainActivity.kt and ComicReaderSession.kt. Create app/src/test/java/dev/tabletcomics/reader/SeekStateTest.kt.

**Interfaces:** Slider keeps a draft index and calls navigate once on release. Session deduplicates current/pending destinations and exposes requested destination separately from last rendered page.

- [x] Write tests: seekClampsToBookBounds; seekMapsToSpreadStart; duplicateDestinationDoesNotStartWorker; failedSeekKeepsCurrentAndOffersRecovery.
- [x] Verify tests fail against immediate navigation on each slider update.
- [x] Navigate on slider release, show requested page and loading while retaining the last bitmap.
- [x] Add retry/return action for errors and clear them on a new request.
- [x] Run seek/session tests and verify first/last page controls.

### Task 7: Bounded zoom and reset

**Files:** Modify PageTurnReader.kt and ComicReaderSession.kt. Create app/src/test/java/dev/tabletcomics/reader/ZoomBoundsTest.kt.

**Interfaces:** ZoomBounds maps viewport, fitted page rect, scale and pan to bounded offsets. Reset returns scale/pan to identity; higher-detail sampling may not exceed cache budget.

- [x] Write tests: panClampsPortraitBounds; panClampsLandscapeBounds; smallPageRemainsCentered; resetRestoresIdentity.
- [x] Verify the tests expose current unbounded pan.
- [x] Clamp pan on pinch/pan updates; add double-tap and visible reset control.
- [x] Keep the existing display-resolution sample while zoomed; no higher-detail bitmap decode is started.
- [x] Run geometry tests; mark physical text clarity unverified until MatePad test.

### Task 8: Active reader restoration and SAF recovery

**Files:** Modify MainActivity.kt and ComicReaderSession.kt. Create app/src/test/java/dev/tabletcomics/reader/ReaderRestoreStateTest.kt.

**Interfaces:** Save active URI, title, format and logical page in saved state. Reopen through existing persisted grant. Invalid grant/file clears reader state and offers folder reselection while preserving local progress.

- [x] Write tests: restoresActiveBookAndLogicalPage; restoredPageClampsToNewPageCount; invalidUriReturnsToLibrary; revokedGrantProducesReselectionState.
- [x] Verify tests fail against current Activity recreation behavior.
- [x] Save/restore active-reader inputs without changing per-comic preference keys.
- [x] Convert missing/revoked source into a recoverable library message.
- [x] Run lifecycle state tests; physical recreation/permission behavior remains unverified without a device.

### Task 9: Correct previews, scan results, and archive routing

**Files:** Modify ZipArchiveReader.kt and MainActivity.kt. Create app/src/test/java/dev/tabletcomics/archive/ArchivePreviewOrderTest.kt and app/src/test/java/dev/tabletcomics/library/FolderScanGenerationTest.kt.

**Interfaces:** Use Task 1 signature detection. ZIP cover selects the naturally first safe page. Cover key is (URI, size, lastModified). Folder scan generation prevents stale results from publishing.

- [x] Write tests: zipCoverUsesNaturalFirstPage for insertion order 10.png then 2.png; changedSizeInvalidatesCover; olderScanCannotPublishAfterNewerScan; extensionSignatureMismatchReturnsError.
- [x] Run focused tests and confirm insertion-order preview and stale scan behavior.
- [x] Match cover selection to reading order and invalidate changed-file cover.
- [x] Route by signature and report corrupt/mismatched files clearly.
- [x] Rerun archive preview and folder scan tests.

### Task 10: Limits, license notice, and release verification

**Files:** Modify README.md, ANDROID_COMIC_READER_GUIDE.md and MainActivity.kt. Add app/src/main/res/raw/unrar_license.txt and an About/license dialog reachable from the library. Create releases/0.4.3-notes.md and releases/tablet-comics-0.4.3.apk.

- [x] Update stale limit descriptions to 24 MiB per image entry, 8 GiB staged archive file, and 100 MP source image; search active README and guide for old conflicting values.
- [x] Package the exact UnRAR notice and verify it is reachable in the app and present in the APK.
- [x] Run :app:testDebugUnitTest, :app:lintDebug and :app:assembleDebug; all tests pass and lint has zero errors (5 warnings/information items remain).
- [x] Inspect APK version, ABI, packaged notice and file size.
- [x] Mark MatePad checks unverified until performed.

## Final review

- Every prioritized audit finding F1–F10 maps to one or more tasks.
- No new library/discovery features are added.
- Stored RAR4/RAR5 fixtures, over-budget dictionary errors, a solid-read scheduler sequence against a recording fake archive, cancellation, cleanup, size boundaries and recoverable errors are exercised.
- Review Focus cases have tests in their owning tasks.
- Device-only results are reported as unverified until a real MatePad run.

## Verification results

- `:app:testDebugUnitTest --offline`: 49 tests across 11 suites; 0 failures, 0 errors, 0 skipped.
- `:app:lintDebug --offline`: completed successfully with 0 errors; 4 warnings and 1 informational item (dependency updates, min-SDK resource qualifier, monochrome icon, and state-autoboxing suggestions).
- `:app:assembleDebug --offline`: completed successfully.
- APK: `dev.tabletcomics`, version 0.4.3 (code 9), min SDK 26, target SDK 35; arm64-v8a, armeabi-v7a, x86 and x86_64; 10,112,935 bytes; SHA-256 `7c1dbe4e19ce4e36e001eef6c99473f3d627a504a53e3ae511a99544813801b7`.
- `res/raw/unrar_license.txt` is present in the APK and byte-for-byte matches the source notice.
- Follow-up review fixes: ZIP preview scanning now stops on a shared 20 MiB uncompressed scan budget and never calls unbounded `closeEntry()` on an unprocessed entry; archive signature I/O stops after the initial header when the marker is at offset zero; the solid-RAR session uses the tested sequential executor; guide/release notes document cache-miss replay; both image decoders use `ArchiveLimits.MAX_IMAGE_PIXELS`.
- Independent follow-up review: no remaining Critical, Important or Minor findings. Real-device behavior, solid RAR cache-miss latency and cancellation exit during a real Junrar extraction remain unverified.
- HarmonyOS 4.2 / MatePad Pro installation, gestures, RAR performance, orientation changes and process restoration remain physically unverified.
