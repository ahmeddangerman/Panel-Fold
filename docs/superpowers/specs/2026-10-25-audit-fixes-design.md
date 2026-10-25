# Tablet Comics audit fixes — design

## Intent

Make the existing Android comic reader safer and more predictable before starting the separate cover-library and discovery work. The user chose audit fixes before new features. This design covers the ten prioritized findings in [the audit](/workspace/docs/app-audit-2026-10-25.md); it does not include the library redesign ideas.

## Goals

- Bound RAR decompression memory by a tablet-safe app budget and fail with a readable page error instead of risking heap exhaustion.
- Make archive staging, page reads, covers and scans cancel promptly; keep active reader work responsive when library previews are in progress.
- Make ordinary forward reading of solid RAR archives sequential, avoid duplicate current-page decode, and retain already decoded nearby pages. Random seeks may still require Junrar replay; measure and report that limit.
- End every gesture path cleanly, including a zero-distance release, boundary swipe, cancelled gesture, orientation/style change and completed fast turn. Keep the preview and committed page identical.
- Make seek/loading state visible, debounce slider work until release, ignore duplicate destinations, and show a useful recoverable error.
- Clamp zoom/pan to the page, provide a clear reset gesture/action, and request extra image detail only within the bitmap budget.
- Restore the active comic and logical page after process recreation when its SAF grant remains valid; provide a recoverable path when access has been revoked.
- Sweep inactive app-owned staging files after an interrupted run; keep source documents untouched.
- Select ZIP/RAR handling from archive signatures with extensions as hints, align library covers to the first naturally sorted reading page, invalidate changed-file covers, and prevent stale folder scans from overwriting newer ones.
- Align user-facing archive limits with enforced limits and package the Junrar notice.

## Non-goals

The cover grid, progress bars, Continue Reading shelf, search, filters, collections, bookmarks, thumbnail navigation, file-manager open intents, new archive formats, sync, accounts, network access, and visual redesign stay in the next feature phase. This work does not claim MatePad compatibility until tested on the actual device.

## Constraints

Keep Kotlin, Jetpack Compose, Android Storage Access Framework, offline behavior, existing ZIP/Junrar engines, and the current photo/curl/sheet reader. Retain each comic's progress and settings. Preserve existing page limits unless the project guide and implementation are deliberately brought into alignment. No source comic may be deleted. The MatePad is not attached in this environment, so source tests and local fixtures cannot substitute for physical validation.

## Approach

**Chosen: repair the current architecture in focused groups.** Put resource bounds and cancellation at the archive boundary, fix solid-RAR scheduling/cache behavior in `ComicReaderSession`, make turn and seek state explicit in the Compose reader, and correct library scan/cover metadata. This limits regression scope and keeps the user's approved order.

A reader/archive rewrite could simplify longer-term ownership, but it would replace working code while solving several localized problems and would make regressions harder to isolate. Building the new library first would leave reported crash and navigation risks in place. The existing dependency and platform stack remain suitable for this pass.

## Design decisions

- Set Junrar's dictionary ceiling to `min(32 MiB, maxHeap / 10)` and check it before extraction allocates the window. If Junrar cannot honor the ceiling for a file, report that page as unsupported by the current memory limit. Keep decoded-page memory accounting separate and avoid retaining raw page byte arrays after decode.
- Make SAF copy and archive read loops coroutine-aware, close streams on cancellation, and ensure cancelled cover work releases the shared archive lock. Folder scans use a cancellable job/token. Do not cancel a synchronous native/JVM section by thread interruption alone; close its source/pipe and verify that the worker exits.
- Add a solid-archive capability to the archive abstraction. For solid RAR, decode a requested current unit once at display resolution, fetch only the next logical unit for forward prefetch, reuse the previous unit already in the session cache, and do not reread current at a second resolution. Preserve compressed/source archive ownership until close. Random-access cache misses may replay entries; disk caching every extracted page is out of scope for this pass.
- Represent an active gesture with one source/target snapshot and one cleanup path. Capture the target at release, animate the captured unit, and commit that same unit. Clear state in `finally` and on every zero-distance or cancellation path. Do not accept a second turn until settling finishes; provide visual feedback while it settles.
- The slider tracks its own draft index while the thumb moves and requests one destination on release. Show progress for that requested destination while the previous bitmap remains visible; show a retry/return action on load failure.
- Bound pan to the visible fitted page and reset with double-tap and a visible control. Keep pinch zoom. On zoom, request a larger sampled bitmap only if the decoded-cache budget allows it; otherwise retain the current bitmap and communicate that zoom detail is limited.
- Persist active URI and logical index with saved state, then reopen through the persisted SAF grant. If the grant or file is no longer valid, return to the library with a reselection message instead of crashing.
- On startup, delete only old inactive files with this app's generated staging prefixes. Keep a process-local registry for active staging files; use age and ownership checks to avoid racing cleanup with an open archive.
- Read the archive signature before dispatch. Reject extension/signature mismatches with an actionable message. For ZIP previews, select the naturally first safe image. Key cached previews by URI plus size and modification time. Ignore results from stale folder scans.
- Do not expand the existing 24 MiB entry or 8 GiB staged-file limits in this reliability pass. Update the guide and README to describe the enforced values and explain that the 8 GiB cap applies to the staged archive, not total unpacked image bytes. Keep the separate 100 MP decoded-image ceiling.
- Include the Junrar UnRAR license text in an app-accessible notice and retain the source notice.

## Acceptance checks

- Unit tests cover RAR memory-budget calculation, archive signature classification, natural cover selection, progress clamping and turn mapping.
- Archive tests use local synthetic ZIP, RAR4 and RAR5 fixtures for stored and compressed pages where available; include a synthetic solid sequence and verify extraction order, cancellation, cleanup, byte caps and readable errors. Do not add copyrighted comics.
- Gesture/UI tests cover release at zero, boundary swipes, fast repeated turns, reversed turns, neighbor publication during a drag, rotate/style change while settling, cache miss and failed seek. Verify the previewed page equals the committed page.
- Lifecycle tests cover process restoration, valid SAF grant, revoked grant and deleted source.
- `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleDebug` pass; APK metadata and notice are inspected.
- On the MatePad: repeat rapid turns in all three styles, single and spread modes, zoom/pan, seeks, rotation, repeated book switching, low storage, and representative CBR/CBZ files. Record model, HarmonyOS build/API compatibility, sample archive features, crashes and latency. If device tests are unavailable, mark those checks unverified.

## Implementation ownership

The implementer may settle internal class names and test fixtures. Any need to change supported archive formats, the current user-visible limits, or the user's choice to defer UI features returns for discussion.
