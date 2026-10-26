# PanelFlow

An offline Android tablet comic and manga reader written in Kotlin and Jetpack Compose.

## Open and build

Open this directory in Android Studio with JDK 17. Install Android SDK Platform 35, NDK 27.2.12479018, and CMake 3.22.1. The Android Gradle Plugin is pinned to 8.7.3, and the Gradle 8.9 wrapper is included. Build the `app` debug variant with `./gradlew :app:assembleDebug` or from Android Studio.

The current source is beta **0.6.2**. The signed APK and release notes are on the [GitHub Releases page](https://github.com/ahmeddangerman/Panel-Fold/releases). Build/install verification for the previous beta is recorded in [the stabilization results](docs/beta-stabilization-results.md). It targets Android API 35 and supports devices from API 26. MatePad Pro / HarmonyOS behavior has not been verified on a physical device.

## Features

- Select and retain access to a folder through Android's folder picker.
- Scan nested folders for CBZ, ZIP, CBR, and RAR archives.
- Read ZIP/CBZ with Android's ZIP support and RAR/CBR with the native RARLab UnRAR 7.30 beta 1 engine, including RAR5 and RAR7 archives.
- Reveal ready neighboring pages while dragging with the Photo swipe turn; fast flicks and halfway drags complete naturally, while short drags return. Double-tap zooms in to page width (or 2× when the page already fills the width), and pinch to zoom and pan.
- Export and restore portable JSON backups of progress, bookmarks, favorites, collections and settings through Android document pickers. Imports validate before merging and defer unmatched comics until their folder is selected.
- Choose per-comic whole-page/width/height fit, conservative plain-border trimming and zoom retention. Adjust pan sensitivity and momentum in Settings. Original image data is unchanged.
- Retry a cover, select a cover page, restore its default, and inspect/clear the cover cache.
- Enable two-page landscape spreads, with the cover shown alone and RTL spread placement.
- Remember zoom percentage per comic across page turns and reopening; zoomed pages begin at the top. Double-tap zoom-in also aligns the page to the top.
- Choose a saved light or dark appearance in Settings, with readable accent colors and reader controls in either mode.
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

Library metadata and reading progress use Android's built-in SQLite support; lightweight preferences remain for reader settings and compatibility with saved positions. Encrypted RAR archives are reported as unsupported. Native UnRAR dictionary use is bounded to one-third of currently available system memory, capped at 1 GiB, leaving memory for Android and page rendering. ZIP cover discovery scans at most 20 MiB of uncompressed entry data and 20,000 entries; if the scan budget runs out, it retains the best cover found so far. This is separate from the 24 MiB page-entry limit. Compressed and solid RAR4/RAR5 engine regressions are covered by the native host suite; Android JNI and HarmonyOS still require device verification. UnRAR is distributed under its included license; see [UNRAR_LICENSE.txt](third_party_licenses/UNRAR_LICENSE.txt) or open **About & licenses** in the library. This project reads and extracts existing RAR archives and does not create RAR archives.

See [ANDROID_COMIC_READER_GUIDE.md](ANDROID_COMIC_READER_GUIDE.md) for the full requirements, implementation order, and device verification checklist.

## Beta signing and source backup

The current debug signer is preserved in the Git-ignored `.signing/current.keystore`; the recovered historical signer is `.signing/legacy.keystore`. `scripts/sign-beta.sh` signs an unsigned release APK with a certificate lineage for the recovered legacy-to-current transition, using the current signer on Android 9+ and the original signer on older supported releases. The keys are development keys, not a production distribution credential. Back up both private keys and their lineage securely outside this workspace; they are deliberately excluded from Git. Do not generate replacement keys for an installed app. Actual in-place updates still need device verification.

Build with JDK17/21 and the SDK/NDK versions above, then run `scripts/sign-beta.sh` after `:app:assembleRelease` to generate the signed beta. To use an independent release credential, supply `TABLET_COMICS_KEYSTORE`, `TABLET_COMICS_STORE_PASSWORD`, `TABLET_COMICS_KEY_ALIAS` and `TABLET_COMICS_KEY_PASSWORD`; the Gradle release signing config reads these environment variables. No passwords or private key bytes are stored in source.

## License

Original PanelFlow source is offered under the [PolyForm Noncommercial License 1.0.0](LICENSE). You may use, modify, and share it for noncommercial purposes. Commercial use requires a separate license from the copyright holder. This is source-available software, not an OSI-approved open-source license. Third-party components remain under their own licenses; see [third_party_licenses](third_party_licenses/).

The source repository is [github.com/ahmeddangerman/Panel-Fold](https://github.com/ahmeddangerman/Panel-Fold). Build and device verification details are in [the stabilization results](docs/beta-stabilization-results.md).
