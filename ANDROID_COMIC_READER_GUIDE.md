# Android Tablet Comic Reader: Build Guide

This guide is for a coding agent implementing a personal, offline comic reader for a Huawei MatePad Pro running HarmonyOS 4.2. Deliver an installable APK. Build a native Android app with Kotlin and Jetpack Compose, and do not depend on Google Play Services.

The first release supports CBZ, ZIP, CBR, and RAR archives; JPEG, PNG, and WebP pages; local folders; saved reading progress; left-to-right and right-to-left reading; zoom; and optional two-page spreads in landscape.

## Product decisions

- Use Kotlin, Jetpack Compose, coroutines, and the Android Storage Access Framework. The current prototype stores per-comic settings and progress in local preferences; Room is not currently used.
- Target Android tablets. Verify the actual MatePad Pro model, HarmonyOS build, supported Android API level, and CPU ABI on-device. HarmonyOS 4.2 alone does not establish the API level or APK compatibility.
- Use Android ZIP APIs for ZIP and CBZ. Build the official RARLab UnRAR source (7.30 beta 1 in this build) into an Android native library for RAR and CBR; keep the included UnRAR license with the app and do not implement RAR archive creation.
- Keep the app offline and local. Do not add accounts, cloud sync, NAS access, ads, analytics, or a backend.
- Defer password-protected and multipart archives, PDF, EPUB, continuous vertical scrolling, and store publishing.

## Build sequence

### 1. Confirm device and archive feasibility

Create a minimal application that can be installed directly on the tablet. Record the device model, HarmonyOS version, Android API compatibility, supported ABI, and the exact build toolchain used. Avoid assuming the newest Android API is supported; select `minSdk` only after verifying the device.

Before building library screens, add a small archive spike that uses the production readers. Open representative ZIP/CBZ, RAR4/CBR, RAR5, and solid RAR samples, list image entries, and decode a page. Verify archive reads on the tablet, folder access remains granted after restart, and cancellation closes resources. Resolve engine or licensing problems here.

### 2. Add folder access and the library

Use Android's system folder picker. Persist each selected directory grant and walk its descendants in background work. Do not request broad storage permissions. Support internal storage and removable storage when exposed through the picker.

Use Room to store each book's document URI, display title, file size, modification time, page count, cover reference, last-read logical page, reading direction, and last-opened time. Use the persisted document URI as the book identity. If size or modification time changes, refresh the page index and cover while preserving progress, clamped to the new page count. Mark missing or inaccessible documents and offer a way to reselect a folder.

Show a tablet-friendly library with cover, title, progress, search, sorting by last opened/title, and a continue-reading action. Generate covers and scan files off the UI thread. Include manual refresh; do not add a background polling service.

### 3. Implement archive indexing and page access

Define one archive-reader interface shared by ZIP/CBZ and RAR/CBR implementations. It should open an archive, enumerate candidate image entries, provide a requested page, report progress, support cancellation, and release resources.

Detect the archive format from its contents; treat the extension as a hint only. Accept JPEG, PNG, and WebP entries. Sort by full entry path using natural numeric ordering (for example, `2.jpg` before `10.jpg`). Ignore hidden metadata directories and non-image files. Handle an empty archive and malformed, encrypted, multipart, or unsupported archive with a readable error.

For solid RAR files, keep the staged archive open and decode forward in logical order, with progress and cancellation. Keep only the current and nearby decoded pages in the session cache; a cache-miss seek can require replaying earlier solid entries. This release does not maintain a full extracted-page disk cache. For ZIP, retrieve individual entries as needed. If SAF access does not provide the random access required by the chosen library, stage a private working copy and remove it according to cache policy.

Never use an archive entry path as a filesystem destination. Use generated cache names and retain the original entry name only as metadata. Reject absolute paths, traversal components, and unsafe names even if an archive library claims to sanitize them.

### 4. Build the reader

Use a full-screen page view with single-page fit, tap and swipe navigation, pinch zoom, pan, double-tap width-fit zoom, a page slider, and readable loading or error states. A double-tap on a page expands it to the tablet width; a second double-tap or the Reset zoom control returns to fit-page view. While zoomed, a one-finger drag pans the image with increased but bounded movement gain; once the image reaches a horizontal edge, continuing the drag outward turns the page after touch slop. Decode images off the main thread using sampled decoding. Preserve the logical page when rotating the tablet or changing reading mode.

Default to left-to-right and save direction per book. In right-to-left mode, reverse gesture/navigation direction and the physical placement of pages; keep logical page order ascending.

Offer two-page spreads in landscape as an option. The cover is alone. Pair logical pages 2–3, 4–5, and onward; show an unmatched final page alone. Advancing moves by one page in single-page mode or one spread in spread mode. When saving spread progress, save the first logical page shown.

Prefetch only the next reading unit. Bound the decoded bitmap session budget to the lower of 40 MiB or one-sixth of the app's maximum heap, with additional headroom for temporary decode work. Sample images to fit the tablet. The current zoom uses the sampled bitmap and does not allocate a second higher-resolution copy.

### 5. Add resource limits and recovery

Use these initial safety limits and report a clear, recoverable error when one is exceeded:

- 20,000 archive entries and 20,000 supported image pages per comic.
- 24 MiB maximum uncompressed size for an individual image entry. ZIP cover discovery scans at most 20 MiB of uncompressed entry data; if it cannot establish the natural first page within that budget, it returns no preview.
- 100 megapixels per source image.
- 8 GiB maximum size of the staged archive file. This is not a limit on the sum of uncompressed page contents.
- Keep at least 1 GiB of free device storage while staging an archive.
- Limit native UnRAR dictionaries to one-third of currently available system memory, capped at 1 GiB. An archive that exceeds the current limit reports a recoverable page error.
- Remove only generated app-owned ZIP/RAR staging files that are inactive and older than seven days at startup. Never delete the user's source comics.

Handle revoked folder access, deleted or changed archives, low storage, corrupt archives, unsupported codecs, cancellation, and app process death without crashing. Save reading position after each navigation action and restore it on reopen.

## Suggested structure

Keep UI, persistence, archive access, and image decoding separate. One reasonable package layout is:

```text
app/
  data/          Room entities, DAO, repository, folder scanner
  archive/       ArchiveReader interface, ZIP reader, RAR/JNI adapter
  reader/        Reader state, page/spread mapping, image loading
  library/       Library screens and view models
  platform/      SAF access, cache management, device diagnostics
```

Use small interfaces at the storage and archive boundaries so the reader UI does not depend on a particular archive library. Keep archive work on background dispatchers and make cancellation close streams and native handles.

## Verification checklist

Run these checks throughout implementation and repeat the device checks on the MatePad Pro before delivery:

- Install, launch, and reopen the APK without Google Play Services.
- Open ZIP/CBZ, RAR4/CBR, RAR5, and solid RAR samples using the production archive engine.
- Check natural sort order, nested image folders, mixed supported image formats, and ignored metadata.
- Check LTR and RTL gestures, spread placement, standalone cover, odd page count, page slider, zoom, and rotation.
- Restore the book and page after process termination; retain progress after archive refresh.
- Exercise revoked permissions, missing files, corrupt/encrypted/multipart input, unsafe archive paths, oversized entries, low storage, and cancellation.
- Read a large comic without loading every page into memory or blocking interaction.

Use small synthetic fixtures for automated archive and page-mapping checks. Do not include copyrighted sample comics in the repository. Provide instructions for supplying local test archives and record the device checks that were actually performed; do not claim physical-device validation without the device.

## Delivery

Provide the source project, reproducible build and APK installation instructions, dependency and license notices (including native archive components), and a debug APK for personal installation. Include a short note with the exact tablet model, HarmonyOS version, Android API compatibility, and archive formats verified. If the device or an archive format is unavailable for testing, state that clearly.
