# Interactive page turns

The user chose all three modes, with photo-style swipe as the default and landscape spreads moving as one unit. All modes reveal the decoded neighboring unit while the finger moves.

- Photo swipe: current and neighboring pages move side by side at their full fitted size.
- Paper curl: the current sheet peels back with a curved paper band and a small shadow, revealing the next unit underneath. The landscape pair turns together.
- Sliding sheet: the current page moves over a stationary neighboring page, with a small edge shadow.

A fast flick or a drag past halfway finishes. A short slow drag returns. The settle animation starts at the finger's release position; a strong reverse flick cancels the turn. Reading progress changes only when a turn completes. Book boundaries never reveal an empty page.

Controls overlay the page rather than changing the reader's dimensions. Pinch zoom and pan take priority over page dragging. Rotation cancels a partial gesture and retains the logical current page. The chosen mode is saved per comic.

The reader holds only current/previous/next units under a bitmap byte budget, using lower-resolution adjacent previews. All archive I/O and disposal are serialized. A neighbor must be decoded before a turn toward it can start. Bitmap eviction drops references rather than recycling live draw resources.

Verification covers natural-swipe completion/cancellation, spread boundaries and standalone covers, Android compilation, and APK packaging. Physical gesture smoothness and the curl appearance require a tablet or emulator; this environment has neither a connected device nor an emulator.
