# Interactive page turns

Photo swipe is the only page-turn animation. The current and neighboring pages move side by side at their full fitted size, and landscape spreads move as one unit.

Each page is clipped to its own viewport before applying zoom. The viewport slides in screen pixels, so magnified content cannot overlap the incoming page. Completion publishes the incoming page and its top-aligned zoom atomically before clearing the outgoing gesture snapshot.

A fast flick or a drag past halfway finishes. A short slow drag returns. The settle animation starts at the finger's release position; a strong reverse flick cancels the turn. Reading progress changes only when a turn completes. Book boundaries never reveal an empty page.

Controls overlay the page rather than changing the reader's dimensions. Pinch zoom and pan take priority over page dragging. Rotation cancels a partial gesture and retains the logical current page. The removed curl and sliding-sheet modes are no longer offered.

The reader holds only current/previous/next units under a bitmap byte budget. All three units use the same reading resolution, so a prepared page retains its quality throughout and after a turn. The budget reserves at most a quarter of the app heap (capped at 96 MiB), accounts for both pages in landscape spreads, and leaves transition headroom. Original image files remain available for full-resolution zoom tiles. All archive I/O and disposal are serialized. A neighbor must be decoded before a turn toward it can start. Bitmap eviction drops references rather than recycling live draw resources.

Verification covers natural-swipe completion/cancellation, spread boundaries and standalone covers, Android compilation, and APK packaging. Physical gesture smoothness and the curl appearance require a tablet or emulator; this environment has neither a connected device nor an emulator.
