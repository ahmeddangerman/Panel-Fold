# Archive stabilization implementation

Scope: audit findings A1–A5 and the native 16 KiB ELF alignment gap in
`final-beta-audit-2026-10-25.md`. The existing ArchiveReader/OpenArchive public
interface and NativeUnrar list/extract compatibility methods remain stable.

## Changes

**A1 — solid RAR cursor and cancellation.** RarArchiveReader owns one native
extraction session for the lifetime of its staged archive. Image pages carry
their physical header ordinal internally, independently of natural display
order. Forward reads keep the existing UnRAR cursor and solid dictionary;
backward reads reconstruct from the start. Failed or cancelled extraction
discards partial dictionary state before any subsequent request. Original page
byte reuse belongs to the reader-session cache and requires no archive API
change.

The engine emits a separate cancellation-only callback during packed input
reads and discarded solid output. `RAR_SKIP` continues to suppress output data
callbacks, so skipped pages cannot be appended to the requested image. Kotlin
cancellation exceptions remain pending through JNI and are rethrown unchanged.
An atomic cancellation flag lets cancellation and close interrupt an extraction
without taking its engine lock. A handle registry with shared ownership avoids
use-after-free when close races a read. Kotlin serializes reads per session;
native engine calls serialize globally because UnRAR's error handler is global.
Close releases the native handle before deleting the staged archive.

**A2 — duplicate image names.** ZIP and RAR readers reject exact duplicate
supported-image filenames with an explanation to rename the duplicate files
and recreate the archive. Streaming ZIP previews reject duplicates encountered
inside their scan budget. The retained name-based NativeUnrar.extract method
also rejects an ambiguous requested filename. RAR's production session uses
ordinals internally. This deliberately avoids silently repeating or omitting
different pages with the same name.

**A3 — SFX ZIP previews.** Preview scanning reads the same bounded detection
prefix used by format detection, skips the leading stub to the first ZIP local
header, and replays any already-read ZIP bytes before continuing the original
stream. It keeps the existing 20 MiB unpacked preview budget and does not stage
an entire archive just to obtain a cover.

**A4 — dictionary ceiling.** A small explicit DLL extension sets both engine
window thresholds to the caller's budget, removing the implicit 32 MiB
allowance. The pre-allocation check includes the engine's actual minimum
256 KiB unpack window. Stored entries need no unpack dictionary. A session
releases an already-retained dictionary if a later request supplies a smaller
budget. The budget bounds the unpack window; ordinary engine/JNI/output-buffer
overhead is separate from that window.

**A5 — empty ZIP entries.** Streaming previews count every entry, including
empty files and directories, and enforce the same 20,000-entry cap as the full
reader. Per-entry cancellation and decompressed-byte bounds remain in place.

**16 KiB alignment.** CMake enables `ANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES` and
passes `-Wl,-z,max-page-size=16384` plus `-Wl,-z,common-page-size=16384` directly
to the shared-library linker. The explicit flags cover NDK r27 configurations
whose toolchain options are evaluated before this project file. Final APK ELF
and ZIP packaging verification belongs to the root release build.

## Internal native interface

No MainActivity or reader-session call sites need modification. The internal
Kotlin API used only by RarArchiveReader is:

```kotlin
NativeUnrar.openSession(archivePath: String): NativeUnrar.Session
Session.extract(
    entryOrdinal: Int,
    maxPageBytes: Long,
    maxDictionaryBytes: Long,
    cancellationCheck: () -> Unit,
    registerCancellation: ArchiveCancellationRegistrar = ArchiveCancellationRegistrar.NONE,
): ByteArray
Session.close()
```

JNI adds `nativeOpenSession(String): Long`, `nativeReadSession(Long, Int, Long,
Long, Function0): ByteArray`, `nativeCancelSession(Long)`, and
`nativeCloseSession(Long)`. Handles are opaque registry IDs. A session must be
closed by its OpenArchive owner. The existing `nativeList(String)` and
`nativeExtract(String, String, Long, Long, Function0)` symbols are preserved.
`rar_session.cpp` contains the same extraction implementation exercised by the
host probe, rather than a second extraction implementation written for tests.

The vendored changes are listed in
`app/src/main/cpp/unrar/TABLET_COMICS_PATCHES.md`.

## Verification performed by the archive worker

- `app/src/test/native/run.sh` passed against compressed RAR4 and RAR5 plus
  solid RAR4 and RAR5 fixtures. It checks output size and engine CRC acceptance,
  forward cursor reuse, backward reads, cancellation inside the first skipped
  entry with zero skipped-output callbacks, recovery after cancellation,
  rejection of a 64 KiB dictionary budget, and close racing an active read.
- Solid RAR4 used 9 checks for the retained forward cursor versus 68 checks
  for a new cursor replaying the same target. Solid RAR5 used 6 versus 31.
- The first host run exposed repeated calls after a cancellation predicate
  returned false; cancellation is now latched and the complete probe passes.
- JNI source passed `g++ -std=c++11 -DRARDLL ... -fsyntax-only` using a copied
  standalone Android JNI header with host system headers.
- All archive Kotlin production sources compiled independently with the cached
  Kotlin 2.0.21 compiler, Android 35 API stubs and coroutines 1.9.0. JUnitCore ran
  ArchiveFormatTest, ArchivePreviewOrderTest, RarArchiveReaderTest and
  StagedArchiveTest: **21 tests passed**. Evidence:
  `/tmp/archive-jvm-results.log`. This used a separate `/tmp` output directory
  and did not invoke Gradle or modify the shared app build outputs.
- Archive changes pass `git diff --check`.

JVM coverage adds SFX ZIP preview, empty-entry count enforcement, and duplicate
image-name failures. Android instrumentation adds real JNI compressed/solid
RAR4/RAR5 extraction, cursor reuse, preserved cancellation exceptions,
dictionary rejection/recovery, and duplicate-name rejection. The root worker
runs shared Gradle tasks; these new instrumentation tests require a device or
emulator to execute. No on-device execution is claimed here.
