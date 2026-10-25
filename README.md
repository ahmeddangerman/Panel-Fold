# Tablet Comics

An offline Android tablet comic reader written in Kotlin and Jetpack Compose.

## Open and build

Open this directory in Android Studio with JDK 17. Install Android SDK Platform 35, NDK 27.2.12479018, and CMake 3.22.1. The Android Gradle Plugin is pinned to 8.7.3, and the Gradle 8.9 wrapper is included. Build the `app` debug variant with `./gradlew :app:assembleDebug` or from Android Studio.

The version 0.6.0 debug APK is available at [releases/tablet-comics-0.6.0.apk](releases/tablet-comics-0.6.0.apk). It targets Android API 35, supports devices from API 26, and includes the Android build's supported ABIs. MatePad Pro / HarmonyOS behavior has not been verified on a physical device. To try it, transfer the APK to the tablet and open it, allowing installation from that source when prompted.

## Features

- Select and retain access to a folder through Android's folder picker.
- Scan nested folders for CBZ, ZIP, CBR, and RAR archives.
- Read ZIP/CBZ with Android's ZIP support and RAR/CBR with the native RARLab UnRAR 7.30 beta 1 engine, including RAR5 and RAR7 archives.
- Reveal ready neighboring pages while dragging. Choose Photo swipe (default), Paper curl, or Sliding sheet per comic; fast flicks and halfway drags complete naturally, while short drags return. Pinch to zoom and pan.
- Enable two-page landscape spreads, with the cover shown alone and RTL spread placement.
- Show a tablet-friendly cover grid with adjustable cover sizes, reading status and page progress on every cover.
- Resume one of the ten most recently read unfinished comics from the top row; filter by All, Reading, Unread, Finished or Favorites; search and sort by recency, title or progress.
- Group comics into automatically discovered folder collections or custom collections. Long-press a cover to favorite it, change its finished status, reset progress, start over or manage collections.
- Save progress, bookmarks and library state in SQLite, importing existing per-comic page positions the first time the new library opens.
- Open nearby page previews, jump to bookmarks, keep the screen awake, and optionally show a thin reader progress line.
- Offer a finish prompt at the end of a comic, with next-issue selection by custom collection or source folder and a confirmation before opening it.
- Cache cover previews on disk with a 128 MiB limit; choose violet, teal or amber accents.
- Show the app logo in the library header and use it as the Android launcher icon.
- Enforce a 24 MiB uncompressed image-entry limit, an 8 GiB staged archive-file limit, 20,000 archive entries and pages, and a 100-megapixel source-image ceiling. The 8 GiB cap applies to the copied archive file, not the sum of unpacked pages.
- Keep at least 1 GiB free while staging, limit native RAR dictionaries to one-third of currently available system memory up to 1 GiB, and remove inactive generated staging files older than seven days at startup.

Library metadata and reading progress use Android's built-in SQLite support; lightweight preferences remain for reader settings and compatibility with saved positions. Encrypted RAR archives are reported as unsupported. Native UnRAR dictionary use is bounded to one-third of currently available system memory, capped at 1 GiB, leaving memory for Android and page rendering. ZIP cover discovery scans at most 20 MiB of uncompressed entry data; if the natural first page cannot be found within that budget, the library shows no cover preview. This is separate from the 24 MiB page-entry limit. The reported story has not been tested with its original archive, so please retry it with 0.6.0 to confirm the native engine resolves its dictionary requirement. UnRAR is distributed under its included license; see [UNRAR_LICENSE.txt](third_party_licenses/UNRAR_LICENSE.txt) or open **About & licenses** in the library. This project reads and extracts existing RAR archives and does not create RAR archives.

See [ANDROID_COMIC_READER_GUIDE.md](ANDROID_COMIC_READER_GUIDE.md) for the full requirements, implementation order, and device verification checklist.
