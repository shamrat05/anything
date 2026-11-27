# Context menus and touch selection

There are **15 distinct app-owned context menus**, plus the sidebar and ZIP/APK entry lists, which use the same touch pattern but do not open separate menus.

| # | Menu | Where it opens |
|---|---|---|
| 1 | View | List/grid view shortcut in Search and Files |
| 2 | Sort | Sort shortcut in Search, Files and Map |
| 3 | Search options | Search toolbar and the app overflow route |
| 4 | More actions | App toolbar three-dot menu; nested groups use the same rows |
| 5 | Clear | Hold the Search clear/back control |
| 6 | Selection | Three dots in the multi-selection toolbar |
| 7 | Folder history | Folder breadcrumb toolbar |
| 8 | Workspace tabs | Sidebar or More actions |
| 9 | Move tab | Tab-grip action when several tabs are open |
| 10 | Active pane | Two-pane Files toolbar |
| 11 | File actions | Long-press a file in Search, Files, Map or either pane; one shared model |
| 12 | Preview actions | Viewer three dots, including archived files opened in the viewer |
| 13 | Syntax | Text/code preview’s syntax button |
| 14 | File category | Two-column picker from the category shortcut above the file list |
| 15 | File types | File category → File types…, hold the category shortcut, or More actions → Filters |

File types keeps the picker open for multiple checked rows. **Clear** starts an empty staged filter, **All** resets it, and **Apply** commits a nonempty set. Dismissal does not commit. The guide points at the category control.

The left sidebar contains navigation rows, not a separate set of popup menus. In ZIP or APK contents, press and slide across entry rows to choose which entry to open. An opened entry has its own content/preview panel, not a second file context menu. TAR/GZIP extraction and 7z read-only listings are not ZIP entry pickers.

- Tap still works. While touching an enabled row, the highlight follows the finger; only release commits. Disabled rows cannot commit. Moving out of the menu, another finger, a cancelled gesture or loss of window focus clears the selection.
- A quick vertical drag in a scrolling menu still scrolls. After a short hold (about 170 ms), dragging picks an action instead. An outward swipe still closes the sidebar. The ZIP list keeps its separate edge fast-scroll affordance.
- The hold that opens a file’s action card can only retarget the file. Lift, then start a **fresh** touch on the action card to choose an action. Delete and move keep their existing confirmations.
- Android share and Open with, permission prompts and package installation stay system-controlled. Modal confirmation buttons, settings forms and document controls are not release-to-pick rows.
- Keep routine pull-to-refresh silent: a brief gradient ring acknowledges an accepted pull and lifts away. With reduced motion it appears briefly without moving. Search and indexing still run, the inline status still shows when relevant, and meaningful errors remain.

QA: exercise View → Automatic → List; a disabled row; exit/cancel; quick scrolling in an overflowing menu; a submenu; a fresh gesture on a file action card; sidebar navigation; and ZIP entry selection. Verify no action before release, preserved keyboard and TalkBack click routes, and no routine refresh toast. Log the exact APK, device and observed results before calling a gate complete.

All file action cards, including the dual-pane card, have an explicit close button beside the filename and path. The filename and path remain separate copy targets. Properties uses the same sheet across Files, Map and Preview and distinguishes unknown metadata from zero bytes. It shows a file-kind icon, type, size, modified date, full path and an explicit Copy path button when the path is available.

Selection must retain the original file icon or thumbnail and the outline. List, Compact, Grid and both panes keep the icon in its original position and use a small top-right check badge, not a leading gutter. Roomy Map tiles use an adjacent badge; dense tiles preserve the outline and announce selection without crowding the label.

The top-bar Switch windows shortcut reuses the Workspace tabs menu. It lists saved browse tabs, up to four minimized previews with distinct folder labels and individual close actions, and a route to open or resume Two-pane Files. It does not create a second tab system or change the current folder when opening the chooser. Pane items can rename or delete directly with the same guarded file operations and confirmation screens.

With an active selection, holding a marked item opens batch actions with the exact selection count. Search, Files, Map and panes do not open the single-file card for a marked row. The viewer tray uses the same interaction for selected thumbnails. Single-file actions remain separate; batch delete still requires confirmation.
