package dev.fastsearch.android;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.Spinner;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;

/** Exercises the real controls and thumbnail workers using an isolated index only. */
public final class UiProbe extends Instrumentation {
    private Context target;
    private File fixture;
    private File thumbnailFixture,thumbnailPngFixture;
    private FileIndex index;
    private volatile MainActivity activity;
    private String id;
    private int assertions;
    private boolean pinchOnly,mapOnly;
    private Map<String, ?> savedUi, savedClipboard;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); pinchOnly=args!=null&&"true".equals(args.getString("pinchOnly"));
        mapOnly=args!=null&&"true".equals(args.getString("mapOnly"));start(); }
    @Override public void callActivityOnResume(Activity resumed){super.callActivityOnResume(resumed);if(resumed instanceof MainActivity)activity=(MainActivity)resumed;}
    @Override public void onStart() {
        boolean passed = false;
        try { target = getTargetContext(); exercise(); passed = true; }
        catch (Throwable failure) {
            java.io.StringWriter trace = new java.io.StringWriter();
            failure.printStackTrace(new java.io.PrintWriter(trace)); report.append(trace);
            try{if(activity!=null&&!activity.isDestroyed())capture("failure.png");}catch(Exception ignored){}
        } finally {
            try { cleanup(); } catch (Throwable failure) { passed = false; report.append("Cleanup: ").append(failure).append('\n'); }
        }
        Bundle result = new Bundle(); result.putBoolean("passed", passed); result.putInt("assertions", assertions);
        result.putString("stream", report.append(passed ? "PASS " : "FAIL ").append(assertions).append(" UI/thumbnail assertions.\n").toString());
        finish(passed ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
    }

    private void exercise() throws Exception {
        android.accessibilityservice.AccessibilityServiceInfo info = getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
        getUiAutomation().setServiceInfo(info);
        savedUi = target.getSharedPreferences("ui", 0).getAll();
        savedClipboard = target.getSharedPreferences("file-clipboard", 0).getAll();
        check(target.getSharedPreferences("ui", 0).edit().clear().putInt("view", 0).putInt("filter", 0)
                .putInt("sort", 0).commit(), "isolated UI preferences with name sort");
        FileClipboard.clear(target);
        id = "IndexProbeUi" + SystemClock.elapsedRealtimeNanos();
        fixture = new File(target.getCacheDir(), "IndexProbe-UI-" + SystemClock.elapsedRealtimeNanos()).getCanonicalFile();
        check(fixture.mkdir(), "create isolated UI files");
        File pictures = new File(fixture, "Photos"), documents = new File(fixture, "Documents");
        check(pictures.mkdir() && documents.mkdir(), "fixture folders");
        for (String name : new String[]{"Earthrise.jpg", "Earth.jpg", "Saturn.jpg", "Earthrise.mp4"}) copyAsset(name, new File(pictures, name));
        PreviewUiFixtures.wav(new File(documents,"Earthquake.wav"));
        PreviewUiFixtures.pdf(new File(documents,"Guide.pdf"));
        File nested=new File(documents,"Folder One"),deep=new File(nested,"Folder Two");check(deep.mkdirs(),"nested browsing fixtures");
        for(String extension:new String[]{"json","xml","csv","html","css","js","java","kt","py","log","yaml","ini"})
            write(new File(deep,"Project notes and exported specification."+extension),"Owned UI fixture");
        write(new File(fixture,".hidden-note.txt"),"hidden");
        File hiddenFolder=new File(fixture,".vault");check(hiddenFolder.mkdir(),"hidden fixture directory");
        write(new File(hiddenFolder,"secret.txt"),"hidden ancestor");
        for (int n = 0; n < 2300; n++) write(new File(documents, String.format(java.util.Locale.ROOT, "Notes %04d.txt", n)), "Local search fixture\n");
        write(new File(documents, "Trip checklist.md"), "# Trip\nCamera\nCharger\n");
        index = FileIndex.forProbe(target, fixture, id); index.setRootEnabled(false); index.startScan();
        await(() -> index.getState().loaded && !index.getState().scanning && index.getState().lastScanMillis > 0, "fixture index built");
        check(index.getState().entries.size() >= 70, "only bounded fixture indexed");
        runOnMainSync(() -> MainActivity.setProbeIndex(index));
        launch(); waitResults();
        if(pinchOnly){pinchDensity();return;}
        if(mapOnly){persistentResultsAndMap(documents);shortMapBand();return;}
        check((Boolean)onMain(()->field(activity,"descending")),"descending is the default direction");
        check(onMain(()->{android.widget.ListAdapter rows=((AbsListView)activity.findViewById(R.id.results)).getAdapter();
            return ((FileEntry)rows.getItem(0)).name.compareToIgnoreCase(((FileEntry)rows.getItem(rows.getCount()-1)).name)>0;}),"initial results use descending name order");
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());
        waitForIdleSync();capture("08-default-sort.png");clickText("Descending");waitResults();
        check(!(Boolean)onMain(()->field(activity,"descending")),"explicit ascending choice overrides default");
        check(onMain(() -> activity.findViewById(R.id.results) instanceof android.widget.ListView), "automatic general list");
        float density = target.getResources().getDisplayMetrics().density;
        check(onMain(() -> activity.findViewById(R.id.results).getTop()) <= 136 * density, "compact controls and slim section label");
        check(!containsText(activity.getWindow().getDecorView(), "Show more"), "no permanent pagination row");
        capture("01-files.png");
        checkpoint("compact controls");compactControls();
        checkpoint("pinch density");pinchDensity();
        checkpoint("drag scrollbar");fastScrolling();
        checkpoint("search and hidden files");searchControls();
        checkpoint("directory history");folderHistory();
        checkpoint("extension picker and grid labels");subtypes();
        checkpoint("selection during index updates and storage map");persistentResultsAndMap(documents);shortMapBand();
        int fullBuilds=index.fullScanStartsForProbe();long checked=index.getState().lastScanMillis;
        runOnMainSync(()->activity.findViewById(R.id.refresh).performClick());
        await(()->!index.getState().scanning&&index.getState().lastScanMillis>checked,"refresh reconciles existing index");waitResults();
        check(index.fullScanStartsForProbe()==fullBuilds,"refresh button does not rebuild completed index");

        // A list icon selects; the filename still opens the file in a separate viewer.
        runOnMainSync(() -> ((View) firstIcon().getParent()).performClick());
        check(onMain(() -> activity.findViewById(R.id.selection_bar).getVisibility() == View.VISIBLE), "icon tap starts selection");
        check(selectionLabel().startsWith("1 "), "one selected file");
        check(onMain(() -> clickDescription(activity.getWindow().getDecorView(), "Selection options")), "selection menu opens");
        clickText("Select all");
        await(()->selectionLabel().startsWith(java.text.NumberFormat.getIntegerInstance().format(index.getState().entries.size())+" "),"select all applies");
        check(selectionLabel().startsWith(java.text.NumberFormat.getIntegerInstance().format(index.getState().entries.size()) + " "), "select all includes complete match set");
        runOnMainSync(() -> clickDescription(activity.getWindow().getDecorView(), "Selection options"));
        clickText("Invert selection");await(()->selectionLabel().startsWith("0 "),"invert selection applies"); check(selectionLabel().startsWith("0 "), "invert all clears logical selection");
        runOnMainSync(() -> clickDescription(activity.getWindow().getDecorView(), "Selection options"));
        clickText("Invert selection");await(()->selectionLabel().startsWith(java.text.NumberFormat.getIntegerInstance().format(index.getState().entries.size())+" "),"invert selection restores all"); check(selectionLabel().startsWith(java.text.NumberFormat.getIntegerInstance().format(index.getState().entries.size()) + " "), "invert restores complete selection");
        runOnMainSync(() -> clickDescription(activity.getWindow().getDecorView(), "Clear selection"));
        check(onMain(() -> activity.findViewById(R.id.selection_bar).getVisibility() == View.GONE), "clear selection restores controls");

        // The actual category spinner chooses media layout automatically.
        runOnMainSync(() -> ((Spinner) activity.findViewById(R.id.category)).setSelection(6));
        waitResults(); await(() -> onMain(() -> activity.findViewById(R.id.results) instanceof android.widget.GridView), "automatic photo grid");
        await(() -> onMain(() -> firstIcon().getDrawable() instanceof BitmapDrawable && firstIcon().getImageTintList() == null), "photo thumbnail decoded");
        check(onMain(() -> ((AbsListView) activity.findViewById(R.id.results)).getAdapter().getCount()) == 3, "photo filter contains actual photos");
        capture("02-photos.png");

        runOnMainSync(() -> activity.findViewById(R.id.view_mode).performClick()); clickText("Compact list");
        waitResults(); check(onMain(() -> activity.findViewById(R.id.results) instanceof android.widget.ListView), "compact view applied");
        check(onMain(() -> ((AbsListView) activity.findViewById(R.id.results)).getChildAt(0).getHeight()) <= 54 * density, "compact row height");
        runOnMainSync(() -> activity.findViewById(R.id.view_mode).performClick()); clickText("Automatic");
        runOnMainSync(() -> ((Spinner) activity.findViewById(R.id.category)).setSelection(7)); waitResults();
        await(() -> onMain(() -> firstIcon().getDrawable() instanceof BitmapDrawable && firstIcon().getImageTintList() == null), "native video thumbnail decoded");
        capture("03-videos.png");

        checkpoint("provider paging");paging();
        File photo = new File(pictures, "Earthrise.jpg");
        checkpoint("thumbnails");exerciseThumbnails(photo);
        checkpoint("themes");themes();
        List<WeakReference<MainActivity>> released = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            ThumbnailLoader old = (ThumbnailLoader) field(activity, "thumbnails");
            ThreadPoolExecutor search = (ThreadPoolExecutor) field(activity, "searches");
            for (int style = 0; style < 4; style++) {
                runOnMainSync(() -> activity.findViewById(R.id.view_mode).performClick());
                clickText(new String[]{"Automatic", "List", "Compact list", "Grid"}[style]); waitForIdleSync();
            }
            released.add(finishActivity());
            check(old.isStopped() && old.awaitStopped(15_000), "thumbnail workers stop after Activity destruction");
            check(old.cachedBytes() == 0 && old.queuedJobs() == 0, "destroyed thumbnail cache and queue empty");
            check(search.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS), "search worker stops after Activity destruction");
            old = null; search = null;
            launch(); waitResults();
        }
        released.add(finishActivity());
        await(() -> { System.gc(); System.runFinalization(); for (WeakReference<MainActivity> ref : released) if (ref.get() != null) return false; return true; }, "closed Activities released after repeated view changes");
        report.append("Actual list/grid controls, widget touch selection, search/scope clearing, hidden descendants, NASA photo/video thumbnails, bounded caches/queues and five destroyed Activity references checked.\n");
    }

    private void checkpoint(String label){Bundle status=new Bundle();status.putString("stream","UI: "+label+"\n");sendStatus(0,status);}

    private void shortMapBand() throws Exception {
        check(onMain(() -> {
            StorageMapView map = new StorageMapView(activity, UiTheme.resolve(activity), bytes -> "2.2 GB");
            FileEntry folder = new FileEntry("/short-map/Folder", true, 0, 0);
            map.show("/short-map", java.util.Collections.singletonList(new StorageMapData.Tile(folder, 2L << 30)),
                    entry -> {}, entry -> {}, entry -> {});
            layoutMap(map);
            ViewGroup band = (ViewGroup) map.getChildAt(0);
            if (band == null) return false;
            TextView tile = (TextView) band.getChildAt(0);
            return tile.getWidth() >= band.getWidth() * .95f && hasFullCaption(tile, "2.2 GB");
        }), "single large folder fills the map width and shows its full size line");
        check(onMain(() -> {
            StorageMapView map = new StorageMapView(activity, UiTheme.resolve(activity), bytes -> bytes + " B");
            List<StorageMapData.Tile> tiles = new ArrayList<>();
            for (int i = 0; i < 17; i++) {
                long bytes = i < 16 ? 1L << 30 : 1;
                FileEntry entry = new FileEntry("/short-map/An item with a long caption " + i, false, bytes, 0);
                tiles.add(new StorageMapData.Tile(entry, bytes));
            }
            map.show("/short-map", tiles, entry -> {}, entry -> {}, entry -> {});
            layoutMap(map);
            View first = ((ViewGroup) map.getChildAt(0)).getChildAt(0);
            long largeArea = (long) first.getWidth() * first.getHeight();
            map.setSelectionFromTop(map.getCount() - 1, 0);
            layoutMap(map);
            ViewGroup tail = (ViewGroup) map.getChildAt(map.getLastVisiblePosition() - map.getFirstVisiblePosition());
            TextView small = (TextView) tail.getChildAt(tail.getChildCount() - 1);
            long smallArea = (long) small.getWidth() * small.getHeight();
            if (!coversBand(tail) || !hasFullCaption(small, "1 B")
                    || smallArea == 0 || largeArea < smallArea * 4) return false;
            tiles = new ArrayList<>(tiles);
            tiles.sort(StorageMapData.order(2, false));
            map.show("/short-map", tiles, entry -> {}, entry -> {}, entry -> {});
            map.setSelectionFromTop(0, 0);
            layoutMap(map);
            ViewGroup head = (ViewGroup) map.getChildAt(0);
            TextView smallest = (TextView) head.getChildAt(0);
            View larger = head.getChildAt(1);
            return coversBand(head) && hasFullCaption(smallest, "1 B")
                    && (larger == null || (long) larger.getWidth() * larger.getHeight()
                            > (long) smallest.getWidth() * smallest.getHeight());
        }), "both size directions keep a one-byte tile selectable without leaving gaps");
        check(onMain(() -> {
            StorageMapView map = new StorageMapView(activity, UiTheme.resolve(activity),
                    bytes -> (bytes >> 20) + " MB");
            long largeBytes = 1021L << 20, smallBytes = 103L << 20;
            FileEntry large = new FileEntry("/short-map/Larger file", false, largeBytes, 0);
            FileEntry small = new FileEntry("/short-map/Smaller file", false, smallBytes, 0);
            map.show("/short-map", java.util.Arrays.asList(new StorageMapData.Tile(large, largeBytes),
                    new StorageMapData.Tile(small, smallBytes)), entry -> {}, entry -> {}, entry -> {});
            layoutMap(map);
            List<TextView> visible = new ArrayList<>();
            for (int i = 0; i < map.getChildCount(); i++) {
                ViewGroup band = (ViewGroup) map.getChildAt(i);
                if (!coversBand(band)) return false;
                for (int j = 0; j < band.getChildCount(); j++) visible.add((TextView) band.getChildAt(j));
            }
            if (visible.size() != 2) return false;
            TextView largeTile = visible.get(0), smallTile = visible.get(1);
            double ratio = (double) largeTile.getWidth() * largeTile.getHeight()
                    / (smallTile.getWidth() * smallTile.getHeight());
            return ratio >= 4 && ratio <= 6 && hasFullCaption(largeTile, "1021 MB")
                    && hasFullCaption(smallTile, "103 MB");
        }), "1021 MB takes roughly five times the area of 103 MB, with both captions visible");
        check(onMain(() -> {
            java.util.function.Function<Long, String> format = bytes -> bytes >= 1L << 20
                    ? (bytes >> 20) + " MB" : bytes + " B";
            StorageMapView map = new StorageMapView(activity, UiTheme.resolve(activity), format);
            List<StorageMapData.Tile> tiles = new ArrayList<>();
            for (int i = 0; i < 347; i++) {
                long bytes = i < 15 ? (347L - i) * 10_000_000 / (i + 1) : 1 + i % 32;
                tiles.add(new StorageMapData.Tile(new FileEntry("/map/Mixed " + i, false, bytes, 1), bytes));
            }
            for (boolean descending : new boolean[]{true, false}) {
                tiles = new ArrayList<>(tiles);
                tiles.sort(StorageMapData.order(2, descending));
                map.show("/map", tiles, entry -> {}, entry -> {}, entry -> {});
                layoutMap(map);
                int verified = 0;
                for (int i = 0; i < map.getCount(); i++) {
                    map.setSelectionFromTop(i, 0);
                    layoutMap(map);
                    int slot = i - map.getFirstVisiblePosition();
                    if (slot < 0 || slot >= map.getChildCount()) return false;
                    ViewGroup band = (ViewGroup) map.getChildAt(slot);
                    if (!coversBand(band) || band.getHeight() > map.getHeight() / 2) return false;
                    for (int j = 0; j < band.getChildCount(); j++) {
                        TextView tile = (TextView) band.getChildAt(j);
                        if (tile.getTop() != 0 || tile.getBottom() != band.getHeight()
                                || !hasFullCaption(tile, format.apply(tiles.get(verified++).bytes))) return false;
                    }
                }
                if (verified != tiles.size()) return false;
            }
            return true;
        }), "all 347 tiles show both lines in both size orders in short, fully filled horizontal rows");
    }

    private static boolean coversBand(ViewGroup band) {
        long covered = 0;
        for (int i = 0; i < band.getChildCount(); i++) {
            View a = band.getChildAt(i);
            if (a.getLeft() < 0 || a.getTop() < 0 || a.getRight() > band.getWidth() || a.getBottom() > band.getHeight()
                    || a.getWidth() <= 0 || a.getHeight() <= 0) return false;
            covered += (long) a.getWidth() * a.getHeight();
            for (int j = 0; j < i; j++) {
                View b = band.getChildAt(j);
                if (a.getLeft() < b.getRight() && b.getLeft() < a.getRight()
                        && a.getTop() < b.getBottom() && b.getTop() < a.getBottom()) return false;
            }
        }
        return covered == (long) band.getWidth() * band.getHeight();
    }

    private static boolean hasFullCaption(TextView tile, String size) {
        android.text.Layout caption = tile.getLayout();
        return tile.getText().toString().endsWith("\n" + size) && caption != null && caption.getLineCount() == 2
                && caption.getLineBottom(1)
                        <= tile.getHeight() - tile.getCompoundPaddingTop() - tile.getCompoundPaddingBottom();
    }

    private void layoutMap(StorageMapView map) {
        int width = activity.findViewById(android.R.id.content).getWidth();
        int height = Math.round(800 * activity.getResources().getDisplayMetrics().density);
        map.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        map.layout(0, 0, width, height);
    }

    private void persistentResultsAndMap(File documents)throws Exception{
        setQuery("file: Notes ext:txt");
        runOnMainSync(()->((android.widget.ListView)activity.findViewById(R.id.results)).setSelectionFromTop(30,-8));
        await(()->onMain(()->((AbsListView)activity.findViewById(R.id.results)).getFirstVisiblePosition())>=30,"scrolled to middle of results");
        String anchor=onMain(()->{AbsListView list=activity.findViewById(R.id.results);
            ((View)list.getChildAt(0).findViewById(R.id.file_icon).getParent()).performClick();
            return ((FileEntry)list.getItemAtPosition(list.getFirstVisiblePosition())).path();
        });
        int oldCount=onMain(()->((AbsListView)activity.findViewById(R.id.results)).getCount());
        check(oldCount==2300,"file-only query starts with the fixture rows");
        File created=new File(documents,"Notes 0000 new.txt");write(created,"new during selection");
        try{
            index.reconcilePaths(java.util.Collections.singletonList(created.getPath())).get(10,java.util.concurrent.TimeUnit.SECONDS);
            await(()->onMain(()->!((Boolean)field(activity,"searchRunning"))&&
                    ((AbsListView)activity.findViewById(R.id.results)).getCount()==oldCount+1),"new file appears in the matching results");
            await(()->onMain(()->firstResultPath().equals(anchor)),"new file keeps the visible file anchor");
            check(selectionLabel().startsWith("1 "),"file stays selected while results change");
            runOnMainSync(()->activity.onBackPressed());waitResults();
        }finally{
            check(created.delete(),"remove owned refresh fixture");
            index.reconcilePaths(java.util.Collections.singletonList(created.getPath())).get(10,java.util.concurrent.TimeUnit.SECONDS);
        }
        setQuery("folder: Documents");
        runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);list.performItemClick(null,0,0);});waitResults();
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(FileTypes.LABELS.length));waitResults();
        check(onMain(()->((TextView)field(activity,"folderPath")).getText().toString().equals(documents.getPath())),"File Manager shows the current folder path");
        check(onMain(()->{AbsListView list=activity.findViewById(R.id.results);
            for(int i=0;i<list.getCount();i++)if(!documents.getPath().equals(((FileEntry)list.getItemAtPosition(i)).parent))return false;
            return list.getCount()>0;}),"File Manager shows immediate directory entries only");
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Bookmark this folder");
        runOnMainSync(()->((View)field(activity,"folderPath")).performClick());
        String typed=new File(documents,"Folder One").getPath();
        await(()->{
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root==null)return false;
            try{
                List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(documents.getPath());
                boolean entered=false;
                Bundle value=new Bundle();
                value.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,documents.getPath()+"/folder o");
                for(AccessibilityNodeInfo node:nodes){
                    if(node.isEditable())entered|=node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,value);
                    node.recycle();
                }
                return entered;
            }finally{root.recycle();}
        },"File Manager path input accepts a partial directory");
        clickText("Folder One");clickText("Folder Two");clickText("Parent folder");clickText("Go");
        await(()->onMain(()->typed.contentEquals(((TextView)field(activity,"folderPath")).getText())),"inline suggestions, subfolder drilldown and Parent open the chosen folder");
        waitResults();
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Recent folders");clickText(documents.getPath());waitResults();
        check(onMain(()->((TextView)field(activity,"folderPath")).getText().toString().equals(documents.getPath())),"recent directory opens");
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Recent folders");clickText(typed);waitResults();
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Bookmarked folders");clickText(documents.getPath());waitResults();
        check(onMain(()->((TextView)field(activity,"folderPath")).getText().toString().equals(documents.getPath())),"bookmark opens its directory");
        for(int i=0;i<4;i++){runOnMainSync(()->activity.onBackPressed());waitResults();}
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("Storage map");waitResults();
        StorageMapView map=onMain(()->(StorageMapView)field(activity,"mapView"));
        await(()->onMain(()->map.isShown()&&map.getChildCount()>0),"weighted map is visible");
        check(onMain(()->map.getCount()>80&&map.getChildCount()<20),"thousands of map tiles are reachable through recycled sections");
        int mapRequest=onMain(()->((java.util.concurrent.atomic.AtomicInteger)field(activity,"sequence")).get());
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());clickText("Name");
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());clickText("Descending");
        await(()->onMain(()->{
            List<?> tiles=(List<?>)field(map,"tiles");
            for(int i=1;i<tiles.size();i++){
                StorageMapData.Tile a=(StorageMapData.Tile)tiles.get(i-1),b=(StorageMapData.Tile)tiles.get(i);
                if(a.entry.name.compareToIgnoreCase(b.entry.name)>0)return false;
            }
            return !tiles.isEmpty();
        }),"map sorting reorders the visible tiles by name");
        check(onMain(()->((java.util.concurrent.atomic.AtomicInteger)field(activity,"sequence")).get()==mapRequest),
                "changing map order does not launch another filesystem scan");
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());clickText("Size");
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());clickText("Descending");
        check(StorageMapView.sizeBand((1L<<20)-1)==0&&StorageMapView.sizeBand(1L<<20)==1
                &&StorageMapView.sizeBand(100L<<20)==2&&StorageMapView.sizeBand(1L<<30)==3
                &&map.fillColor(0)!=map.fillColor(3),"legend uses fixed byte ranges and different colors");
        int firstTop = onMain(() -> map.getChildAt(0).getTop());
        swipeMap(map);
        await(()->onMain(()->map.getFirstVisiblePosition()>0||map.getChildAt(0).getTop()<firstTop),
                "one-finger swipe scrolls down the map, including within a tall section");
        runOnMainSync(()->map.setSelection(map.getCount()-1));
        await(()->onMain(()->map.getLastVisiblePosition()==map.getCount()-1),"smallest items are on the map");
        long smallestArea = onMain(() -> {
            ViewGroup band = (ViewGroup) map.getChildAt(map.getLastVisiblePosition() - map.getFirstVisiblePosition());
            View tile = band.getChildAt(band.getChildCount() - 1);
            return (long) tile.getWidth() * tile.getHeight();
        });
        runOnMainSync(()->map.setSelectionFromTop(0,0));
        await(()->onMain(()->map.getFirstVisiblePosition()==0),"map can return to the top");
        check(onMain(() -> {
            View first = ((ViewGroup) map.getChildAt(0)).getChildAt(0);
            return smallestArea > 0 && (long) first.getWidth() * first.getHeight() >= smallestArea;
        }), "final small tile never grows larger than the first tile after size sorting");
        runOnMainSync(()->map.setSelectionFromTop(map.getCount()/2,0));waitForIdleSync();
        pinchView(map,true);
        await(()->onMain(()->(Float)field(map,"zoom")>1),"pinch enlarges tiles");
        runOnMainSync(()->map.setSelectionFromTop(map.getCount()/2,0));waitForIdleSync();
        int[][] points=onMain(()->{
            List<int[]> visible = new ArrayList<>();
            for (int i = 0; i < map.getChildCount() && visible.size() < 2; i++) {
                ViewGroup band = (ViewGroup) map.getChildAt(i);
                for (int j = 0; j < band.getChildCount() && visible.size() < 2; j++) {
                    View tile = band.getChildAt(j);
                    int y = band.getTop() + tile.getTop();
                    int bottom = Math.min(band.getTop() + tile.getBottom(), map.getHeight() - 4);
                    if (bottom <= Math.max(4, y) || tile.getWidth() < 1) continue;
                    int[] point = mapPoint(band, tile);
                    point[1] = (Math.max(4, y) + bottom) / 2;
                    visible.add(point);
                }
            }
            return visible.toArray(new int[0][]);
        });
        check(points.length == 2, "two blocks can be touched on the enlarged map");
        long pressedAt=SystemClock.uptimeMillis();
        mapTouch(map,pressedAt,android.view.MotionEvent.ACTION_DOWN,points[0]);
        await(()->selectionLabel().startsWith("1 "),"hold selects a map tile");
        String beforeDrag=mapDetails();
        mapTouch(map,pressedAt,android.view.MotionEvent.ACTION_MOVE,points[1]);
        await(()->!mapDetails().equals(beforeDrag),"hold and drag previews the next tile");
        check(mapDetails().split("\n")[1].startsWith(documents.getPath()+"/"),"preview shows the full path below the name");
        mapTouch(map,pressedAt,android.view.MotionEvent.ACTION_UP,points[1]);
        check(selectionLabel().startsWith("1 "),"long press selects a map tile");
        check(mapDetails().equals(beforeDrag)&&beforeDrag.contains("% of folder"),"inspection preserves selection and details show size share");
        check(onMain(()->clickDescription(activity.getWindow().getDecorView(),"Copy selected files")),"map selection uses the existing copy action");
        await(()->FileClipboard.count(target)==1,"map tile copied");
        check(onMain(()->activity.findViewById(R.id.paste).performClick()),"map can open the paste destination dialog");
        clickText("Cancel");FileClipboard.clear(target);
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("Automatic");waitResults();
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(0));waitResults();
        runOnMainSync(()->activity.onBackPressed());waitResults();setQuery("");
    }

    private String mapDetails(){return onMain(()->((TextView)field(activity,"mapSelection")).getText().toString());}
    private static int[] mapPoint(View band,View tile){
        return new int[]{band.getLeft()+tile.getLeft()+tile.getWidth()/2,band.getTop()+tile.getTop()+tile.getHeight()/2};
    }
    private void mapTouch(StorageMapView map,long time,int action,int[] point){
        runOnMainSync(()->{
            android.view.MotionEvent event=android.view.MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,point[0],point[1],0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{map.dispatchTouchEvent(event);}finally{event.recycle();}
        });
    }
    private void swipeMap(StorageMapView map){
        int[] point=onMain(()->new int[]{map.getWidth()/2,map.getHeight()*4/5});
        long time=SystemClock.uptimeMillis();
        mapTouch(map,time,android.view.MotionEvent.ACTION_DOWN,point);
        for(int i=0;i<8;i++){SystemClock.sleep(30);point[1]-=map.getHeight()/12;mapTouch(map,time,android.view.MotionEvent.ACTION_MOVE,point);}
        mapTouch(map,time,android.view.MotionEvent.ACTION_UP,point);
    }

    private String firstResultPath(){
        AbsListView list=activity.findViewById(R.id.results);
        Object first=list.getItemAtPosition(list.getFirstVisiblePosition());
        return first instanceof FileEntry?((FileEntry)first).path():"";
    }
    private void searchControls(){
        int total=index.getState().entries.size(),top=onMain(()->activity.findViewById(R.id.results).getTop());
        runOnMainSync(()->((EditText)activity.findViewById(R.id.query)).setText("folder: Documents"));waitResults();
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())==1,"folder names searchable with existing query field");
        openFirstFolder();
        check(onMain(()->((EditText)activity.findViewById(R.id.query)).getHint().toString()).equals("Search in Documents"),"folder scope reuses search hint");
        check(onMain(()->activity.findViewById(R.id.results).getTop())==top,"folder scope consumes no extra result row");
        setQuery("Notes");clickClear("Clear search");
        check(onMain(()->((EditText)activity.findViewById(R.id.query)).length())==0&&onMain(()->field(activity,"folderScope"))!=null,"clear query retains folder");
        setQuery("Notes");clickClear("Clear folder");
        check(onMain(()->((EditText)activity.findViewById(R.id.query)).getText().toString()).equals("Notes")&&onMain(()->field(activity,"folderScope"))==null,"clear folder retains query");
        setQuery("folder: Documents");openFirstFolder();setQuery("Notes");clickClear("Clear both");
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())==total,"clear both restores complete file results");
        check(onMain(()->activity.findViewById(R.id.clear_search).getVisibility())==View.GONE,"empty unscoped query hides clear action");
        overflow("Filters","Hidden files");waitResults();
        check(!target.getSharedPreferences("ui",0).getBoolean("showHidden",true),"hidden-file preference saved");
        check(onMain(()->{android.widget.ListAdapter rows=((AbsListView)activity.findViewById(R.id.results)).getAdapter();for(int i=0;i<rows.getCount();i++)if(((FileEntry)rows.getItem(i)).hidden)return false;return true;}),"hidden files folders and descendants excluded");
        setQuery("path:.vault");check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())==0,"query cannot expose hidden descendants while disabled");
        runOnMainSync(()->activity.findViewById(R.id.clear_search).performClick());waitResults();
        overflow("File actions","Select all");
        check(selectionLabel().startsWith(java.text.NumberFormat.getIntegerInstance().format(total-3)+" "),"select all respects hidden visibility");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Clear selection"));
        overflow("Filters","Hidden files");waitResults();
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())==total,"show hidden restores entries without rebuilding");
    }
    private void setQuery(String text){runOnMainSync(()->((EditText)activity.findViewById(R.id.query)).setText(text));waitResults();}
    private void clickClear(String option){runOnMainSync(()->activity.findViewById(R.id.clear_search).performLongClick());clickText(option);
        await(()->onMain(()->(option.equals("Clear folder")||((EditText)activity.findViewById(R.id.query)).length()==0)
                &&(option.equals("Clear search")||field(activity,"folderScope")==null)),"clear action applied: "+option);waitResults();}
    private void openFirstFolder(){runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);list.performItemClick(list.getChildAt(0),0,0);});waitResults();}
    private void folderHistory(){
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(2));waitResults();
        openFolder(new File(fixture,"Documents"));openFolder(new File(fixture,"Documents/Folder One"));openFolder(new File(fixture,"Documents/Folder One/Folder Two"));
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(0));waitResults();
        MainActivity before=activity;runOnMainSync(before::recreate);await(()->activity!=before&&!activity.isDestroyed(),"directory recreation completes");waitResults();
        runOnMainSync(()->activity.onBackPressed());waitResults();
        check(onMain(()->field(activity,"folderScope")).equals(new File(fixture,"Documents/Folder One").getPath()),"Back pops one browsed directory after category change and recreation");
        check(onMain(()->((Spinner)activity.findViewById(R.id.category)).getSelectedItemPosition())==0,"directory Back retains the default category");
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getCount())==13,"previous directory includes every matching descendant");
        runOnMainSync(()->activity.findViewById(R.id.clear_search).performClick());waitResults();
        check(onMain(()->field(activity,"folderScope")).equals(new File(fixture,"Documents").getPath()),"search-row X pops only one directory");
        runOnMainSync(()->activity.findViewById(R.id.clear_search).performClick());waitResults();
        check(onMain(()->field(activity,"folderScope"))==null,"final directory Back returns to original unscoped results");
    }
    private void openFolder(File folder){runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);android.widget.ListAdapter rows=list.getAdapter();
        for(int i=0;i<rows.getCount();i++)if(((FileEntry)rows.getItem(i)).path().equals(folder.getPath())){list.performItemClick(null,i,i);return;}
        throw new AssertionError("browsed fixture folder absent");});waitResults();}
    private void subtypes()throws Exception{
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(4));waitResults();
        check(onMain(()->activity.findViewById(R.id.file_subtypes).getVisibility())==View.VISIBLE,"extension button visible for Documents");
        runOnMainSync(()->activity.findViewById(R.id.file_subtypes).performClick());waitForIdleSync();
        PopupWindow popup=(PopupWindow)onMain(()->field(activity,"subtypePopup"));
        check(onMain(()->((android.widget.GridView)popup.getContentView()).getNumColumns())==3,"extension popup uses three native columns");
        check(onMain(()->((android.widget.GridView)popup.getContentView()).getCount())==9,"initial extension popup stays within three rows including More");
        check(onMain(()->((FileSubtypes.Choice)((java.util.List<?>)field(activity,"availableSubtypes")).get(0)).extension).equals("txt"),"most frequent extension appears first");
        capture("07-extension-picker.png");clickText("More");waitForIdleSync();
        PopupWindow expanded=(PopupWindow)onMain(()->field(activity,"subtypePopup"));
        check(onMain(expanded::getHeight)<=target.getResources().getDisplayMetrics().heightPixels*.45f,"expanded extension picker has bounded height");
        clickText("PDF");waitResults();
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getCount())==1,"extension choice filters results");
        overflow("File actions","Select all");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Copy selected files"));await(()->FileClipboard.count(target)==1,"extension-filtered selection copied");
        check(FileClipboard.read(target).entries.getJSONObject(0).getString("path").endsWith("/Guide.pdf"),"clipboard respects extension filter");FileClipboard.clear(target);
        MainActivity before=activity;runOnMainSync(before::recreate);await(()->activity!=before&&!activity.isDestroyed(),"filtered category recreates");waitResults();
        check("pdf".equals(onMain(()->field(activity,"subtype"))),"extension choice survives recreation");
        runOnMainSync(()->activity.findViewById(R.id.file_subtypes).performClick());clickText("All");waitResults();
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("Grid");waitResults();
        runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);int labelHeight=-1,nameTop=-1;
            for(int i=0;i<list.getChildCount();i++){View tile=list.getChildAt(i);Object holder=tile.getTag();TextView name=(TextView)member(holder,"name"),date=(TextView)member(holder,"date");
                check(tile.getBackground() instanceof android.graphics.drawable.GradientDrawable&&tile.getClipToOutline(),"grid tile uses a rounded card");
                if(nameTop<0)nameTop=name.getTop();
                check(name.getTop()==nameTop&&name.getPaddingStart()==((View)date.getParent()).getPaddingStart(),"grid label and metadata use consistent alignment");
                check(name.getEllipsize()==android.text.TextUtils.TruncateAt.END&&name.getMaxLines()==2,"grid filename uses two end-truncated lines");
                if(labelHeight<0)labelHeight=name.getHeight();check(name.getHeight()==labelHeight,"grid filenames share label height");check(date.getMaxLines()==1,"grid date stays on one line");}});
        SystemClock.sleep(2500);capture("06-document-grid.png");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(7));waitResults();
        check(onMain(()->field(activity,"subtype"))==null,"changing category resets extension restriction");
        check(onMain(()->((java.util.List<?>)field(activity,"availableSubtypes")).size())==1,"only present video extensions listed");
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("Automatic");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(0));waitResults();
        check(onMain(()->activity.findViewById(R.id.file_subtypes).getVisibility())==View.GONE,"default category keeps extension button hidden");
    }
    private static Object member(Object owner,String name){try{java.lang.reflect.Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
        catch(ReflectiveOperationException failure){throw new AssertionError(name,failure);}}

    private void pinchDensity()throws Exception{
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("List");waitForIdleSync();
        runOnMainSync(()->((View)firstIcon().getParent()).performClick());check(selectionLabel().startsWith("1 "),"pinch starts with one selected item");
        pinchResults(true);
        check(onMain(()->activity.findViewById(R.id.results) instanceof android.widget.GridView),"two-finger expansion changes list to grid");
        check(selectionLabel().startsWith("1 "),"pinch preserves selection without extra toggles");
        // Exercise the actual one-column boundary in touch mode, where the reported crash occurs.
        for(int cycle=0;cycle<3;cycle++){
            runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);list.setSelection(list.getCount()-1);});waitForIdleSync();
            for(int step=0;step<5;step++)pinchResults(true);
            check(onMain(()->(Integer)field(activity,"gridWidth"))==240,"pinch reaches maximum icon width");
            check(onMain(()->((android.widget.GridView)activity.findViewById(R.id.results)).getNumColumns())==1,"maximum-size grid lays out one column");
            check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getChildCount())>0,"maximum-size grid retains visible rows");
            check(selectionLabel().startsWith("1 "),"maximum-size pinch preserves selection");
            for(int step=0;step<6;step++)pinchResults(false);
            check(onMain(()->(Integer)field(activity,"activeLayout"))==2,"pinch contracts back to compact list");
        }
        runOnMainSync(activity::onBackPressed);check(onMain(()->activity.findViewById(R.id.selection_bar).getVisibility()==View.GONE),"one Back clears result selection");
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("List");waitForIdleSync();
    }
    private void pinchResults(boolean expand)throws Exception{
        pinchView(onMain(()->activity.findViewById(R.id.results)),expand);
    }
    private void pinchView(View view,boolean expand){
        runOnMainSync(()->{
            int[] origin=new int[2];view.getLocationInWindow(origin);
            float x=origin[0]+view.getWidth()*.5f,y=origin[1]+view.getHeight()*.3f;
            long time=SystemClock.uptimeMillis();
            android.view.MotionEvent.PointerProperties[] properties=new android.view.MotionEvent.PointerProperties[2];android.view.MotionEvent.PointerCoords[] coords=new android.view.MotionEvent.PointerCoords[2];
            for(int i=0;i<2;i++){properties[i]=new android.view.MotionEvent.PointerProperties();properties[i].id=i;properties[i].toolType=android.view.MotionEvent.TOOL_TYPE_FINGER;coords[i]=new android.view.MotionEvent.PointerCoords();coords[i].pressure=1;coords[i].size=1;coords[i].y=y;}
            for(int step=0;step<9;step++){float amount=step<2?0:Math.min(1,(step-1)/5f);float span=view.getWidth()*(expand?.08f+.37f*amount:.45f-.37f*amount);coords[0].x=x-span;coords[1].x=x+span;
                int action=step==0?android.view.MotionEvent.ACTION_DOWN:step==1?android.view.MotionEvent.ACTION_POINTER_DOWN|(1<<8):step==7?android.view.MotionEvent.ACTION_POINTER_UP|(1<<8):step==8?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_MOVE;
                android.view.MotionEvent event=android.view.MotionEvent.obtain(time,time+step*30,action,step==0||step==8?1:2,properties,coords,0,0,1,1,0,0,android.view.InputDevice.SOURCE_TOUCHSCREEN,0);
                activity.dispatchTouchEvent(event);event.recycle();}
        });waitForIdleSync();
    }
    private void compactControls()throws Exception{
        View status=onMain(()->activity.findViewById(R.id.status));
        check(onMain(()->status.getParent()==field(activity,"body")),"status overlays results instead of consuming a layout row");
        overflow("Appearance","Show status");
        await(()->onMain(()->status.getVisibility()==View.GONE&&activity.findViewById(R.id.results).getPaddingBottom()==0),"status hidden after menu action");
        check(onMain(()->status.getVisibility()==View.GONE&&activity.findViewById(R.id.results).getPaddingBottom()==0),"status toggle hides badge and reserves no bottom space");
        overflow("Appearance","Show status");
        await(()->onMain(()->status.getVisibility()==View.VISIBLE),"status shown after menu action");
        check(onMain(()->status.getVisibility()==View.VISIBLE),"status can be enabled again");
        check(onMain(()->((TextView)activity.findViewById(R.id.query)).getTypeface().equals(target.getResources().getFont(R.font.aeonik))),"search uses bundled Aeonik family");
        // Actual held input must select exactly once after release.
        View result=onMain(()->((AbsListView)activity.findViewById(R.id.results)).getChildAt(0));hold(result);
        await(()->selectionLabel().startsWith("1 "),"held result selects one item after release");SystemClock.sleep(200);
        check(selectionLabel().startsWith("1 "),"first long press does not invert selection on release");
        java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1);
        ((java.util.concurrent.ExecutorService)field(activity,"fileActions")).submit(()->{entered.countDown();try{release.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS),"identity worker barrier starts");
        try{runOnMainSync(()->{activity.findViewById(R.id.delete).performClick();AppSheet sheet=(AppSheet)field(activity,"activeSheet");check(sheet!=null&&sheet.isShowing(),"delete panel appears before blocked filesystem check");check(!sheet.actions.getChildAt(1).isEnabled(),"delete waits for identity before confirmation");});clickText("Cancel");}finally{release.countDown();}
        check(index.getState().entries.stream().allMatch(entry->new File(entry.path()).exists()),"cancelled delete confirmation leaves every fixture present");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Selection options"));clickText("Rename");
        await(()->onMain(()->{AppSheet sheet=(AppSheet)field(activity,"activeSheet");return sheet!=null&&sheet.isShowing()&&sheet.content.getChildCount()>1&&sheet.content.getChildAt(1) instanceof EditText;}),"single rename sheet opens");
        runOnMainSync(()->{AppSheet sheet=(AppSheet)field(activity,"activeSheet");((EditText)sheet.content.getChildAt(1)).setText("rename-preview.txt");});await(()->onMain(()->((AppSheet)field(activity,"activeSheet")).actions.getChildAt(1).isEnabled()),"single rename identity capture enables Apply");capture("10-rename-single.png");clickText("Cancel");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Clear selection"));
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(6));waitResults();
        hold(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getChildAt(0)));
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Selection options"));clickText("Select all");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Selection options"));clickText("Rename");
        await(()->onMain(()->{AppSheet sheet=(AppSheet)field(activity,"activeSheet");return sheet!=null&&sheet.isShowing();}),"bulk rename sheet opens");
        runOnMainSync(()->{AppSheet sheet=(AppSheet)field(activity,"activeSheet");((Spinner)sheet.content.getChildAt(0)).setSelection(1);});waitForIdleSync();
        runOnMainSync(()->{AppSheet sheet=(AppSheet)field(activity,"activeSheet");((EditText)sheet.content.getChildAt(1)).setText("prefix-");});await(()->onMain(()->((AppSheet)field(activity,"activeSheet")).actions.getChildAt(1).isEnabled()),"bulk rename identity capture enables Apply");capture("11-rename-bulk.png");clickText("Cancel");
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(0));waitResults();
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Clear selection"));
        hold(onMain(()->activity.findViewById(R.id.category)));
        await(()->{android.view.accessibility.AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();try{return root!=null&&!root.findAccessibilityNodeInfosByText("File types").isEmpty();}finally{if(root!=null)root.recycle();}},"held category opens multiple file types");
        clickText("All types");
        // Apply a mixed media filter through the same public control, then verify every returned row.
        runOnMainSync(()->((Spinner)activity.findViewById(R.id.category)).setSelection(6));waitResults();
        hold(onMain(()->activity.findViewById(R.id.category)));clickText("Videos");clickText("Audio");clickText("Apply");waitResults();
        int mask=(1<<6)|(1<<7)|(1<<3);check(onMain(()->((Integer)field(activity,"multiTypes"))==mask),"multiple categories persist a union of photos videos and audio");
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())==5,"combined media filter includes photos video and audio together");
        check(target.getSharedPreferences("ui",0).getInt("types",0)==mask,"combined types saved");
        mixedSort();
        hold(onMain(()->activity.findViewById(R.id.category)));clickText("All types");waitResults();
    }
    private void mixedSort(){
        for(String field:new String[]{"Name","Path","Size","Modified"}){
            runOnMainSync(()->activity.findViewById(R.id.sort).performClick());clickText(field);waitResults();
            for(int reverse=0;reverse<2;reverse++){
                final boolean descending=reverse==1;
                onMain(()->{android.widget.ListAdapter adapter=((AbsListView)activity.findViewById(R.id.results)).getAdapter();
                    int transitions=0;FileEntry previous=null;
                    for(int i=0;i<adapter.getCount();i++){
                        FileEntry current=(FileEntry)adapter.getItem(i);
                        check(FileTypes.category(current)==3||FileTypes.category(current)==6||FileTypes.category(current)==7,"mixed sort contains only chosen categories");
                        if(previous!=null){int order=field.equals("Name")?previous.name.compareToIgnoreCase(current.name):field.equals("Path")?previous.path().compareToIgnoreCase(current.path()):
                                field.equals("Size")?Long.compare(previous.size,current.size):Long.compare(previous.modified,current.modified);
                            boolean metadata=field.equals("Size")||field.equals("Modified");
                            int tie=metadata?previous.name.compareToIgnoreCase(current.name):0;
                            if(tie==0)tie=previous.path().compareTo(current.path());
                            check(order==0?(metadata||!descending?tie<=0:tie>=0):(descending?order>0:order<0),
                                    "mixed categories share global "+field+" sort, descending="+descending);
                            if(FileTypes.category(previous)!=FileTypes.category(current))transitions++;}
                        previous=current;
                    }
                    if(field.equals("Name"))check(transitions>=3,"name sorting interleaves photos audio and video");
                    android.widget.SectionIndexer sections=(android.widget.SectionIndexer)adapter;
                    check(sections.getSections().length>0,"active sort publishes navigation sections");
                    for(int i=0;i<adapter.getCount();i++){int section=sections.getSectionForPosition(i);check(sections.getPositionForSection(section)<=i,"section points to its first sorted row");}
                    check(((TextView)activity.findViewById(R.id.result_section)).getText().length()>0,"slim header shows current sort section");
                    return true;});
                runOnMainSync(()->activity.findViewById(R.id.sort).performClick());clickText("Descending");waitResults();
            }
        }
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());clickText("Name");waitResults();
    }
    private void fastScrolling(){
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())==index.getState().entries.size(),"all cached file rows are reachable beyond 2000 without expanding prefixes");
        for(String style:new String[]{"List","Grid"}){
            runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText(style);waitResults();
            runOnMainSync(()->((AbsListView)activity.findViewById(R.id.results)).setSelection(0));waitForIdleSync();
            check(onMain(()->!((AbsListView)activity.findViewById(R.id.results)).isFastScrollEnabled()),"scrollbar hidden before right-edge touch in "+style);
            runOnMainSync(()->((AbsListView)activity.findViewById(R.id.results)).smoothScrollBy(300,100));SystemClock.sleep(220);
            check(onMain(()->!((AbsListView)activity.findViewById(R.id.results)).isFastScrollEnabled()),"ordinary scrolling does not reveal the thumb in "+style);
            dragScroll(.8f);
            check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).isFastScrollEnabled()),"right-edge touch reveals native thumb in "+style);
            await(()->onMain(()->((AbsListView)activity.findViewById(R.id.results)).getFirstVisiblePosition())>index.getState().entries.size()/2,"thumb dragging jumps through entire "+style+" result set");
            dragScroll(.1f);
            await(()->onMain(()->((AbsListView)activity.findViewById(R.id.results)).getFirstVisiblePosition())<index.getState().entries.size()/2,"thumb can drag back up in "+style);
            await(()->onMain(()->!((AbsListView)activity.findViewById(R.id.results)).isFastScrollEnabled()),"thumb hides after edge gesture in "+style);
        }
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("Automatic");waitResults();
        runOnMainSync(()->((AbsListView)activity.findViewById(R.id.results)).setSelection(0));
    }
    private void dragScroll(float fraction){runOnMainSync(()->{
        AbsListView list=activity.findViewById(R.id.results);float density=target.getResources().getDisplayMetrics().density;
        float x=list.getWidth()-2*density,y=Math.max(20*density,list.getHeight()*Math.min(.95f,list.getFirstVisiblePosition()/(float)Math.max(1,list.getCount()-list.getChildCount())));
        long time=SystemClock.uptimeMillis();int[] actions={android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_MOVE,android.view.MotionEvent.ACTION_UP};
        for(int n=0;n<actions.length;n++){android.view.MotionEvent event=android.view.MotionEvent.obtain(time,time+n*80,actions[n],x,n==0?y:list.getHeight()*fraction,0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);try{list.dispatchTouchEvent(event);}finally{event.recycle();}}
    });waitForIdleSync();}
    private void hold(View view){await(()->onMain(()->activity.hasWindowFocus()),"main input focus before hold");
        SystemClock.sleep(200);int[] at=onMain(()->{int[] location=new int[2];view.getLocationInWindow(location);return location;});
        float x=at[0]+view.getWidth()/2f,y=at[1]+view.getHeight()/2f;long time=SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(time,time,android.view.MotionEvent.ACTION_DOWN,x,y,0);
        android.view.MotionEvent up=android.view.MotionEvent.obtain(time,time+800,android.view.MotionEvent.ACTION_UP,x,y,0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{runOnMainSync(()->activity.dispatchTouchEvent(down));SystemClock.sleep(800);runOnMainSync(()->activity.dispatchTouchEvent(up));}finally{down.recycle();up.recycle();}waitForIdleSync();
    }
    private void paging()throws Exception{
        if(android.os.Build.VERSION.SDK_INT<29)return;
        DeviceSearchProbe.FixtureProvider provider=new DeviceSearchProbe.FixtureProvider();android.content.pm.ProviderInfo info=new android.content.pm.ProviderInfo();info.authority="com.android.contacts;sms";info.applicationInfo=target.getApplicationInfo();provider.attachInfo(target,info);provider.bulkContacts=4230;
        DeviceSearchProbe.FixtureContext context=new DeviceSearchProbe.FixtureContext(target.getApplicationContext(),android.content.ContentResolver.wrap(provider));context.contacts=true;
        java.lang.reflect.Field search=MainActivity.class.getDeclaredField("deviceSearch");search.setAccessible(true);
        try{runOnMainSync(()->{try{search.set(activity,new DeviceSearch(context));}catch(Exception error){throw new AssertionError(error);}((Spinner)activity.findViewById(R.id.category)).setSelection(10);});waitResults();
            int rounds=0;
            while(onMain(()->field(activity,"deviceNext"))!=null){Object next=onMain(()->field(activity,"deviceNext"));runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);list.setSelection(list.getAdapter().getCount()-1);});
                await(()->!java.util.Objects.equals(next,onMain(()->field(activity,"deviceNext"))),"automatic next contact page");waitResults();
                check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getAdapter().getCount())<=2000,"scroll window retains at most 2000 contact rows");
                if(++rounds>60)throw new AssertionError("UI contact pages did not terminate");}
            int first=onMain(()->(Integer)field(activity,"firstDevicePage"));check(first>20,"actual list scroll reaches contacts beyond the 2000-row window");
            runOnMainSync(()->((AbsListView)activity.findViewById(R.id.results)).setSelection(0));await(()->onMain(()->(Integer)field(activity,"firstDevicePage"))<first,"scrolling up reloads an earlier evicted page");
            check(provider.closes==provider.queries,"automatic paging closes every provider cursor");
        }finally{runOnMainSync(()->{try{search.set(activity,new DeviceSearch(target));}catch(Exception error){throw new AssertionError(error);}((Spinner)activity.findViewById(R.id.category)).setSelection(0);});waitResults();provider.database.close();}
    }

    private void exerciseThumbnails(File photo) throws Exception {
        ThumbnailLoader loader=new ThumbnailLoader(target);List<ImageView> offscreen=new ArrayList<>();
        runOnMainSync(()->{for(int n=0;n<400;n++){ImageView image=new ImageView(activity);offscreen.add(image);loader.bind(image,new FileEntry(photo.getPath(),false,photo.length(),n),384,false);}});waitForIdleSync();
        check(loader.queuedJobs()==0&&loader.activeJobs()==0&&loader.sourceDecodesForProbe()==0,"offscreen bindings never queue or decode thumbnails");offscreen.clear();
        File copy=thumbnailFixture=new File(target.getCacheDir(),"ThumbnailProbe-"+SystemClock.elapsedRealtimeNanos()+".jpg").getCanonicalFile();java.nio.file.Files.copy(photo.toPath(),copy.toPath());FileEntry entry=new FileEntry(copy.getPath(),false,copy.length(),copy.lastModified());
        android.widget.FrameLayout[] panel=new android.widget.FrameLayout[1];ImageView[] views=new ImageView[3];
        runOnMainSync(()->{panel[0]=new android.widget.FrameLayout(activity);for(int n=0;n<views.length;n++){views[n]=new ImageView(activity);android.widget.FrameLayout.LayoutParams cell=new android.widget.FrameLayout.LayoutParams(200,200);cell.leftMargin=n*210;panel[0].addView(views[n],cell);}((android.widget.FrameLayout)activity.getWindow().getDecorView()).addView(panel[0],new android.widget.FrameLayout.LayoutParams(-1,220,android.view.Gravity.CENTER));loader.bind(views[0],entry,384,false);});
        await(()->onMain(()->views[0].getDrawable() instanceof BitmapDrawable)&&loader.activeJobs()==0,"visible photo decodes and persists");
        check(loader.sourceDecodesForProbe()==1,"one visible request produces one source decode");Bitmap first=onMain(()->((BitmapDrawable)views[0].getDrawable()).getBitmap());
        check(Math.max(first.getWidth(),first.getHeight())<=384,"cached image pixels are bounded to the requested thumbnail size");
        runOnMainSync(()->{for(int n=0;n<50;n++)loader.bindPreview(views[0],entry,500,false);});
        check(onMain(()->((BitmapDrawable)views[0].getDrawable()).getBitmap()==first)&&loader.sourceDecodesForProbe()==1,"preview fitting and oversized requests reuse normalized bitmap without flashing");
        runOnMainSync(()->{loader.pause();views[0].setImageDrawable(null);loader.resume();});waitForIdleSync();
        check(onMain(()->views[0].getDrawable() instanceof BitmapDrawable)&&loader.sourceDecodesForProbe()==1,"returning screen reuses memory thumbnails");
        runOnMainSync(()->{loader.trim();views[0].setImageDrawable(null);loader.bind(views[0],entry,384,false);});
        await(()->onMain(()->views[0].getDrawable() instanceof BitmapDrawable)&&loader.activeJobs()==0,"disk thumbnail restores after memory eviction");
        check(loader.diskHitsForProbe()==1&&loader.sourceDecodesForProbe()==1,"disk reuse avoids image source decode");
        runOnMainSync(loader::close);check(loader.awaitStopped(15000),"old loader stops without discarding reusable thumbnails");
        ThumbnailLoader reopened=new ThumbnailLoader(target);runOnMainSync(()->reopened.bind(views[0],entry,384,false));
        check(onMain(()->views[0].getDrawable() instanceof BitmapDrawable)&&reopened.sourceDecodesForProbe()==0,"new loader immediately reuses unchanged bitmap");
        runOnMainSync(()->{reopened.trim();views[0].setImageDrawable(null);reopened.bind(views[0],entry,384,false);});
        await(()->onMain(()->views[0].getDrawable() instanceof BitmapDrawable)&&reopened.activeJobs()==0,"new loader restores persisted thumbnail");
        check(reopened.diskHitsForProbe()==1&&reopened.sourceDecodesForProbe()==0,"simulated process cache loss does not reread media source");
        check(copy.setLastModified(copy.lastModified()+2000),"change fixture metadata");FileEntry changed=new FileEntry(copy.getPath(),false,copy.length(),copy.lastModified());
        runOnMainSync(()->reopened.bind(views[0],changed,384,false));await(()->onMain(()->views[0].getDrawable() instanceof BitmapDrawable)&&reopened.activeJobs()==0,"changed file thumbnail refreshes");check(reopened.sourceDecodesForProbe()==1,"changed metadata invalidates prior thumbnail");
        File png=thumbnailPngFixture=new File(target.getCacheDir(),"ThumbnailProbe-"+SystemClock.elapsedRealtimeNanos()+".png").getCanonicalFile();
        Bitmap pngSource=Bitmap.createBitmap(2048,1536,Bitmap.Config.ARGB_8888);pngSource.eraseColor(android.graphics.Color.TRANSPARENT);android.graphics.Canvas pngCanvas=new android.graphics.Canvas(pngSource);android.graphics.Paint pngInk=new android.graphics.Paint();pngInk.setColor(android.graphics.Color.BLUE);pngCanvas.drawRect(512,0,2048,1536,pngInk);
        try(FileOutputStream out=new FileOutputStream(png)){check(pngSource.compress(Bitmap.CompressFormat.PNG,100,out),"PNG fixture encoded");}finally{pngSource.recycle();}
        FileEntry pngEntry=new FileEntry(png.getPath(),false,png.length(),png.lastModified());int sourcesBeforePng=reopened.sourceDecodesForProbe(),hitsBeforePng=reopened.diskHitsForProbe();
        runOnMainSync(()->reopened.bind(views[2],pngEntry,384,false));await(()->onMain(()->views[2].getDrawable() instanceof BitmapDrawable)&&reopened.activeJobs()==0,"visible PNG decodes and persists");
        Bitmap pngBitmap=onMain(()->((BitmapDrawable)views[2].getDrawable()).getBitmap());check(pngBitmap.getWidth()==384&&pngBitmap.getHeight()==288&&android.graphics.Color.alpha(pngBitmap.getPixel(1,1))==0,"native PNG uses cell-sized pixels and retains transparency");
        runOnMainSync(()->{reopened.trim();views[2].setImageDrawable(null);reopened.bind(views[2],pngEntry,384,false);});await(()->onMain(()->views[2].getDrawable() instanceof BitmapDrawable)&&reopened.activeJobs()==0,"PNG returns from disk cache");
        check(reopened.sourceDecodesForProbe()==sourcesBeforePng+1&&reopened.diskHitsForProbe()==hitsBeforePng+1,"PNG revisit reads cached pixels without decompressing original again");
        File pdf=new File(fixture,"Documents/Guide.pdf");FileEntry pdfEntry=new FileEntry(pdf.getPath(),false,pdf.length(),pdf.lastModified());int gridEdge=Math.round(112*target.getResources().getDisplayMetrics().density);
        runOnMainSync(()->{reopened.bind(views[1],pdfEntry,gridEdge,false);reopened.bind(views[2],pdfEntry,Math.round(40*target.getResources().getDisplayMetrics().density),false);});
        await(()->onMain(()->views[1].getDrawable() instanceof BitmapDrawable),"visible PDF grid page decodes");
        check(onMain(()->views[1].getScaleType()==ImageView.ScaleType.FIT_CENTER),"PDF thumbnail stays fitted");Bitmap page=onMain(()->((BitmapDrawable)views[1].getDrawable()).getBitmap());
        check(page.getWidth()<=384&&page.getHeight()<=384&&Math.abs(page.getWidth()/(float)page.getHeight()-.75f)<.005f,"PDF thumbnail bounds and aspect");
        check(onMain(()->!(views[2].getDrawable() instanceof BitmapDrawable)),"small PDF list keeps inexpensive icon");
        runOnMainSync(()->reopened.bind(views[1],pdfEntry,gridEdge,false));check(onMain(()->((BitmapDrawable)views[1].getDrawable()).getBitmap()==page),"PDF return uses exact cached bitmap");
        check(reopened.cachedBytes()>0&&reopened.cachedBytes()<=reopened.cacheLimitBytes(),"shared bitmap budget stays bounded");
        ThumbnailLoader obsolete=new ThumbnailLoader(target);ThreadPoolExecutor pool=(ThreadPoolExecutor)field(obsolete,"workers");java.util.concurrent.CountDownLatch blocked=new java.util.concurrent.CountDownLatch(2),releaseWork=new java.util.concurrent.CountDownLatch(1);
        for(int i=0;i<2;i++)pool.execute(()->{blocked.countDown();try{releaseWork.await(10,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException stopped){Thread.currentThread().interrupt();}});
        check(blocked.await(5,java.util.concurrent.TimeUnit.SECONDS),"thumbnail worker barrier starts");
        try{runOnMainSync(()->obsolete.bind(views[0],new FileEntry(copy.getPath(),false,copy.length(),copy.lastModified()+999),384,false));waitForIdleSync();check(obsolete.queuedJobs()==1,"visible cache miss queues one deduplicated job");
            runOnMainSync(()->{panel[0].setVisibility(View.INVISIBLE);obsolete.viewportChanged();});waitForIdleSync();check(obsolete.queuedJobs()==0,"leaving viewport removes queued decode before it runs");
        }finally{releaseWork.countDown();runOnMainSync(obsolete::close);}
        check(obsolete.awaitStopped(15000)&&obsolete.sourceDecodesForProbe()==0,"obsolete requests perform no source decode");

        runOnMainSync(()->{reopened.trim();reopened.close();((android.view.ViewGroup)panel[0].getParent()).removeView(panel[0]);});check(reopened.awaitStopped(15000),"visible thumbnail workers terminate");
        check(reopened.isStopped()&&reopened.cachedBytes()==0&&reopened.queuedJobs()==0,"closed loader owns no queued work or cache");
    }

    private void themes()throws Exception{
        runOnMainSync(()->{((Spinner)activity.findViewById(R.id.category)).setSelection(6);});waitResults();
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("Automatic");waitResults();
        for(String choice:new String[]{"Light","Dark","System"}){
            MainActivity previous=activity;
            overflow("Appearance","Theme");clickText(choice);
            await(()->activity!=previous&&onMain(()->activity.hasWindowFocus()),"theme recreates visible Activity");waitResults();
            int setting=choice.equals("Light")?1:choice.equals("Dark")?2:0;
            check(target.getSharedPreferences("ui",0).getInt("theme",-1)==setting,"theme choice saved");
            boolean expected=setting==2||(setting==0&&(target.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
            check(onMain(()->((UiTheme)field(activity,"theme")).dark)==expected,"actual Activity follows "+choice+" theme");
            check(onMain(()->((TextView)activity.findViewById(R.id.query)).getTypeface().equals(target.getResources().getFont(R.font.aeonik))),"Aeonik survives theme recreation");
            await(()->onMain(()->{ThumbnailLoader loader=(ThumbnailLoader)field(activity,"thumbnails");if(loader.activeJobs()!=0||loader.queuedJobs()!=0)return false;
                AbsListView list=activity.findViewById(R.id.results);if(list.getChildCount()!=3)return false;
                for(int n=0;n<list.getChildCount();n++)if(!(((ImageView)list.getChildAt(n).findViewById(R.id.file_icon)).getDrawable() instanceof BitmapDrawable))return false;
                return true;}),"all sample photo thumbnails decoded before capture");
            capture("theme-"+choice.toLowerCase(java.util.Locale.ROOT)+".png");
        }
    }

    private void launch() {
        Intent intent = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activity = (MainActivity) startActivitySync(intent); waitForIdleSync();
        runOnMainSync(()->activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        await(()->onMain(()->activity.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT&&activity.hasWindowFocus()),"portrait fixture screen ready");
        runOnMainSync(() -> { activity.findViewById(R.id.query).clearFocus();
            target.getSystemService(InputMethodManager.class).hideSoftInputFromWindow(activity.getWindow().getDecorView().getWindowToken(), 0); });
    }
    private WeakReference<MainActivity> finishActivity() {
        MainActivity previous = activity; runOnMainSync(previous::finish); waitForIdleSync();
        await(previous::isDestroyed, "Activity destroyed"); activity = null;
        return new WeakReference<>(previous);
    }
    private void waitResults() { waitForIdleSync();await(() -> onMain(() -> !((Boolean) field(activity, "searchRunning"))), "search settled"); waitForIdleSync(); }
    private ImageView firstIcon() {
        AbsListView list = activity.findViewById(R.id.results);
        if (list.getChildCount() == 0) throw new AssertionError("No visible result rows");
        return list.getChildAt(0).findViewById(R.id.file_icon);
    }
    private String selectionLabel() { return onMain(() -> ((TextView) activity.findViewById(R.id.selection_count)).getText().toString()); }
    private static boolean clickDescription(View root, String description) {
        if (description.equals(root.getContentDescription())) return root.performClick();
        if (root instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) root).getChildCount(); n++)
            if (clickDescription(((ViewGroup) root).getChildAt(n), description)) return true;
        return false;
    }
    private boolean containsText(View view, String value) {
        return onMain(() -> containsTextMain(view, value));
    }
    private static boolean containsTextMain(View view, String value) {
        if (view instanceof TextView && value.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int n = 0; n < ((ViewGroup) view).getChildCount(); n++)
            if (containsTextMain(((ViewGroup) view).getChildAt(n), value)) return true;
        return false;
    }
    private void overflow(String group,String item){runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());clickText(group);clickText(item);}
    private void clickText(String value) {
        SystemClock.sleep(250);
        await(() -> {
            AccessibilityNodeInfo root = getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            try {
                List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(value);
                boolean clicked = false;
                for (AccessibilityNodeInfo node : nodes) {
                    if (!clicked && node.refresh() && value.equalsIgnoreCase(node.getText() == null ? "" : node.getText().toString())) {
                        clicked=clickAccessible(node);
                        if(!clicked){
                            android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
                            SystemClock.sleep(100);
                            android.graphics.Rect settled=new android.graphics.Rect();
                            if(node.refresh())node.getBoundsInScreen(settled);
                            if (!bounds.isEmpty() && bounds.equals(settled) && node.isVisibleToUser()) {
                                long time = SystemClock.uptimeMillis();
                                android.view.MotionEvent down = android.view.MotionEvent.obtain(time, time, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
                                android.view.MotionEvent up = android.view.MotionEvent.obtain(time, time + 20, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
                                try { clicked = getUiAutomation().injectInputEvent(down, true) && getUiAutomation().injectInputEvent(up, true); }
                                finally { down.recycle(); up.recycle(); }
                            }
                        }
                    }
                    node.recycle();
                }
                if(!clicked&&!hasSearchInput(root)&&scrollAccessible(root))SystemClock.sleep(400);
                return clicked;
            } finally { root.recycle(); }
        }, "click " + value);SystemClock.sleep(120); waitForIdleSync();
    }
    private boolean clickAccessible(AccessibilityNodeInfo node){
        AccessibilityNodeInfo current=AccessibilityNodeInfo.obtain(node);
        try{for(int depth=0;current!=null&&depth<4;depth++){
            if(current.isEnabled()&&current.isClickable()&&current.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
            AccessibilityNodeInfo parent=current.getParent();current.recycle();current=parent;
        }}finally{if(current!=null)current.recycle();}
        return false;
    }
    private static boolean scrollAccessible(AccessibilityNodeInfo node){
        if(node.isScrollable()&&node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{if(scrollAccessible(child))return true;}finally{child.recycle();}}
        return false;
    }
    private static boolean hasSearchInput(AccessibilityNodeInfo node){
        if("android.widget.AutoCompleteTextView".contentEquals(node.getClassName()==null?"":node.getClassName()))return true;
        for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo child=node.getChild(i);if(child==null)continue;
            try{if(hasSearchInput(child))return true;}finally{child.recycle();}}
        return false;
    }
    private void capture(String name) throws Exception {
        SystemClock.sleep(400); Bitmap screen = getUiAutomation().takeScreenshot();
        check(screen != null,"capture display");
        android.graphics.Rect region=onMain(()->{View root=((ViewGroup)activity.findViewById(android.R.id.content)).getChildAt(0);int[] at=new int[2];root.getLocationOnScreen(at);
            return new android.graphics.Rect(at[0]+root.getPaddingLeft(),at[1]+root.getPaddingTop(),at[0]+root.getWidth()-root.getPaddingRight(),at[1]+root.getHeight()-root.getPaddingBottom());});
        region.intersect(0,0,screen.getWidth(),screen.getHeight());
        Bitmap screenshot=Bitmap.createBitmap(screen,region.left,region.top,region.width(),region.height());
        check(screenshot != null, "capture actual synthetic screen");
        File directory = new File(target.getExternalFilesDir(null), "probe-screens");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create screenshot folder");
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out), "write screenshot"); }
        finally { if(screenshot!=screen)screenshot.recycle();screen.recycle(); }
        Bundle status=new Bundle();status.putString("stream","Screenshot: "+new File(directory,name).getPath()+"\n");sendStatus(0,status);
    }
    private void copyAsset(String name, File destination) throws Exception {
        try (InputStream in = getContext().getAssets().open("samples/" + name); FileOutputStream out = new FileOutputStream(destination)) {
            byte[] bytes = new byte[8192]; int n; while ((n = in.read(bytes)) != -1) out.write(bytes, 0, n);
        }
    }
    private static void write(File file, String value) throws Exception { try (FileOutputStream out = new FileOutputStream(file)) { out.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }
    private static Object field(Object owner, String name) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (Exception failure) { throw new AssertionError(failure); } }
    private <T> T onMain(java.util.concurrent.Callable<T> action) {
        final Object[] value = new Object[1]; final Throwable[] error = new Throwable[1];
        runOnMainSync(() -> { try { value[0] = action.call(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
        @SuppressWarnings("unchecked") T result = (T) value[0]; return result;
    }
    private void await(BooleanSupplier condition, String description) {
        long deadline = SystemClock.elapsedRealtime() + 20_000;
        while (SystemClock.elapsedRealtime() < deadline) { if (condition.getAsBoolean()) return; SystemClock.sleep(80); }
        throw new AssertionError("Timed out: " + description);
    }
    private void check(boolean condition, String description) { assertions++; if (!condition) throw new AssertionError(description); }
    private void cleanup() throws Exception {
        if(thumbnailFixture!=null)thumbnailFixture.delete();
        if(thumbnailPngFixture!=null)thumbnailPngFixture.delete();
        if (activity != null) finishActivity();
        runOnMainSync(() -> MainActivity.setProbeIndex(null));
        if (index != null) { index.shutdown(); if (!index.awaitShutdown(15_000)) throw new java.io.IOException("Fixture index did not stop"); }
        if (target != null) {
            restore("ui", savedUi); restore("file-clipboard", savedClipboard);
            if (id != null) { target.deleteDatabase(id + "-root-filenames.db"); target.deleteDatabase(id + "-shared-filenames.db"); target.deleteSharedPreferences(id + "-filename-index"); }
            if (fixture != null && fixture.getCanonicalPath().startsWith(target.getCacheDir().getCanonicalPath() + "/IndexProbe-UI-")) delete(fixture);
        }
    }
    private void restore(String name, Map<String, ?> values) {
        if (values == null) return; SharedPreferences.Editor edit = target.getSharedPreferences(name, 0).edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object v = entry.getValue(); String k = entry.getKey();
            if (v instanceof String) edit.putString(k, (String) v); else if (v instanceof Boolean) edit.putBoolean(k, (Boolean) v);
            else if (v instanceof Integer) edit.putInt(k, (Integer) v); else if (v instanceof Long) edit.putLong(k, (Long) v);
            else if (v instanceof Float) edit.putFloat(k, (Float) v);
            else if (v instanceof java.util.Set) { @SuppressWarnings("unchecked") java.util.Set<String> set = (java.util.Set<String>) v; edit.putStringSet(k, set); }
        }
        if (!edit.commit()) throw new AssertionError("Preference restore failed");
    }
    private static void delete(File file) throws Exception { File[] children = file.listFiles(); if (children != null) for (File child : children) delete(child); if (!file.delete()) throw new java.io.IOException("Fixture cleanup failed"); }
}
