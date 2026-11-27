package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;

/** Exercises the real adapters' geometry and rendering without operating on device files. */
public final class SelectionMarksProbe extends Instrumentation {
    private final FileEntry entry = new FileEntry("/probe/Selection-layout.pdf", false, 100, 1);
    private int checks;

    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }

    @Override public void onStart() {
        Bundle result = new Bundle();
        Throwable[] failure = new Throwable[1];
        Activity activity = null;
        try {
            activity = startActivitySync(new Intent(getTargetContext(),MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("permissionProbe",true));
            final Activity testActivity = activity;
            runOnMainSync(() -> {
                try { resultRows(testActivity); mapTiles(testActivity); }
                catch (Throwable error) { failure[0] = error; }
            });
        } catch (Throwable error) { failure[0] = error; }
        finally {
            if (activity != null) runOnMainSync(activity::finish);
        }
        boolean passed = failure[0] == null;
        result.putBoolean("passed",passed);
        result.putString("stream",passed ? "PASS " + checks + " selection mark assertions\n"
                : android.util.Log.getStackTraceString(failure[0]));
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED,result);
    }

    @SuppressWarnings("unchecked")
    private void resultRows(Activity activity) throws Exception {
        BaseAdapter adapter = (BaseAdapter)field(activity,"adapter").get(activity);
        Field items = field(adapter,"items"), devices = field(adapter,"devices");
        Field layout = field(activity,"activeLayout"), allSelected = field(activity,"allSelected");
        Object previousItems = items.get(adapter), previousDevices = devices.get(adapter);
        int previousLayout = layout.getInt(activity);
        boolean previousAll = allSelected.getBoolean(activity);
        Set<String> selection = (Set<String>)field(activity,"selection").get(activity);
        AbsListView parent = activity.findViewById(R.id.results);
        try {
            items.set(adapter,Collections.singletonList(entry)); devices.set(adapter,Collections.emptyList());
            allSelected.setBoolean(activity,false);
            for (int style : new int[]{1,2,3}) {
                layout.setInt(activity,style);
                View row = adapter.getView(0,null,parent);
                int width = dp(activity,style == 3 ? 112 : 320);
                layout(row,width,0);
                ImageView icon = row.findViewById(R.id.file_icon);
                View mark = row.findViewById(R.id.file_selection_mark);
                Drawable initialIcon = icon.getDrawable();
                Rect initialBounds = bounds(icon);
                View frame=(View)icon.getParent();
                int padding=dp(activity,style==3?0:style==2?12:6);
                check(initialIcon != null && mark.getVisibility() == View.INVISIBLE
                                && initialBounds.left==padding && initialBounds.right==frame.getWidth()-padding
                                && (style==3||frame.getWidth()==dp(activity,48)),
                        "style " + style + " uses original artwork spacing and no check gutter");
                selection.add(entry.path());
                check(row == adapter.getView(0,row,parent),"same row recycled for style " + style);
                layout(row,width,0);
                check(mark.getVisibility() == View.VISIBLE && icon.getDrawable() == initialIcon
                                && initialBounds.equals(bounds(icon)) && mark.getLeft() >= initialBounds.centerX()
                                && mark.getTop() <= initialBounds.centerY() && mark.getWidth()<=dp(activity,14),
                        "style " + style + " adds a small upper-right badge without moving the original icon");
                check(row.isActivated() && (style == 3 ? row.getClipToOutline() : row.getForeground() != null),
                        "style " + style + " keeps its selected card or outline");
                selection.remove(entry.path());
                adapter.getView(0,row,parent);
                check(mark.getVisibility() == View.INVISIBLE && !row.isActivated() && icon.getDrawable() == initialIcon,
                        "style " + style + " clears reused selection without clearing the icon");
            }
        } finally {
            layout.setInt(activity,previousLayout); allSelected.setBoolean(activity,previousAll);
            items.set(adapter,previousItems); devices.set(adapter,previousDevices); selection.remove(entry.path());
        }
    }

    private void mapTiles(Activity activity) throws Exception {
        StorageMapView map = new StorageMapView(activity,UiTheme.resolve(activity),n->"100 B");
        Class<?> type = Class.forName("dev.fastsearch.android.StorageMapView$TileView");
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructor(StorageMapView.class);
        ctor.setAccessible(true);
        TextView tile = (TextView)ctor.newInstance(map);
        tile.setLayoutParams(new android.view.ViewGroup.LayoutParams(-1,-2));
        java.lang.reflect.Method bind = type.getDeclaredMethod("bind",StorageMapData.Tile.class,
                int.class,int.class,int.class,int.class);
        bind.setAccessible(true);
        StorageMapData.Tile model = new StorageMapData.Tile(entry,entry.size);
        int wide = dp(activity,210), tall = dp(activity,140);
        bind.invoke(tile,model,0,0,wide,tall); layout(tile,wide,tall);
        String caption = tile.getText().toString();
        check(field(tile,"showTileMarks").getBoolean(tile),"roomy map tile has file art");
        Bitmap before = render(tile);
        Rect icon = new Rect(((Drawable)field(tile,"fileMark").get(tile)).getBounds());
        map.updateSelection(e->true);
        bind.invoke(tile,model,0,0,wide,tall);
        Bitmap after = render(tile);
        Rect mark = new Rect(((Drawable)field(tile,"pickedMark").get(tile)).getBounds());
        check(tile.isSelected() && caption.contentEquals(tile.getText()) && !mark.isEmpty() && mark.left >= icon.right && mark.top < icon.centerY(),
                "map selection places its badge at upper right without changing the caption");
        boolean identicalIcon = true, changedCheck = false;
        for (int y=icon.top;y<icon.bottom;y++) for (int x=icon.left;x<icon.right;x++)
            identicalIcon &= before.getPixel(x,y) == after.getPixel(x,y);
        for (int y=mark.top;y<mark.bottom;y++) for (int x=mark.left;x<mark.right;x++)
            changedCheck |= before.getPixel(x,y) != after.getPixel(x,y);
        before.recycle(); after.recycle();
        check(identicalIcon && changedCheck,"map selection does not repaint the file icon");
        bind.invoke(tile,model,0,0,dp(activity,65),dp(activity,44));
        check(tile.isSelected() && !field(tile,"showTileMarks").getBoolean(tile),
                "dense map tile keeps selected state without crowding its caption");
    }

    private static Bitmap render(View view) {
        Bitmap pixels=Bitmap.createBitmap(view.getWidth(),view.getHeight(),Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(pixels));
        return pixels;
    }
    private static Rect bounds(View view) { return new Rect(view.getLeft(),view.getTop(),view.getRight(),view.getBottom()); }
    private static Field field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
    private static void layout(View child,int width,int height) {
        child.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,height>0?View.MeasureSpec.EXACTLY:View.MeasureSpec.UNSPECIFIED));
        child.layout(0,0,child.getMeasuredWidth(),child.getMeasuredHeight());
    }
    private static int dp(Activity activity,int px) {
        return Math.round(px*activity.getResources().getDisplayMetrics().density);
    }
    private void check(boolean ok,String message) {
        if(!ok) throw new AssertionError(message);
        checks++;
    }
}
