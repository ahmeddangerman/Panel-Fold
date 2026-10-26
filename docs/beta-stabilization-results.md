# Beta 0.6.1 stabilization results

This implements the fixes in the historical [25 October audit](final-beta-audit-2026-10-25.md), plus the four additions approved by the user. Original page bytes remain unchanged. The app stays in beta, version code 17.

## What changed

| Audit area | Implementation |
|---|---|
| R1/R5: zoom ROI, incomplete tiles, seams | Shared fit/crop/drawing geometry and centered inverse transform; adaptive sampled tiles cover all visible regions across a spread; shared floating boundaries. |
| R2/L1: original-file eviction and startup race | Per-open source directories and decoder leases; every page in retained units is preserved; startup removes only inactive directories. |
| R3/R4: false finish and mid-swipe rollback | Actual end determined by page count; present and absent neighbors are frozen consistently during a gesture; settled frames wait for parent acknowledgement. |
| R6/L2: obsolete tile work and old-book thumbnails | Serialized cancellable decode batches; epoch/document-checked thumbnail cache publication. |
| U1/U2: collection trap and unreachable shelves | Visible scope label and All comics action; Library navigation clears scope; collections use a lazy scrolling list. |
| U3/U4: navigation state | Saveable destination content preserves library position while reading; restart captures the origin; superseded async open requests cannot steal navigation. |
| U5: recently added | New discovery time is separate from source modification time. |
| A1: solid RAR replay/cancellation | Persistent forward extraction cursor, staged-byte reuse, cancellation during packed reads and discarded solid output. |
| A2: duplicate image paths | Actionable rejection rather than silently repeating one page and losing another. |
| A3/A5: SFX ZIP and preview entry work | Bounded leading-stub alignment; empty entries count toward the entry ceiling; best found preview retained at byte budget. |
| A4: native window ceiling | Explicit caller limit, including the engine's allocation floor. |
| Release alignment | Flexible/16 KiB ELF linker configuration and packaged-library inspection. |
| Update signing | Both old and current private keys recovered and preserved outside Git; signed certificate lineage for Android 9+ transition. |
| Persistence review follow-ups | Ordered progress reset/open/import/export; portable RTL/spreads; authoritative SAF relocation honors removed preferences/bookmarks/memberships. |

## Added tools

- **Settings → Reading data:** export or restore a bounded, versioned JSON backup of progress, favorites, bookmarks, custom collections and reading preferences. Restoring merges validated data; folder permissions and comic files are selected separately. Unique title/folder/format matching supports relocated SAF trees. Ambiguous records remain pending instead of applying to the wrong book.
- **Reading options:** whole page / fit width / fit height; conservative plain-border trimming; per-comic keep-zoom toggle. Zoom starts at the top and keeps the chosen relative percentage when enabled.
- **Settings → Gesture feel:** pan sensitivity and momentum, including momentum off.
- **Comic details → Cover artwork:** select a page, retry loading, or restore the default. **Settings → Cover storage:** inspect size and clear generated covers.

## Verification

Final shared Gradle run: **BUILD SUCCESSFUL in 1m 33s**, 139 actionable tasks (45 executed, 94 up-to-date). Debug/release APKs, debug lint, the full JVM suite and the instrumentation APK were built from the final source.

- **125 JVM tests in 26 classes passed**, with zero failures/errors/skips.
- Debug lint: **zero errors, seven warnings and one information item**. Release lint-vital checks passed.
- **23 Android instrumentation cases compiled and packaged**, but were not executed because no device/emulator is connected.
- Native host regressions passed compressed and solid RAR4/RAR5, cursor reuse, backward reads, skip cancellation, small budgets and concurrent close. The standalone 21 archive JVM tests also passed (these overlap the full suite).
- Signed beta APK verified with v2/v3 schemes and explicitly at API26,28,31,35. The embedded lineage contains both recovered certificates with installed-data capability.
- All packaged ARM64/x86_64 native-library LOAD segments inspected at **0x4000 (16 KiB)**, and signed-APK `zipalign -c -P 16 4` passed.
- Package metadata: `dev.tabletcomics`, **versionCode17**, **0.6.1-beta**.

See [verification evidence](beta-stabilization-verification.txt).

The source integration was independently reviewed in [stabilization-review.md](stabilization-review.md). Its important findings were fixed before the final rerun.

Real Android tests were added for reader gestures/loading, collection navigation/scroll restoration, PNG region decoding, database migration/backup/SAF relocation, and JNI compressed/solid archive behavior. Building that APK does not mean these tests were executed.

## Signing and remaining limits

The recovered legacy certificate is `af83e47650b7ce108b9c74749e02f7e444f72e18fcf7a3901d96e45463ee727e`; the current certificate is `a751cfc1f12ff43c69f4f322bb412349cb9a548b0d6a474887ec98ed6b5b45a9`. `scripts/sign-beta.sh` uses the original signer on Android 8.x and a proven legacy-to-current certificate lineage from Android 9 onward. These are development signing credentials, not a production store release. Keep both private keys and the lineage securely backed up outside Git. A current-key-only debug APK remains available for older Android installations that used that key.

No tablet or emulator is connected. In-place install/update, touch feel, crop choices on varied real comics, SAF provider behavior, and measured Java/native/GPU memory remain device checks. A single native bitmap decode cannot be interrupted midway; cancellation is observed as it returns, and replacement batches wait. SQLite and SharedPreferences cannot guarantee one crash-atomic transaction across process termination, although validation and rollback protect ordinary failures.

The source is a local Git repository. No GitHub remote was supplied, so there is no remote backup claim. Unrelated attachments and generated documents are preserved outside the source commit.

Implementation details: [reader](reader-implementation-report.md), [session](session-implementation-report.md), [archives](archive-implementation-report.md), [backup](backup-implementation-report.md).
