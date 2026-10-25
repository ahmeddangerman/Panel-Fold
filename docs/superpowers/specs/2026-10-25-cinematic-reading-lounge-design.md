# Tablet Comics: Cinematic Reading Lounge

## Intent

Opening Tablet Comics should invite the reader back into a story. Replace the current toolbar and stacked card layout with a focused reading Home, a separate cover-first Library, and a reader whose controls stay out of the way. The design targets tablet use first and adapts to portrait windows and larger text.

Approved visual reference: `generated_images/exec-ffabfc0b-21a5-491d-9bd4-3d2e94de65d4.png`. Its comic art and titles are illustrative placeholders; the app uses only the reader's own comic covers and known metadata.

## Goals and boundaries

- Make resuming the latest unfinished comic the clearest action on Home.
- Make browsing, filtering, organizing and adjusting settings distinct from the reading home.
- Keep comic pages full-size, with compact reader controls overlaid when requested.
- Reuse existing progress, favorites, finished state, collections, bookmarks, page previews and reader settings without a storage migration.
- The user is handling engine changes. This design does not change archive handling or reader gesture physics. It also excludes AI, accounts, network features, generated artwork, autoplay and invented story descriptions.

## Considered directions

- **Reading lounge — chosen.** A cover-led Home focused on continuing a story, with a separate Library. It gives the app a clear reading purpose while keeping the full collection available.
- **Cover gallery.** Opens directly to all covers. This is retained as the Library destination, but makes returning to an in-progress story less immediate.
- **Editorial collection.** Uses magazine-like asymmetric artwork and expressive typography. Dropped because the extra visual treatment competes with the comic covers.

## Screen behavior

### Home

- At app launch, show Home unless Android is restoring an active reader session.
- Feature the most recently read unfinished comic with a complete cover, title, saved page and percentage when known, a prominent **Resume reading** action, and a secondary Details action.
- Beneath it, show up to eight other unfinished recent comics under **On your reading shelf**, excluding the featured comic.
- With no unfinished reading history, feature the latest added unread comic with **Start reading**. If all comics are finished, feature the latest comic with **Read again**. With no comics, show a folder-selection welcome.
- Tint only the Home backdrop using a restrained color sampled from the cached cover; use a neutral gradient if no cover is available. Do not generate or fabricate cover or story content. The feature is static, with no carousel or autoplay.

### Library and Collections

- Library is a separate adaptive cover gallery. Covers remain the visual focus, with titles, status and reading progress below. Preserve adjustable cover size.
- Keep All, Reading, Unread, Finished and Favorites filters. Search expands from an icon into an inline field. A display-options sheet contains the current sort choices and cover-size control.
- A tap on a comic resumes it. Long-press and an accessible overflow action open comic details.
- Collections is a dedicated screen for existing folder groups and custom collections. Selecting a collection opens its scoped Library view. Keep empty custom collections visible and allow collection creation.
- Comic details use an artwork-led sheet with known metadata, progress, Resume/Start, favorite, finished status, reset/restart and collection membership. Preserve the existing meanings of reset progress and start from beginning.

### Reader

- Fit the page to the full reader viewport without cropping it. Keep controls hidden by default. A single tap reveals the controls; existing turn, zoom and pan behavior stays in place.
- The top overlay contains Back, the comic title and bookmark. The slim bottom dock contains previous/next, progress seeking, page count, Pages and Options.
- Pages opens an on-demand panel with nearby thumbnails and bookmarks. Options contains reading direction, landscape spreads, the three turn styles, keep-awake, optional thin progress, and the existing animated reset-zoom action.
- Remove the persistent reset-zoom pill. Panels overlay the page, capture their own touches and never alter the page viewport. Back dismisses an open panel, then visible controls, then returns to the originating screen and position.
- At the last page, show the existing finish decision with the comic cover, next-issue option when available, and return to library. Reaching the last page alone does not set Finished.

### Navigation and settings

- At 840dp width and above, use an 80dp navigation rail. At narrower widths use bottom navigation. Destinations are Home, Library, Collections and Settings; hide application navigation while reading.
- Settings becomes a full screen grouped into Appearance, Reading, Library and About. Preserve accent, cover size, reader progress, keep-awake, folder replacement, refresh and license details. Folder replacement is labeled **Change folder**.
- Returning from the reader restores the originating destination and scroll position. Preserve existing process-death reader restore behavior.

## Visual and implementation decisions

- Use charcoal `#0B0D13`, raised surfaces `#151922`, warm ivory primary text and muted secondary text. Existing accent preferences color functional controls and progress; cover tint affects only the Home backdrop.
- Use expressive system-serif display headings with sans-serif controls and metadata. Keep an 8dp spacing rhythm, 12–20dp corner radii and minimum 48dp touch targets. Do not wrap every element in a heavy card.
- In landscape, place cover art beside feature text. In portrait, stack them and allow scroll. Large system text expands or scrolls instead of clipping.
- Use short fades up to 220ms and subtle press feedback. Respect disabled system animations. Avoid decorative page shrinking, autoplay and continuous effects.
- Extract screen compositions and reusable elements out of `MainActivity` into focused Compose UI files with explicit state and action callbacks. Add navigation state and a deterministic Home-feature selector. Keep the existing single reader session, progress repository, preferences and restore key semantics.
- Reuse cached covers. Derive a muted Home tint off the main thread and fall back to neutral. Preview fixture data may be used in previews/screenshots but must not enter production library data.

## Delivery and acceptance

1. Build theme, responsive navigation, Home and Library first. Use preview fixtures for populated, empty, missing-cover, portrait and landscape states.
2. Capture actual app screens and compare them with the approved reference before completing Collections, details, Settings and reader surfaces.
3. Verify resume fallbacks, search, filters, sorting, detail actions, collection membership, bookmarks, thumbnails, finish behavior, navigation restoration and scroll restoration.
4. Verify reader viewport size is unchanged while controls/panels are open; inspect rotation, zoom, fast swipes, RTL and spreads.
5. Check large text, long titles, missing covers, loading/errors, accessibility labels, narrow windows and disabled animations.
6. Run the existing unit suite, lint and APK build. Update this document with results and note any visual acceptance that cannot be completed without an emulator or tablet.

Success means the rendered app matches the new screen composition, not simply new colors applied to the old layout. Actual screenshots are required for visual acceptance.

## Implementation checkpoint

- Implemented the new Home, Library, Collections and Settings destinations, responsive rail/bottom navigation, cover based Home tint, compact reader dock and on demand reader options.
- `:app:assembleDebug` and `:app:lintDebug` passed for this checkpoint.
- Real device screenshot comparison remains pending: no Android device or emulator is available in the build environment.
