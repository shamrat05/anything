package dev.fastsearch.android;

import android.view.Menu;
import android.view.View;
import dev.fastsearch.core.FileEntry;

/** The item menu is independent of both the previewer and the multi-selection toolbar. */
final class FileItemMenu {
    private FileItemMenu() {}

    static Menu model(View anchor, UiTheme theme, FileEntry entry, boolean pane, boolean transfer) {
        Menu menu=theme.popup(anchor.getContext(),anchor).getMenu();
        menu.add("Open");
        if(!entry.directory)menu.add("Share");
        menu.add(pane?"Copy to other pane":"Copy").setEnabled(transfer);
        menu.add(pane?"Move to other pane":"Move").setEnabled(transfer);
        menu.add("Properties");
        menu.add("Copy path");
        menu.add("Select items");
        if(!entry.directory)menu.add("Open with");
        menu.add("Rename");menu.add("Delete");
        return menu;
    }
}
