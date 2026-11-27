# Anything's mobile workspace

The home screen is a file tool, not a dashboard. Keep the original search field first, with the sidebar at its leading edge and Refresh and More at its trailing edge. Do not put another Search label, logo banner, or a large navigation row above it. Add choices in the sidebar rather than taking height from the files.

## Layout rules

- Keep primary icon targets at least 48 dp tall and wide. The artwork can be 18–24 dp inside that space. Do not shrink the hit area to fit another action; put less frequent actions in a menu.
- Keep file names prominent and details quiet. Reuse the file-type symbols, platform/app icons where available, and native thumbnails. Match text truncation to the task: keep the title legible and preserve the useful end of long paths.
- Use charcoal surfaces and a blue active accent without painting every control blue. A checked or selected state needs a shape, marker, or label, not just color. Use the system settings for font scaling, handed layout, and reduced motion.
- On phones the leading-edge sidebar is narrower than the screen and leaves a visible strip of the workspace. Open it from the toolbar or by swiping inward just beside the system gesture area. Close, Back, tapping outside, and swiping out dismiss it. Moving a tab is separate from the Copy and Move commands.

## Journeys to verify before release

| Journey | Expected behavior | Edge cases |
| --- | --- | --- |
| Launch and grant access | First launch shows the real workspace, with an in-context access action. The user chooses when to open Android's settings. | Denial returns to a usable screen without a repeat prompt; no contact or SMS request unless the user chooses that category. |
| Guided tour | Highlight the real search field, sidebar trigger, drawer rows, and view control. Position copy above or below the cutout as space permits. Viewer help highlights its own controls. | Back, Skip, Done and replay work without changing the current query or granting access. Small text areas scroll at larger font scales. |
| Search and results | Use the original compact field and familiar icon positions. The result list remains on screen while input and filtering change. | First index, no results, unsupported permissions, long names, keyboard up and compact phone width. |
| Sidebar and storage | A visible toolbar icon opens an actual side panel with views, common folders, volume capacity and tab tools. Show what is used and available with labels as well as a meter. | Unknown or removed volume reports unavailable; Back and outside tap close without altering the active view. |
| Files and Map | Start in the current folder. Enter a folder, return to its parent, filter the current scope, and switch view without losing place. Use size labels and color together. | Empty, permission denied, very long paths, zero-byte files, and unreadable entries. |
| Tabs and two-pane files | Keep independent folder/query histories. Move a workspace using its grip or a menu action. On the phone both source and destination lists stay visible, with a compact shared action strip. Two-pane transfer clearly names source and destination. | Closing a tab restores another workspace. Cancel never moves anything; collisions never overwrite. Cross-volume Move points to Copy instead. |
| Folder tree | Enter from the sidebar or current path. Show the current folder, keep siblings visible when a branch expands, and open a chosen folder without a second confirmation. Use the same picker for the active pane and to fill a Paste destination. | Preserve the other pane and do not start a transfer from the picker. Allow a typed path in Files and Paste. |
| File actions from the list | Press and hold an item for a two-column file menu. Keep the finger down and slide to update the filename, path, properties, and row highlight together. Release leaves the last menu open; an action requires a new tap. Select items enters multi-select. | Unsupported media still offers Share, Copy, and Properties. A normal tap still opens the file. |
| Preview and return | The file owns the screen. Its own three-dot menu offers Share, Copy, Move, Properties, and Show in Files. When opened from two panes, transfers clearly name the other pane as the destination and still ask for confirmation. Focus, preview navigation, and one minimized preview are separate controls. | Video doesn't resume playing unexpectedly. Unsupported, missing, or corrupt files offer a clear exit or Open in another app. |
| Help and updates | Keep Check for updates in the main menu and explanation in Help. The browser/store and Android handle installation. | Offline or missing handler is explained; never ship an APK whose signing certificate doesn't match an in-place upgrade. |

## Tap budget

- Search to open: tap the search field, type, then tap the result. No separate Search mode button.
- Browse a folder from Search: sidebar, Files or Storage map, then a folder. Once in a folder, Files ↔ Map switches in one tap without leaving it; entering a child folder takes one tap.
- Act on one file: hold the row and choose an action. No preview step.
- Copy or move between open panes: select the items, tap Copy or Move, then confirm the displayed destination. There is no separate clipboard or Paste step. Keep the confirmation and no-overwrite behavior.

## Evidence, not estimates

Review actual screenshots with synthetic files at the narrowest supported layouts and on the Realme. Run device navigation, permission, preview, and operation probes before publishing. A compiled test is not a passed device test. Run signed in-place upgrade tests from the exact previous APK for both editions, preserving the fixture state. Record Android 8 and current-device results separately. Do not claim safety, speed, or compatibility beyond observed evidence.

## Storage map design references

[Shneiderman’s treemap paper](https://www.cs.umd.edu/~ben/papers/Shneiderman1992Tree.pdf) explains why nested blocks and weighted area make a directory hierarchy easier to compare. [QDirStat’s map guide](https://github.com/shundhammer/qdirstat/blob/master/doc/Treemap.md) shows the practical companion pattern: visible groups plus a list for files too small to inspect in the map. Anything uses file sizes for its blue-to-red scale, rather than QDirStat’s type colors. Touch targets stay large enough on mobile; top-level tile area is an approximation where the minimum target size takes priority.


## Quiet finish

Use a slight [Android system palette](https://developer.android.com/develop/ui/views/theming/dynamic-colors) tint on neutral surfaces (with a blue fallback and an opt-out). Do not read wallpaper images. Never draw a fixed short gradient rule beneath Search or any shared preview header; it looks like a stalled progress indicator. Reserve the gradient for the icon family, the selected workspace tab and brief, non-modal refresh feedback. Real progress, errors and playback indicators keep their own meaning.

See [context-menu inventory and gesture behavior](CONTEXT-MENUS.md).


Keep a common rounded two-plane shape language at 24dp. Use silhouettes before color: terminals for executable scripts, a sealed APK package for install files, a grid for an installed-app fallback, a zipper for archives, a landscape for photos, and a play mark for video. Use installed app icons and decoded media thumbnails where available; the original vectors are fallbacks.

## Dark canvases

Dark mode uses pure black file canvases, menu backgrounds and storage chrome. A thin outline separates floating controls, and selected rows use a quiet blue surface and a readable corner mark. Wallpaper tint can still affect the light palette; it does not raise black levels in dark mode. Grid and Select share the same neutral pill, with the checkbox aligned directly beside its label.
