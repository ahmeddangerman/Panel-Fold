# Reader session stabilization

Scope: audit R2, R6, L1 and L2 in `docs/final-beta-audit-2026-10-25.md`.

## Interfaces for integration

- `ComicReaderSession.decodeRegions(source, regions, sampleSize: Int = 1)` now accepts the renderer's requested sample. The session rounds up to a positive power of two (maximum `1 shl 30`). MainActivity must forward the third callback argument.
- `cleanupInactivePageSourceDirectories(context: Context)` must run on IO instead of recursively deleting `cacheDir/comic-page-source`. It preserves every live per-open directory, including a closed session whose decoder still holds a source lease.
- `ReaderUnit`, `FullResolutionPage`, archive interfaces, reading quality and original archive bytes are unchanged.

## Implemented behavior

Each open comic receives a `SessionSourceStore` with its own UUID directory. Detach captures that specific store for cleanup; cleanup from an old open cannot clear the sources of a later open. The store registers a directory before publishing any sources. Startup cleanup and store registration share a lock, preventing the restore/startup deletion race. Closed stores remain registered until all decoder leases have released their files.

The retained source set comes from the page IDs of every retained cached `ReaderUnit`. It includes both halves of neighboring spreads and a cached previous solid-archive unit. Eviction removes availability immediately and delays file deletion until active leases finish. Sources from a different open cannot be acquired by matching only their page index.

`PageRegionDecoder` serializes entire batches through a coroutine mutex. Cancellation is checked before decoder creation, before every region, and immediately after each synchronous region decode. Canceled and failed batches recycle their unpublished output; the decoder and source lease always close. A replacement waits until the canceled synchronous call returns. Rectangle clipping checks `Rect.intersect`'s Boolean result, so a fully external rectangle returns null. Sample sizes are respected when decoding.

Page-unit and thumbnail reads first reuse healthy staged original bytes. This avoids rereading/replaying an archive for pages already staged, including preference changes that rebuild a unit. A missing or truncated staging file falls back to archive reading. Staged originals are written once without image re-encoding or lossy compression.

Thumbnail publication rechecks cancellation, epoch and archive identity after synchronous decode. `SessionThumbnailCache` atomically validates an epoch/document key and publishes under one lock; reset cannot race with an old insertion. Old-book and previous-open keys cannot retrieve current-book thumbnails. Rejected bitmaps are recycled. The cache remains bounded to 2 MiB.

## Regression coverage

- `SessionSourceStoreTest`: all pages of retained spread units; eviction while a decoder lease is active; unchanged staged bytes and truncated-file fallback; startup cleanup with two live stores; old close versus new open at the same index; foreign-source lease rejection.
- `RegionDecodeSampleTest`: negative, exact, rounded-up and overflowing sample requests.
- `RegionDecodeCancellationTest`: a blocked synchronous decode followed by cancellation and a replacement; no overlapping decode; cancellation skips the remaining regions and discards the completed obsolete result; partial-batch failure discards prior results.
- `SessionThumbnailCacheTest`: late old-book insertion, reopening the same document with a new epoch, closed cache rejection and byte-budget LRU eviction.
- `PageRegionDecoderInstrumentedTest`: real Android PNG region decoding, fully external and partially clipped rectangles, power-of-two sampling, original byte identity and rejection after eviction.

Verification available at handoff: `git diff --check` passed for the session changes. Shared Gradle compilation/JVM tests and Android test APK assembly are owned by root integration. This worker did not run Gradle or claim device execution.

## Remaining device checks

BitmapRegionDecoder cannot interrupt a single native synchronous decode; cancellation takes effect when that call returns, while the mutex prevents a replacement batch from allocating concurrently. Validate pan/zoom responsiveness, actual peak Java/native/GPU memory, book switching during thumbnail decode and rapid spread/solid navigation on a tablet. The instrumented regression requires Android; no device result is claimed here.
