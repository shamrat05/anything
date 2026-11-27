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
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.Arrays;
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
    private volatile FileViewerActivity viewer;
    private String id;
    private int assertions;
    private boolean folderSizeOnly,typoOnly,bulkDeleteOnly,freshnessOnly,resultMapOnly,storageOverviewOnly,nestedMapOnly,pinchOnly,mapOnly,navigationOnly,viewerHandoffOnly,paneOnly,contextOnly,treeOnly,toolbarOnly,searchMenuBeforeOnly,searchMenuOnly;
    private boolean quickPopupOnly,navigationViaTap,menuDragOnly,ambientOnly,pullOnly,filterGridOnly,previewWindowsOnly,gridSelectionOnly;
    private Map<String, ?> savedUi, savedClipboard;
    private final StringBuilder report = new StringBuilder();

    @Override public void onCreate(Bundle args) { super.onCreate(args); pinchOnly=args!=null&&"true".equals(args.getString("pinchOnly"));
        gridSelectionOnly=args!=null&&"true".equals(args.getString("gridSelectionOnly"));
        quickPopupOnly=args!=null&&"true".equals(args.getString("quickPopupOnly"));
        previewWindowsOnly=args!=null&&"true".equals(args.getString("previewWindowsOnly"));
        folderSizeOnly=args!=null&&"true".equals(args.getString("folderSizeOnly"));
        typoOnly=args!=null&&"true".equals(args.getString("typoOnly"));
        bulkDeleteOnly=args!=null&&"true".equals(args.getString("bulkDeleteOnly"));
        freshnessOnly=args!=null&&"true".equals(args.getString("freshnessOnly"));
        resultMapOnly=args!=null&&"true".equals(args.getString("resultMapOnly"));
        storageOverviewOnly=args!=null&&"true".equals(args.getString("storageOverviewOnly"));
        nestedMapOnly=args!=null&&"true".equals(args.getString("nestedMapOnly"));
        mapOnly=args!=null&&"true".equals(args.getString("mapOnly"));
        navigationOnly=args!=null&&"true".equals(args.getString("navigationOnly"));
        navigationViaTap=args!=null&&"true".equals(args.getString("navigationViaTap"));
        menuDragOnly=args!=null&&"true".equals(args.getString("menuDragOnly"));
        ambientOnly=args!=null&&"true".equals(args.getString("ambientOnly"));
        pullOnly=args!=null&&"true".equals(args.getString("pullOnly"));
        filterGridOnly=args!=null&&"true".equals(args.getString("filterGridOnly"));
        contextOnly=args!=null&&"true".equals(args.getString("contextOnly"));
        paneOnly=args!=null&&"true".equals(args.getString("paneOnly"));
        searchMenuOnly=args!=null&&"true".equals(args.getString("searchMenuOnly"));
        searchMenuBeforeOnly=args!=null&&"true".equals(args.getString("searchMenuBeforeOnly"));
        toolbarOnly=args!=null&&"true".equals(args.getString("toolbarOnly"));
        treeOnly=args!=null&&"true".equals(args.getString("treeOnly"));
        viewerHandoffOnly=args!=null&&"true".equals(args.getString("viewerHandoffOnly"));start(); }
    @Override public void callActivityOnResume(Activity resumed){super.callActivityOnResume(resumed);if(resumed instanceof MainActivity)activity=(MainActivity)resumed;
        else if(resumed instanceof FileViewerActivity)viewer=(FileViewerActivity)resumed;}
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
        if(menuDragOnly||folderSizeOnly||typoOnly||gridSelectionOnly||quickPopupOnly||previewWindowsOnly||bulkDeleteOnly||freshnessOnly||navigationOnly||paneOnly||contextOnly||treeOnly||toolbarOnly||searchMenuBeforeOnly||searchMenuOnly||nestedMapOnly||storageOverviewOnly||resultMapOnly){
            for(String name:new String[]{"Budget.csv","Packing list.txt","Workshop notes.md","Meeting agenda.txt","Reading list.txt",
                    "Schedule.csv","Recipe notes.md","Travel checklist.txt","Project brief.txt","Weekly plan.md"})
                write(new File(documents,name),"Synthetic UI fixture.\n");
            if(quickPopupOnly) {
                StringBuilder preview=new StringBuilder("Synthetic UI fixture.\n");
                for(int line=0;line<240;line++)preview.append("Scrollable owned text, line ").append(line).append('\n');
                write(new File(documents,"Packing list.txt"),preview.toString());
                write(new File(documents,"Pager Alpha.txt"),"owned short first");
                check(new File(documents,"Pager Folder").mkdir(),"pager folder fixture");
                write(new File(documents,"Pager Gamma.txt"),preview.toString());
                copyAsset("Saturn.jpg",new File(documents,"Pager Zeta.jpg"));
            }
            write(new File(documents,"Opaque artifact.qafx"),"Not a supported format");
            if(contextOnly)write(new File(documents,"Sandbox.qafx"),"Second unsupported file with a different size");
            copyAsset("Earthrise.jpg",new File(documents,"Earthrise.jpg"));
            copyAsset("Earthrise.mp4",new File(documents,"Earthrise.mp4"));
        }else for (int n = 0; n < (viewerHandoffOnly?80:2300); n++)
            write(new File(documents, String.format(java.util.Locale.ROOT, "Notes %04d.txt", n)), "Local search fixture\n");
        if(folderSizeOnly){
            File sized=new File(documents,"Sized fixture"), sizedChild=new File(sized,".hidden");
            check(sizedChild.mkdirs()&&new File(documents,"Empty fixture").mkdir(),"size probe folders");
            check(new File(sized,"part-one.bin").createNewFile()&&new File(sizedChild,".part-two.bin").createNewFile(),
                    "size probe files");
            try(java.io.RandomAccessFile first=new java.io.RandomAccessFile(new File(sized,"part-one.bin"),"rw");
                java.io.RandomAccessFile second=new java.io.RandomAccessFile(new File(sizedChild,".part-two.bin"),"rw")){
                first.setLength(1600);second.setLength(1137);
            }
        }
        if(nestedMapOnly){
            try(java.io.RandomAccessFile biggest=new java.io.RandomAccessFile(new File(documents,"Lab capture.txt"),"rw")){
                biggest.writeBytes("Owned Map fixture"); biggest.setLength(96L<<20);
            }
            check(new File(documents,"Zero bytes.txt").createNewFile(),"zero byte map fixture");
        }
        write(new File(documents, "Trip checklist.md"), "# Trip\nCamera\nCharger\n");
        if(bulkDeleteOnly){
            write(new File(documents,"AA Bulk delete sample.txt"),"owned first");
            write(new File(documents,"AB Bulk delete sample.txt"),"owned second");
        }
        if(previewWindowsOnly){
            write(new File(documents,"Same note.txt"),"Private synthetic alpha");
            write(new File(pictures,"Same note.txt"),"Private synthetic bravo");
        }
        index = FileIndex.forProbe(target, fixture, id); index.setRootEnabled(false);
        if(freshnessOnly)index.setProbeLiveWatching(false);
        index.startScan();
        await(() -> index.getState().loaded && !index.getState().scanning && index.getState().lastScanMillis > 0, "fixture index built");
        check(index.getState().entries.size() >= (menuDragOnly||folderSizeOnly||typoOnly||gridSelectionOnly||quickPopupOnly||previewWindowsOnly||bulkDeleteOnly||freshnessOnly||navigationOnly||paneOnly||contextOnly||treeOnly||toolbarOnly||searchMenuBeforeOnly||searchMenuOnly||nestedMapOnly||storageOverviewOnly||resultMapOnly?28:70), "only bounded fixture indexed");
        runOnMainSync(() -> MainActivity.setProbeIndex(index));
        launch(); waitResults();
        if(folderSizeOnly){verifyFolderSize();return;}
        if(typoOnly){verifyTypoSearch();return;}
        if(gridSelectionOnly){verifyGridSelection();return;}
        if(previewWindowsOnly){verifyPreviewWindows(documents,pictures);return;}
        if(quickPopupOnly){verifyQuickPopup(documents);return;}
        if(freshnessOnly){verifyVisibleFreshness(documents,deep);return;}
        if(bulkDeleteOnly){verifyBulkDelete(documents,pictures);return;}
        if(searchMenuOnly){verifySearchOptionsMenu();return;}
        if(searchMenuBeforeOnly){
            setQuery("file: guide ext:pdf");runOnMainSync(()->activity.findViewById(R.id.search_options).performClick());
            await(()->activeTextStartsWith("Regular expressions"),"existing Search options displays its four matching rows");
            capture("search-options-before.png");clickText("Done");return;
        }
        if(menuDragOnly){verifyMenuDrag(documents);return;}
        if(ambientOnly){verifyAmbient();return;}
        if(pullOnly){verifyPull(documents);return;}
        if(filterGridOnly){verifyFilterGrid();return;}
        if(toolbarOnly){verifyToolbarMenus();return;}
        if(contextOnly){verifyItemMenus(documents,pictures);return;}
        if(viewerHandoffOnly){verifyViewerHandoff(documents);return;}
        if(navigationOnly){recoverNavigation(documents);return;}
        if(paneOnly){verifyVisiblePanes(documents,pictures);return;}
        if(treeOnly){verifyFolderTree(documents,pictures,deep);return;}
        if(pinchOnly){pinchDensity();return;}
        if(storageOverviewOnly){verifyStorageOverview(documents);return;}
        if(nestedMapOnly){verifyNestedMap(documents);return;}
        if(resultMapOnly){verifyResultMap(pictures);return;}
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
                    new StorageMapData.Scale(0, 1L << 30), entry -> {}, (children,entry) -> false,
                    (anchor,entry) -> false, entry -> {});
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
            map.show("/short-map", tiles, new StorageMapData.Scale(0, 1L << 30), entry -> {}, (children,entry) -> false,
                    (anchor,entry) -> false, entry -> {});
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
            map.show("/short-map", tiles, new StorageMapData.Scale(0, 1L << 30), entry -> {}, (children,entry) -> false,
                    (anchor,entry) -> false, entry -> {});
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
                    new StorageMapData.Tile(small, smallBytes)), new StorageMapData.Scale(0, 1L << 30), entry -> {}, (children,entry) -> false,
                    (anchor,entry) -> false, entry -> {});
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
                map.show("/map", tiles, new StorageMapData.Scale(0, 1L << 30), entry -> {}, (children,entry) -> false,
                    (anchor,entry) -> false, entry -> {});
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

    private void verifyPreviewWindows(File documents,File pictures)throws Exception{
        File a=new File(documents,"Same note.txt"),b=new File(pictures,"Same note.txt");
        openAndMinimizeFixture(a);
        setQuery("");
        runOnMainSync(activity::onBackPressed);
        await(()->onMain(()->field(activity,"folderScope")==null),"Back returns from the first scoped folder");
        waitResults();
        openAndMinimizeFixture(b);
        check(onMain(()->((List<?>)field(activity,"minimizedPreviews")).size()==2),
                "same filename from separate paths stays in two preview windows");
        MainActivity old=activity;
        runOnMainSync(old::recreate);
        await(()->activity!=old&&onMain(()->activity.hasWindowFocus()
                &&((List<?>)field(activity,"minimizedPreviews")).size()==2),"both previews survive activity recreation");
        runOnMainSync(()->activity.findViewById(R.id.workspace_switcher).performClick());
        clickText("Resume 2. Same note.txt · "+fixture.getName()+"/Documents");
        await(()->viewer!=null&&onMain(()->viewer.hasWindowFocus()&&a.getPath().equals(field(viewer,"path"))),
                "switcher resumes the older same-named file by its own path");
        await(()->onMain(()->containsTextFragment(viewer.getWindow().getDecorView(),"Private synthetic alpha")),
                "older preview shows its own content");
        runOnMainSync(viewer::onBackPressed);
        await(()->onMain(()->activity.hasWindowFocus()&&((List<?>)field(activity,"minimizedPreviews")).size()==1),
                "closing one preview preserves the other");
        runOnMainSync(()->activity.findViewById(R.id.workspace_switcher).performClick());
        clickText("Resume 1. Same note.txt · "+fixture.getName()+"/Photos");
        await(()->viewer!=null&&onMain(()->viewer.hasWindowFocus()&&b.getPath().equals(field(viewer,"path"))
                &&containsTextFragment(viewer.getWindow().getDecorView(),"Private synthetic bravo")),
                "the remaining same-named file reopens with distinct content");
        runOnMainSync(viewer::onBackPressed);
        await(()->onMain(()->activity.hasWindowFocus()&&!activity.findViewById(R.id.preview_resume).isShown()),
                "leaving the last preview clears the dock");
    }

    private void openAndMinimizeFixture(File file)throws Exception{
        openFolder(file.getParentFile());setQuery(file.getName());waitResults();
        runOnMainSync(()->{
            AbsListView results=activity.findViewById(R.id.results);
            for(int i=0;i<results.getCount();i++){
                Object entry=results.getItemAtPosition(i);
                if(entry instanceof FileEntry&&file.getPath().equals(((FileEntry)entry).path())){
                    results.performItemClick(results.getChildAt(i-results.getFirstVisiblePosition()),i,results.getItemIdAtPosition(i));
                    return;
                }
            }
            throw new AssertionError("same-named fixture not found in its own folder");
        });
        await(()->viewer!=null&&onMain(()->viewer.hasWindowFocus()&&file.getPath().equals(field(viewer,"path"))),
                "open the correct same-named fixture");
        runOnMainSync(()->clickDescription(viewer.getWindow().getDecorView(),"More file actions"));
        clickText("Minimize preview");
        await(()->onMain(()->activity.hasWindowFocus()&&activity.findViewById(R.id.preview_resume).isShown()),
                "background preview is docked");
    }

    private static boolean containsTextFragment(View view,String expected){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(expected))return true;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)if(containsTextFragment(group.getChildAt(i),expected))return true;
        }
        return false;
    }

    private void verifyViewerHandoff(File documents) throws Exception {
        File guide=new File(documents,"Guide.pdf");
        setQuery("Guide.pdf");
        runOnMainSync(()->{
            AbsListView list=activity.findViewById(R.id.results);
            list.performItemClick(null,0,0);
        });
        await(()->viewer!=null&&onMain(()->viewer.hasWindowFocus()&&(Integer)field(viewer,"pageCount")>=2),"viewer opens the fixture PDF");
        FileViewerActivity original=viewer;
        runOnMainSync(()->clickDescription(original.getWindow().getDecorView(),"More file actions"));
        clickText("Properties");
        check(onMain(()->{
            AppSheet properties=(AppSheet)field(original,"activeSheet");
            return properties!=null&&properties.isShowing()&&containsTextMain(properties.getWindow().getDecorView(),"Guide.pdf");
        }),"file overflow shows the current file's properties");
        runOnMainSync(()->((AppSheet)field(original,"activeSheet")).dismiss());waitForIdleSync();
        runOnMainSync(()->clickDescription(original.getWindow().getDecorView(),"More file actions"));
        clickText("Viewer guide");
        await(()->onMain(()->field(original,"viewerGuide")!=null),"viewer guide opens from More");
        clickText("Topics");
        check(onMain(()->containsTextMain(original.getWindow().getDecorView(),"Guide topics")),"viewer guide has a topic picker");
        clickText("Controls for this file");
        check(onMain(()->containsTextMain(original.getWindow().getDecorView(),"Change PDF pages")
                &&field(field(original,"viewerGuide"),"anchor")==field(original,"formatGuideTarget")),
                "PDF topic spotlights the actual page toolbar");
        runOnMainSync(original::onBackPressed);
        check(onMain(()->field(original,"viewerGuide")==null),"Back dismisses the viewer guide without leaving the file");
        runOnMainSync(()->original.findViewById(R.id.preview_focus).performClick());
        check(onMain(()->((View)field(original,"navigationDock")).getVisibility())==View.GONE,"focus hides the file-action dock");
        runOnMainSync(original::onBackPressed);
        check(onMain(()->((View)field(original,"navigationDock")).getVisibility())==View.VISIBLE,"Back exits focus without closing the file");
        runOnMainSync(()->clickDescription(original.getWindow().getDecorView(),"Next page"));
        await(()->onMain(()->(Integer)field(original,"page")==1),"PDF moves to page two");
        runOnMainSync(()->clickDescription(original.getWindow().getDecorView(),"More file actions"));
        clickText("Minimize preview");
        await(()->onMain(()->activity.hasWindowFocus()&&activity.findViewById(R.id.preview_resume).isShown()),"main screen offers the minimized preview");
        Bundle paused=onMain(()->(Bundle)((List<?>)field(activity,"minimizedPreviews")).get(0));
        check(paused!=null&&paused.getInt("page")==1&&!paused.getBoolean("playing")&&!paused.containsKey("fileUri"),
                "dock preserves position without autoplay or a granted URI");
        runOnMainSync(()->activity.findViewById(R.id.preview_resume).performClick());
        await(()->viewer!=original&&onMain(()->viewer.hasWindowFocus()&&(Integer)field(viewer,"pageCount")>=2),"resume reopens the PDF");
        check(onMain(()->(Integer)field(viewer,"page")==1),"resumed PDF stays on page two");
        runOnMainSync(()->viewer.findViewById(R.id.preview_path).performClick());
        clickText("Show in Files");waitResults();
        check(onMain(()->!((Boolean)field(activity,"storageMap"))&&((Spinner)activity.findViewById(R.id.category))
                .getSelectedItem().toString().equals("File Manager")),"reveal opens the Files view");
        check(documents.getPath().equals(onMain(()->field(activity,"folderScope"))),"reveal opens the immediate parent");
        check(guide.getPath().equals(onMain(()->field(activity,"revealedPreviewPath"))),"reveal highlights the same file, not another result");
        check(guide.isFile(),"viewer handoff leaves the original file in place");
    }

    private void recoverNavigation(File documents)throws Exception{
        setQuery("folder: Documents");
        runOnMainSync(()->{AbsListView list=activity.findViewById(R.id.results);list.performItemClick(null,0,0);});waitResults();
        capture("recovery-search.png");
        check(onMain(()->((View)activity.findViewById(R.id.query)).getHeight()>0),"compact search bar visible");
        check(onMain(()->{
            View query=activity.findViewById(R.id.query);
            View searchRow=(View)((View)query.getParent()).getParent();
            android.view.ViewGroup chrome=(android.view.ViewGroup)searchRow.getParent();
            return chrome.getChildAt(chrome.indexOfChild(searchRow)+1)==field(activity,"workspaceStrip");
        }),"Search has no decorative line that looks like progress");
        check(onMain(()->{
            android.widget.ProgressBar progress=(android.widget.ProgressBar)field(activity,"progress");
            return progress.getParent()!=null&&progress.isIndeterminate()&&progress.getVisibility()==View.GONE;
        }),"the real scan progress remains separate and hidden when idle");
        if(navigationViaTap)runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());
        else openSidebarGesture();
        await(()->onMain(()->field(activity,"storageDrawer")!=null),
                navigationViaTap?"sidebar opened with its button":"sidebar opened with inner-edge swipe");
        await(()->onMain(()->{
            StorageDrawer drawer=(StorageDrawer)field(activity,"storageDrawer");
            return drawer!=null&&drawer.isShowing()&&drawer.getWindow().getDecorView().getWidth()>0
                    &&drawer.getWindow().getDecorView().getWidth()<activity.getWindow().getDecorView().getWidth();
        }),"drawer is a side panel rather than a bottom sheet");
        check(onMain(()->{
            StorageDrawer drawer=(StorageDrawer)field(activity,"storageDrawer");
            return drawer.findAction("Files").getHeight()>=Math.round(48*activity.getResources().getDisplayMetrics().density);
        }),"sidebar uses a full 48 dp Files touch row");
        capture("recovery-sidebar.png");
        clickText("Files");waitResults();
        check(onMain(()->!((Boolean)field(activity,"storageMap"))&&((Spinner)activity.findViewById(R.id.category))
                .getSelectedItem().toString().equals("File Manager")),"Files view opens from the drawer");
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"Files keeps the selected folder");
        check(onMain(()->((TextView)field(activity,"folderPath")).getText().toString().endsWith("Documents")
                &&!((TextView)field(activity,"folderPath")).getText().toString().startsWith("/data/")),"folder breadcrumb hides implementation path");
        check(onMain(()->{
            Spinner picker=activity.findViewById(R.id.category);
            View row=picker.getSelectedView();
            return row instanceof LinearLayout&&"Files".contentEquals(((TextView)((LinearLayout)row).getChildAt(1)).getText());
        }),"selected Files label stays compact");
        check(onMain(()->{
            android.content.res.Configuration config=activity.getResources().getConfiguration();
            EditText search=activity.findViewById(R.id.query);
            boolean compact=config.screenWidthDp<=340||config.screenWidthDp<=380&&config.fontScale>=1.2f;
            return (compact?"Search":"Search in Documents").contentEquals(search.getHint())
                    &&("Search in "+documents.getPath()).contentEquals(search.getContentDescription());
        }),"visible hint fits the width while accessibility retains full folder scope");
        capture("recovery-files.png");
        runOnMainSync(()->((View)field(activity,"mapListButton")).performClick());waitResults();
        check(onMain(()->(Boolean)field(activity,"storageMap")),"folder toolbar switches directly to Map");
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"direct switch keeps the folder");
        capture("recovery-map.png");
        int ticket=onMain(()->((java.util.concurrent.atomic.AtomicInteger)field(activity,"sequence")).get());
        setQuery("Guide.pdf");
        await(()->onMain(()->((AbsListView)activity.findViewById(R.id.results)).getCount()==1),"Map filter returns one result");
        check(onMain(()->((java.util.concurrent.atomic.AtomicInteger)field(activity,"sequence")).get())==ticket,"Map filter does not rescan");
        runOnMainSync(()->activity.findViewById(R.id.workspace_switcher).performClick());
        clickText("New tab");waitResults();
        setQuery("Saturn");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Move tab 2"));
        clickText("Move earlier");
        await(()->onMain(()->(Integer)field(activity,"workspace")==0),"move action puts selected tab first");
        check(onMain(()->"Saturn".contentEquals(((TextView)activity.findViewById(R.id.query)).getText())),"tab move preserves query");
        runOnMainSync(()->activity.findViewById(R.id.workspace_switcher).performClick());
        clickText("2. "+onMain(()->workspaceLabel(1)));
        await(()->onMain(()->(Boolean)field(activity,"storageMap")
                &&documents.getPath().equals(field(activity,"folderScope"))),"window switcher returns to the same map folder");
        runOnMainSync(()->activity.findViewById(R.id.workspace_switcher).performClick());
        clickText("1. "+onMain(()->workspaceLabel(0)));waitResults();
        runOnMainSync(()->activity.findViewById(R.id.workspace_switcher).performClick());
        clickText("Close tab");waitResults();
        check(onMain(()->(Boolean)field(activity,"storageMap")),"closing tab restores Map");
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"closing tab restores previous folder");
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());clickText("Guided tour");
        await(()->onMain(()->field(activity,"spotlightTour")!=null),"tour spotlight opens");
        capture("recovery-tour-search.png");
        clickText("Next");clickText("Next");
        check(onMain(()->field(activity,"storageDrawer")!=null),"tour opens and highlights the actual Files sidebar row");
        capture("recovery-tour-sidebar.png");
        clickText("Back");
        await(()->onMain(()->field(activity,"storageDrawer")==null),"tour Back returns to toolbar");
        clickText("Skip");
        check(onMain(()->field(activity,"spotlightTour")==null),"guided tour may be skipped after going back");
        String scopeBefore=onMain(()->(String)field(activity,"folderScope"));
        String queryBefore=onMain(()->((TextView)activity.findViewById(R.id.query)).getText().toString());
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());clickText("Guided tour");
        clickText("Topics");clickText("Map, Sort & View");
        clickText("Next");
        check(onMain(()->field(field(activity,"spotlightTour"),"anchor")==activity.findViewById(R.id.view_mode)),
                "Map your search results spotlights the real View button");
        clickText("Next");clickText("Next");clickText("Next");
        check(onMain(()->field(field(activity,"spotlightTour"),"anchor")==activity.findViewById(R.id.sort)),
                "Map, Sort & View spotlights the real Sort button");
        clickText("Next");
        check(onMain(()->field(field(activity,"spotlightTour"),"anchor")==activity.findViewById(R.id.view_mode)),
                "Map, Sort & View spotlights the real View button");
        clickText("Done");
        check(onMain(()->containsTextMain(activity.getWindow().getDecorView(),"Guide topics")),
                "finishing a topic returns to its replayable topic picker");
        clickText("Close");
        check(onMain(()->field(activity,"spotlightTour")==null&&field(activity,"storageDrawer")==null
                &&java.util.Objects.equals(scopeBefore,field(activity,"folderScope"))
                &&queryBefore.contentEquals(((TextView)activity.findViewById(R.id.query)).getText())),
                "tour does not change the current folder or query");
    }

    private void openSidebarGesture()throws Exception{
        float[] points=onMain(()->{
            View decor=activity.getWindow().getDecorView();
            int[] at=new int[2];decor.getLocationOnScreen(at);
            int edge=0;
            if(android.os.Build.VERSION.SDK_INT>=29&&decor.getRootWindowInsets()!=null)
                edge=decor.getRootWindowInsets().getSystemGestureInsets().left;
            float density=activity.getResources().getDisplayMetrics().density;
            return new float[]{at[0]+edge+density*8,at[1]+decor.getHeight()*.52f,density*108};
        });
        long first=SystemClock.uptimeMillis();
        for(int i=0;i<=10;i++){
            int action=i==0?android.view.MotionEvent.ACTION_DOWN:i==10?android.view.MotionEvent.ACTION_UP:android.view.MotionEvent.ACTION_MOVE;
            android.view.MotionEvent event=android.view.MotionEvent.obtain(first,SystemClock.uptimeMillis(),action,
                    points[0]+(points[2]*i/10),points[1],0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{check(getUiAutomation().injectInputEvent(event,true),"synthetic swipe frame "+i);}finally{event.recycle();}
            SystemClock.sleep(30);
        }
    }

    private void verifyFolderSize()throws Exception{
        for(String name:new String[]{"Sized fixture","Empty fixture"}){
            setQuery("\""+name+"\"");waitResults();
            await(()->onMain(()->visibleNamedView(activity.findViewById(R.id.results),name)!=null),
                    "search finds "+name);
            hold(onMain(()->resultFor(name)));
            clickText("Properties");
            String expected=name.startsWith("Sized")
                    ?java.text.NumberFormat.getIntegerInstance().format(2737)+" bytes"
                    :android.text.format.Formatter.formatFileSize(target,0);
            await(()->activeTextStartsWith("Size")&&activeTextContaining(expected),
                    "Properties reports "+expected+" for "+name);
            sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            await(()->!activeTextStartsWith("Copy path"),"dismiss Properties");
        }
    }

    private boolean activeTextContaining(String text){
        for(android.view.accessibility.AccessibilityWindowInfo window:getUiAutomation().getWindows()){
            android.view.accessibility.AccessibilityNodeInfo root=window.getRoot();
            if(root==null)continue;
            try{
                boolean found=false;
                for(android.view.accessibility.AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
                    if(node.isVisibleToUser()&&node.getText()!=null&&node.getText().toString().contains(text))found=true;
                    node.recycle();
                }
                if(found)return true;
            }finally{root.recycle();}
        }
        return false;
    }

    private void verifyTypoSearch()throws Exception{
        setQuery("budegt.csv");waitResults();
        await(()->onMain(()->visibleNamedView(activity.findViewById(R.id.results),"Budget.csv")!=null),
                "ordinary misspelling with exact suffix finds the synthetic CSV by default");
        setQuery("budegt.pdf");waitResults();
        check(onMain(()->visibleNamedView(activity.findViewById(R.id.results),"Budget.csv")==null),
                "typed extensions do not broaden");
        setQuery("budegt ext:csv");waitResults();
        check(onMain(()->visibleNamedView(activity.findViewById(R.id.results),"Budget.csv")==null),
                "explicit query operator remains strict");
        setQuery("budegt");waitResults();
        runOnMainSync(()->activity.findViewById(R.id.search_options).performClick());
        await(()->onMain(()->toolbarItemChecked("Typo-tolerant search")),"typo matching enabled for an existing profile with no saved choice");
        clickText("Typo-tolerant search");waitResults();
        await(()->onMain(()->!toolbarItemChecked("Typo-tolerant search")
                &&visibleNamedView(activity.findViewById(R.id.results),"Budget.csv")==null),
                "disabling typo matching updates results immediately");
        check(!target.getSharedPreferences("ui",0).getBoolean("tolerateTypos",true),"off choice persists");
        clickText("Reset matching options");waitResults();
        await(()->onMain(()->toolbarItemChecked("Typo-tolerant search")
                &&visibleNamedView(activity.findViewById(R.id.results),"Budget.csv")!=null),
                "reset returns to default on and restores the fallback result");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->field(activity,"navigationMenu")==null),"close Search options");
        setQuery("budget");waitResults();
        await(()->onMain(()->visibleNamedView(activity.findViewById(R.id.results),"Budget.csv")!=null),
                "normal matching remains available");
        check(onMain(()->!(Boolean)field(activity,"typoResults")),"ordinary match is not marked similar");
    }

    private void verifySearchOptionsMenu()throws Exception{
        setQuery("file: guide ext:pdf");waitResults();
        await(()->onMain(()->visibleNamedView(activity.findViewById(R.id.results),"Guide.pdf")!=null),
                "lowercase search shows the synthetic capitalized PDF by default");
        runOnMainSync(()->activity.findViewById(R.id.search_options).performClick());
        await(()->onMain(()->{
            Object menu=field(activity,"navigationMenu");
            return menu!=null&&field(menu,"anchor")==activity.findViewById(R.id.search_options);
        }),"Search options is anchored to its toolbar button, not a centered dialog");
        check(onMain(()->!toolbarItemChecked("Match case")),"case option is off by default");
        clickText("Match case");waitResults();
        await(()->onMain(()->toolbarItemChecked("Match case")
                &&((AbsListView)activity.findViewById(R.id.results)).getCount()==0),
                "toggle checks its row and applies immediately without closing the menu");
        clickText("Whole words");waitResults();
        await(()->onMain(()->toolbarItemChecked("Match case")&&toolbarItemChecked("Whole words")),
                "menu supports multiple independent options without reopening");
        check(onMain(()->"Search options, 2 active".contentEquals(activity.findViewById(R.id.search_options).getContentDescription())),
                "toolbar announces and indicates two active options");
        capture("search-options-after.png");
        clickText("Reset matching options");waitResults();
        await(()->onMain(()->{
            Object menu=field(activity,"navigationMenu");
            return menu!=null&&!toolbarItemChecked("Match case")&&!toolbarItemChecked("Whole words")
                    &&visibleNamedView(activity.findViewById(R.id.results),"Guide.pdf")!=null;
        }),"Reset restores default matching, results and keeps the menu open");
        check(onMain(()->"Search options".contentEquals(activity.findViewById(R.id.search_options).getContentDescription())),
                "reset clears the active badge");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->field(activity,"navigationMenu")==null),"Back closes the options without changing the query");

        setQuery("ext:json Folder One");waitResults();
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());clickText("Search");
        clickText("Include folder paths");waitResults();
        await(()->onMain(()->toolbarItemChecked("Include folder paths")
                &&((AbsListView)activity.findViewById(R.id.results)).getCount()>0),
                "More → Search directly applies path matching to the same query");
        capture("more-search-after.png");
        clickText("Regular expressions");waitResults();
        check(onMain(()->toolbarItemChecked("Regular expressions")&&toolbarItemChecked("Include folder paths")),
                "regex is still available and independent in the second entry point");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->field(activity,"navigationMenu")==null),"both Back levels dismiss normally");
        runOnMainSync(()->activity.findViewById(R.id.search_options).performClick());
        await(()->onMain(()->toolbarItemChecked("Include folder paths")&&toolbarItemChecked("Regular expressions")),
                "both entry points share the same saved matching state");
        clickText("Reset matching options");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
    }

    private void verifyFilterGrid()throws Exception{
        Spinner category=activity.findViewById(R.id.category);
        runOnMainSync(category::performClick);
        await(()->onMain(()->{
            Object popup=field(activity,"navigationMenu");
            return popup!=null&&field(popup,"list") instanceof android.widget.GridView
                    &&((android.widget.GridView)field(popup,"list")).getChildCount()>1;
        }),"the category control opens its grid");
        android.widget.GridView grid=onMain(()->(android.widget.GridView)field(field(activity,"navigationMenu"),"list"));
        check(onMain(()->grid.getNumColumns()==2&&grid.getChildAt(0).getTop()==grid.getChildAt(1).getTop()),
                "first two categories are side by side");
        int[] a=screenPoint(onMain(()->grid.getChildAt(0))),b=screenPoint(onMain(()->grid.getChildAt(1)));
        long began=SystemClock.uptimeMillis();
        injectHeld(began,MotionEvent.ACTION_DOWN,a);
        injectHeld(began,MotionEvent.ACTION_MOVE,b);
        await(()->onMain(()->grid.getChildAt(1).isPressed()&&category.getSelectedItemPosition()==0),
                "horizontal category drag only highlights before release");
        injectHeld(began,MotionEvent.ACTION_UP,b);
        await(()->onMain(()->category.getSelectedItemPosition()==1&&field(activity,"navigationMenu")==null),
                "release chooses the second category");
        runOnMainSync(category::performClick);clickText("File types…");
        clickText("Clear");
        check(onMain(()->{
            MenuDialog popup=(MenuDialog)field(activity,"navigationMenu");
            return popup!=null&&!((View)field(popup,"applyButton")).isEnabled()&&!toolbarItemChecked("Photos");
        }),"empty staged filter does not apply");
        clickText("Photos");clickText("Documents");
        check(onMain(()->toolbarItemChecked("Photos")&&toolbarItemChecked("Documents")),
                "multiple types can be toggled without reopening");
        clickText("Photos");
        check(onMain(()->!toolbarItemChecked("Photos")&&toolbarItemChecked("Documents")),
                "deselect updates only that type");
        clickText("Photos");clickText("Apply");waitResults();
        check(onMain(()->Integer.valueOf((1<<4)|(1<<6)).equals(field(activity,"multiTypes"))),
                "Apply commits exactly the staged document and photo mask");
        runOnMainSync(category::performClick);clickText("File types…");clickText("All");clickText("Apply");waitResults();
        check(onMain(()->Integer.valueOf(0).equals(field(activity,"multiTypes"))),
                "All and Apply return to unrestricted Search");
    }

    private void verifyPull(File documents)throws Exception{
        openFolder(documents);
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());
        clickText("Files");waitResults();
        View results=onMain(()->activity.findViewById(R.id.results));
        int[] point=onMain(()->{
            int[] origin=new int[2];results.getLocationOnScreen(origin);
            return new int[]{origin[0]+results.getWidth()/2,origin[1]+Math.round(24*activity.getResources().getDisplayMetrics().density)};
        });
        int before=onMain(()->((java.util.concurrent.atomic.AtomicInteger)field(activity,"sequence")).get());
        int end=point[1]+Math.round(112*activity.getResources().getDisplayMetrics().density);
        java.util.concurrent.atomic.AtomicBoolean toast=new java.util.concurrent.atomic.AtomicBoolean();
        getUiAutomation().setOnAccessibilityEventListener(event->{
            if(event.getEventType()==android.view.accessibility.AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED)
                for(CharSequence line:event.getText())if(line!=null&&line.toString().contains("Checking folder"))toast.set(true);
        });
        try{
            runOnMainSync(()->{
                long time=SystemClock.uptimeMillis();
                pullEvent(time,time,MotionEvent.ACTION_DOWN,point[0],point[1]);
                pullEvent(time,time+55,MotionEvent.ACTION_MOVE,point[0],point[1]+(end-point[1])/2);
                pullEvent(time,time+110,MotionEvent.ACTION_MOVE,point[0],end);
                pullEvent(time,time+120,MotionEvent.ACTION_UP,point[0],end);
                View pulse=(View)field(activity,"refreshPulse");
                check(pulse.getVisibility()==View.VISIBLE&&!pulse.isClickable(),"accepted pull shows a passive ring");
            });
            await(()->onMain(()->((java.util.concurrent.atomic.AtomicInteger)field(activity,"sequence")).get()>before),
                    "folder refresh still starts a real query");
            await(()->onMain(()->((View)field(activity,"refreshPulse")).getVisibility()==View.GONE),
                    "refresh ring disappears by itself");
            check(!toast.get(),"pull does not emit a routine checking toast");
        }finally{getUiAutomation().setOnAccessibilityEventListener(null);}
    }

    private void pullEvent(long start,long time,int action,int x,int y){
        MotionEvent e=MotionEvent.obtain(start,time,action,x,y,0);
        e.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{activity.dispatchTouchEvent(e);}finally{e.recycle();}
    }

    private void verifyAmbient()throws Exception{
        int actual=AmbientPalette.systemTone(target);
        if(android.os.Build.VERSION.SDK_INT<31)
            check(actual==AmbientPalette.COOL_BLUE,"older Android uses the pale blue fallback");
        else check(actual==target.getColor(android.R.color.system_accent1_500),
                "newer Android consumes the public system tone without image access");
        for(boolean dark:new boolean[]{false,true}){
            int cool=AmbientPalette.surface(dark,0xff367cf0),warm=AmbientPalette.surface(dark,0xffbc3a6d);
            check(cool!=warm,"surface changes gently with the chosen system tone");
            int foreground=dark?0xffb3b6bd:0xff4c607c;
            for(int background:new int[]{cool,warm,AmbientPalette.chrome(dark,actual)}){
                float a=android.graphics.Color.luminance(foreground),b=android.graphics.Color.luminance(background);
                check((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5,"muted text remains readable on tinted surfaces");
            }
        }
        check(onMain(()->{
            UiTheme dark=(UiTheme)field(activity,"theme");
            return dark.surface==android.graphics.Color.BLACK&&dark.chrome==android.graphics.Color.BLACK;
        }),"dark activity canvas and chrome are pure AMOLED black even with wallpaper tint on");
        capture("ambient-dark.png");
        MainActivity old=activity;overflow("Appearance","Wallpaper tint");
        await(()->activity!=old&&onMain(()->activity.hasWindowFocus()
                &&((UiTheme)field(activity,"theme")).surface==android.graphics.Color.BLACK),"dark mode remains pure black after tint opt-out");
        MainActivity neutral=activity;overflow("Appearance","Wallpaper tint");
        await(()->activity!=neutral&&onMain(()->activity.hasWindowFocus()),"turning tint back on recreates the screen");
        MainActivity dark=activity;overflow("Appearance","Theme");clickText("Light");
        await(()->activity!=dark&&onMain(()->activity.hasWindowFocus()&&
                ((UiTheme)field(activity,"theme")).surface==AmbientPalette.surface(false,AmbientPalette.systemTone(activity))),
                "light ambient tint reaches the real activity");
        UiTheme light=onMain(()->(UiTheme)field(activity,"theme"));
        check(light.surface!=android.graphics.Color.WHITE&&light.surface!=light.chrome,
                "light mode uses distinct blue-tinted surfaces");
        for(int foreground:new int[]{light.ink,light.muted,light.accent}){
            for(int background:new int[]{light.surface,light.chrome,light.selected}){
                float a=android.graphics.Color.luminance(foreground),b=android.graphics.Color.luminance(background);
                check((Math.max(a,b)+.05)/(Math.min(a,b)+.05)>=4.5,
                        "light text and blue controls have 4.5:1 contrast across canvas, chrome and selection");
            }
        }
        capture("ambient-light.png");
    }

    private void verifyMenuDrag(File documents)throws Exception{
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());
        await(()->onMain(()->{
            Object popup=field(activity,"navigationMenu");
            return popup!=null&&((ListView)field(popup,"list")).getChildCount()>=2;
        }),"View menu contains the two choices");
        ListView list=onMain(()->(ListView)field(field(activity,"navigationMenu"),"list"));
        View first=onMain(()->list.getChildAt(0)),second=onMain(()->list.getChildAt(1));
        int[] from=screenPoint(first),to=screenPoint(second);
        long began=SystemClock.uptimeMillis();
        injectHeld(began,MotionEvent.ACTION_DOWN,from);SystemClock.sleep(220);
        injectHeld(began,MotionEvent.ACTION_MOVE,to);
        await(()->onMain(()->second.isPressed()&&!first.isPressed()),"highlight follows the finger");
        check(onMain(()->Integer.valueOf(0).equals(field(activity,"layoutMode"))),"dragging does not select before release");
        injectHeld(began,MotionEvent.ACTION_UP,to);
        await(()->onMain(()->Integer.valueOf(1).equals(field(activity,"layoutMode"))&&field(activity,"navigationMenu")==null),
                "releasing over List activates only List");

        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());
        await(()->onMain(()->field(activity,"navigationMenu")!=null),"View opens again for cancellation");
        ListView retry=onMain(()->(ListView)field(field(activity,"navigationMenu"),"list"));
        await(()->onMain(()->retry.getChildCount()>=2),"cancel test rows visible");
        SystemClock.sleep(160);
        int[] origin=screenPoint(onMain(()->retry.getChildAt(0)));
        int[] outside=onMain(()->{int[] point=new int[2];retry.getLocationOnScreen(point);return new int[]{Math.max(1,point[0]-Math.round(12*activity.getResources().getDisplayMetrics().density)),origin[1]};});
        long cancel=SystemClock.uptimeMillis();
        injectHeld(cancel,MotionEvent.ACTION_DOWN,origin);
        injectHeld(cancel,MotionEvent.ACTION_MOVE,outside);
        injectHeld(cancel,MotionEvent.ACTION_UP,outside);
        check(onMain(()->Integer.valueOf(1).equals(field(activity,"layoutMode"))),"leaving the menu never commits");
        if(onMain(()->field(activity,"navigationMenu")!=null))sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        openFolder(documents);

        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());
        await(()->onMain(()->field(activity,"storageDrawer")!=null
                &&((StorageDrawer)field(activity,"storageDrawer")).findAction("Files").isShown()),"drawer actions visible");
        StorageDrawer drawer=onMain(()->(StorageDrawer)field(activity,"storageDrawer"));
        View search=onMain(()->drawer.findAction("Search")),files=onMain(()->drawer.findAction("Files"));
        int[] searchAt=screenPoint(search),filesAt=screenPoint(files);long sidebar=SystemClock.uptimeMillis();
        injectHeld(sidebar,MotionEvent.ACTION_DOWN,searchAt);SystemClock.sleep(220);
        injectHeld(sidebar,MotionEvent.ACTION_MOVE,filesAt);
        await(()->onMain(files::isPressed),"drawer highlight follows touch");
        injectHeld(sidebar,MotionEvent.ACTION_UP,filesAt);waitResults();
        check(onMain(()->"File Manager".equals(((Spinner)activity.findViewById(R.id.category)).getSelectedItem().toString())),
                "drawer release opens Files rather than Search");

        setQuery("Opaque artifact");waitResults();
        hold(onMain(()->resultFor("Opaque artifact.qafx")));
        FileHoldActions held=onMain(()->(FileHoldActions)field(activity,"itemActions"));
        await(()->onMain(held::isOpen),"file action card is open after lifting");
        FileActionCard card=onMain(()->(FileActionCard)field(held,"card"));
        await(()->onMain(()->visibleNamedView(card,"Open")!=null&&visibleNamedView(card,"Properties")!=null),
                "two-column actions are visible");
        View open=onMain(()->visibleNamedView(card,"Open")),properties=onMain(()->visibleNamedView(card,"Properties"));
        int[] a=screenPoint(open),b=screenPoint(properties);long action=SystemClock.uptimeMillis();
        injectHeld(action,MotionEvent.ACTION_DOWN,a);SystemClock.sleep(220);
        injectHeld(action,MotionEvent.ACTION_MOVE,b);
        await(()->onMain(properties::isPressed),"fresh touch highlights the new file action");
        check(onMain(held::isOpen)&&viewer==null,"moving inside the file card does not run the first action");
        injectHeld(action,MotionEvent.ACTION_UP,b);
        await(()->activeTextStartsWith(new File(documents,"Opaque artifact.qafx").getPath()),"release opens only file Properties");
        closeProperties();
    }

    private static int pillColor(View view){
        android.graphics.drawable.RippleDrawable ripple=(android.graphics.drawable.RippleDrawable)view.getBackground();
        android.graphics.drawable.InsetDrawable inset=(android.graphics.drawable.InsetDrawable)ripple.getDrawable(0);
        return ((android.graphics.drawable.GradientDrawable)inset.getDrawable()).getColor().getDefaultColor();
    }

    private void verifyGridSelection()throws Exception{
        setQuery("file: ext:txt");
        check(onMain(()->!activity.findViewById(R.id.grid_select).isShown()),"List does not have the Grid command");
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());clickText("Grid");waitResults();
        await(()->onMain(()->{
            View select=activity.findViewById(R.id.grid_select);
            AbsListView grid=activity.findViewById(R.id.results);
            View mode=activity.findViewById(R.id.grid_mode_pill);
            float density=target.getResources().getDisplayMetrics().density;
            return grid instanceof android.widget.GridView&&grid.getChildCount()>=2
                    &&select.isShown()&&mode.isShown()&&select.getHeight()>=Math.round(48*density)
                    &&mode.getHeight()>=Math.round(48*density)&&grid.getTop()<=Math.round(24*density);
        }),"Grid presents a named, full-size Select touch target");
        check(onMain(()->"Select multiple files in grid".contentEquals(activity.findViewById(R.id.grid_select).getContentDescription())),
                "Grid Select explains its function to a screen reader");
        check(onMain(()->{
            Button select=activity.findViewById(R.id.grid_select),mode=activity.findViewById(R.id.grid_mode_pill);
            float density=target.getResources().getDisplayMetrics().density;
            return select.getParent()==mode.getParent()
                &&select.getCompoundDrawablePadding()<=Math.round(3*density)
                &&(select.getGravity()&Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK)==Gravity.START
                &&pillColor(select)==pillColor(mode);
        }),"Grid and Select share one neutral surface and checkbox sits beside its label");
        FileViewerActivity previous=viewer;
        tapGridControl(onMain(()->gridTile(0).findViewById(R.id.file_icon)));
        await(()->viewer!=null&&viewer!=previous,"Ordinary Grid tile touch still opens a preview");
        runOnMainSync(viewer::finish);await(()->onMain(activity::hasWindowFocus),"Grid regains input focus");
        int oldTop=onMain(()->activity.findViewById(R.id.results).getTop());
        tapGridControl(onMain(()->activity.findViewById(R.id.grid_select)));
        await(()->selectionLabel().startsWith("0 ")&&onMain(()->activity.findViewById(R.id.selection_bar).isShown()),
                "Select begins the existing batch mode without preselecting a file");
        check(onMain(()->activity.findViewById(R.id.results).getTop()==oldTop
                &&!activity.findViewById(R.id.grid_select).isShown()&&!activity.findViewById(R.id.grid_mode_pill).isShown()),
                "Selection removes the pills without moving the grid or reserving a whole new row");
        tapGridControl(onMain(()->gridTile(0).findViewById(R.id.file_icon)));
        await(()->selectionLabel().startsWith("1 ")&&onMain(()->gridTile(0).findViewById(R.id.file_selection_mark).isShown()),
                "Whole Grid tile selects and exposes its corner check");
        tapGridControl(onMain(()->gridTile(1).findViewById(R.id.file_icon)));
        await(()->selectionLabel().startsWith("2 ")&&onMain(()->gridTile(1).findViewById(R.id.file_selection_mark).isShown()),
                "Second tile joins the batch without opening a viewer");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Copy selected files"));
        await(()->FileClipboard.count(target)==2,"Existing Copy action receives both selected Grid files");
        FileClipboard.clear(target);
    }

    private View gridTile(int index){return ((AbsListView)activity.findViewById(R.id.results)).getChildAt(index);}

    private void tapGridControl(View view){
        float[] point=onMain(()->{
            int[] origin=new int[2];view.getLocationOnScreen(origin);
            return new float[]{origin[0]+view.getWidth()/2f,origin[1]+view.getHeight()/2f};
        });
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){
            android.view.MotionEvent event=android.view.MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,point[0],point[1],0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{if(!getUiAutomation().injectInputEvent(event,true))throw new AssertionError("Could not inject Grid touch");}
            finally{event.recycle();}
        }
        waitForIdleSync();
    }

    private void verifyToolbarMenus()throws Exception{
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());
        await(()->onMain(()->toolbarItemChecked("Name")&&toolbarItemChecked("Descending")),
                "Sort displays its current key and order");
        capture("toolbar-sort.png");
        clickText("Size");waitResults();
        check(onMain(()->Integer.valueOf(2).equals(field(activity,"sort"))),"choosing Size changes the actual sort key");
        runOnMainSync(()->activity.findViewById(R.id.sort).performClick());
        await(()->onMain(()->toolbarItemChecked("Size")&&!toolbarItemChecked("Name")),"Sort highlights only the chosen key");
        clickText("Descending");waitResults();
        check(!onMain(()->(Boolean)field(activity,"descending")),"Sort still toggles direction");

        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());
        await(()->onMain(()->toolbarItemChecked("Automatic")),"View has a distinct active layout");
        capture("toolbar-view.png");
        clickText("Grid");waitResults();
        check(onMain(()->activity.findViewById(R.id.results) instanceof android.widget.GridView),"View switches to Grid");
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());
        await(()->onMain(()->toolbarItemChecked("Grid")),"View reflects the selected Grid mode");
        clickText("Compact list");waitResults();
        check(onMain(()->Integer.valueOf(2).equals(field(activity,"layoutMode"))),"View switches from Grid to compact list");
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());
        clickText("Appearance");
        await(()->onMain(()->toolbarItemChecked("Show status")),"Other menu keeps its submenu and checked state");
        capture("toolbar-appearance.png");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->{
            Object popup=field(activity,"navigationMenu");
            return popup!=null&&((List<?>)field(popup,"levels")).size()==1;
        }),"Back returns to the top menu instead of leaving the page");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->field(activity,"navigationMenu")==null),"Back dismisses top-level menu");
    }

    private boolean toolbarItemChecked(String name){
        Object popup=field(activity,"navigationMenu");
        if(popup==null)return false;
        List<?> menus=(List<?>)field(popup,"levels");
        android.view.Menu menu=(android.view.Menu)menus.get(menus.size()-1);
        for(int i=0;i<menu.size();i++){
            android.view.MenuItem item=menu.getItem(i);
            if(name.contentEquals(item.getTitle()))return item.isChecked();
        }
        return false;
    }

    private void verifyResultMap(File pictures)throws Exception{
        Spinner categories=onMain(()->(Spinner)activity.findViewById(R.id.category));
        runOnMainSync(()->categories.setSelection(6));
        setQuery("Earth");
        waitResults();
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());
        clickText("Map results");
        StorageMapView map=onMain(()->(StorageMapView)field(activity,"mapView"));
        await(()->onMain(()->map.isShown()&&findVisibleMapTile(map,"Earth.jpg")!=null),"Photos search shows its own result map");
        check(onMain(()->{
            List<?> tiles=(List<?>)field(map,"tiles");
            return categories.getSelectedItemPosition()==6&&field(activity,"folderScope")==null
                    &&tiles.size()==3&&tiles.stream().allMatch(item->{
                        StorageMapData.Tile tile=(StorageMapData.Tile)item;
                        return !tile.entry.directory&&tile.entry.name.endsWith(".jpg")&&tile.bytes==tile.entry.size;
                    });
        }),"map keeps global Photos search, excludes video, and uses exact indexed metadata");
        check(onMain(()->((TextView)field(activity,"folderOverview")).getText().toString().contains("in indexed files")
                &&((TextView)field(activity,"folderOverview")).getText().toString().contains("Largest match:")),
                "mapped bytes and largest match are identified as indexed search");
        hold(onMain(()->findVisibleMapTile(map,"Earth.jpg")));
        clickText("Properties");
        await(()->activeTextStartsWith(new File(pictures,"Earth.jpg").getPath()),"tile actions select the mapped file");
        closeProperties();
        runOnMainSync(()->((View)field(activity,"mapLayoutToggle")).performClick());
        await(()->onMain(()->activity.findViewById(R.id.results).isShown()&&resultFor("Earth.jpg")!=null),
                "List shows the same filtered matches");
        check(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getCount()==3),"all mapped matches are in List");
        runOnMainSync(()->categories.setSelection(7));
        await(()->onMain(()->{
            List<?> tiles=(List<?>)field(activity,"mapTiles");
            return tiles.size()==2&&tiles.stream().allMatch(item->((StorageMapData.Tile)item).entry.name.endsWith(".mp4"));
        }),"changing to Videos re-maps the same query without opening a folder");
        setQuery("Saturn");waitResults();
        await(()->onMain(()->((List<?>)field(activity,"mapTiles")).isEmpty()),"changing the query updates the video map");
        runOnMainSync(()->((View)field(activity,"mapListButton")).performClick());
        waitResults();
        check(onMain(()->!(Boolean)field(activity,"storageMap")&&categories.getSelectedItemPosition()==7
                &&field(activity,"folderScope")==null&&"Saturn".contentEquals(((EditText)field(activity,"query")).getText())),
                "leaving Map retains the active category, query, and global scope");
        runOnMainSync(()->categories.setSelection(0));
        setQuery("");waitResults();
        openFolder(pictures);
        runOnMainSync(()->categories.setSelection(6));
        setQuery("Earth");waitResults();
        runOnMainSync(()->activity.findViewById(R.id.view_mode).performClick());
        clickText("Map results");
        await(()->onMain(()->map.isShown()&&findVisibleMapTile(map,"Earth.jpg")!=null),
                "scoped Map opens without changing the folder");
        check(onMain(()->((List<?>)field(activity,"mapTiles")).size()==2),
                "same-name match in another folder is not part of scoped Map");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        waitResults();
        check(onMain(()->!(Boolean)field(activity,"storageMap")&&categories.getSelectedItemPosition()==6
                &&pictures.getPath().equals(field(activity,"folderScope"))
                &&"Earth".contentEquals(((EditText)field(activity,"query")).getText())),
                "Android Back returns from Map to the same folder and filter before going up");
    }

    private void verifyQuickPaging(FileHoldActions held)throws Exception{
        runOnMainSync(()->{
            try{
                Field descending=MainActivity.class.getDeclaredField("descending");
                descending.setAccessible(true);descending.setBoolean(activity,false);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        setQuery("Pager");waitResults();
        hold(onMain(()->resultFor("Pager Alpha.txt")));
        QuickPreviewCard first=onMain(()->(QuickPreviewCard)field(held,"quickCard"));
        View fixed=onMain(()->(View)field(first,"more"));
        android.widget.ScrollView scroll=onMain(()->(android.widget.ScrollView)field(first,"previewScroll"));
        await(()->onMain(()->((TextView)field(first,"status")).getText().toString().contains("owned short first")),
                "short pager fixture displayed");
        check(onMain(()->{
            View body=scroll.getChildAt(0);
            View last=((android.widget.LinearLayout)body).getChildAt(((android.widget.LinearLayout)body).getChildCount()-1);
            android.graphics.Rect content=new android.graphics.Rect(),footer=new android.graphics.Rect();
            return last.getGlobalVisibleRect(content)&&fixed.getGlobalVisibleRect(footer)
                    &&footer.top-content.bottom>=0&&footer.top-content.bottom<=Math.round(24*first.getResources().getDisplayMetrics().density);
        }),"short content ends just above the pinned buttons instead of leaving a blank lower area");
        Object sheet=onMain(()->field(held,"quickSheet"));
        int[] toolbar=screenPoint(fixed);
        quickSwipe(scroll,1,false);
        check(onMain(()->first==field(held,"quickCard")),"backward swipe at first file doesn’t wrap");
        quickSwipe(scroll,-1,false);
        await(()->onMain(()->held.targetPath().endsWith("/Pager Gamma.txt")),"left swipe advances in result order and skips folder");
        QuickPreviewCard second=onMain(()->(QuickPreviewCard)field(held,"quickCard"));
        check(onMain(()->field(held,"quickSheet")==sheet&&Boolean.TRUE.equals(field(first,"closed"))),
                "paging keeps one window and retires the previous preview");
        check(java.util.Arrays.equals(toolbar,screenPoint(onMain(()->(View)field(second,"more")))),
                "same bottom control coordinate after paging");
        android.widget.ScrollView secondScroll=onMain(()->(android.widget.ScrollView)field(second,"previewScroll"));
        await(()->onMain(()->secondScroll.canScrollVertically(1)),"next file renders its own scrollable preview");
        quickSwipe(secondScroll,-1,true);
        await(()->onMain(()->secondScroll.getScrollY()>0&&field(held,"quickCard")==second),
                "real vertical swipe scrolls text without changing files");
        quickSwipe(secondScroll,1,false);
        await(()->onMain(()->held.targetPath().endsWith("/Pager Alpha.txt")),"right swipe goes to previous file");
        QuickPreviewCard returned=onMain(()->(QuickPreviewCard)field(held,"quickCard"));
        java.util.ArrayList<String> filename=new java.util.ArrayList<>();
        runOnMainSync(()->{
            returned.clipboardForProbe(clip->filename.add(clip.getItemAt(0).getText().toString()));
            check(visibleNamedView(returned,"Copy filename").performClick()
                    &&filename.equals(java.util.Collections.singletonList("Pager Alpha.txt")),"actions follow the currently shown file");
        });
        runOnMainSync(held::stop);
    }

    private void quickSwipe(View region,int direction,boolean vertical){
        int[] center=screenPoint(region);
        int half=onMain(()->Math.min(110,(vertical?region.getHeight():region.getWidth())/3));
        long began=SystemClock.uptimeMillis();
        for(int i=0;i<=7;i++){
            int shift=direction*(2*half*i/7-half);
            injectHeld(began,i==0?MotionEvent.ACTION_DOWN:i==7?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE,
                    new int[]{center[0]+(vertical?0:shift),center[1]+(vertical?shift:0)});
            SystemClock.sleep(24);
        }
    }

    private void verifyQuickPopup(File documents)throws Exception {
        String file="Packing list.txt";
        setQuery("Packing list");waitResults();
        FileHoldActions held=onMain(()->(FileHoldActions)field(activity,"itemActions"));
        check(!target.getSharedPreferences("ui",0).getBoolean(QuickPreviewCard.PREFERENCE,false),
                "quick variant off by default");
        hold(onMain(()->resultFor(file)));
        await(()->onMain(()->held.isOpen()&&field(held,"quickCard")==null
                &&visibleNamedView((View)field(held,"card"),"Properties")!=null),"default folder and file actions unchanged");
        runOnMainSync(held::stop);

        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());
        clickText("Appearance");clickText(QuickPreviewCard.SETTING);
        check(target.getSharedPreferences("ui",0).getBoolean(QuickPreviewCard.PREFERENCE,false),
                "Appearance setting actually enables the alternate popup");
        if(onMain(()->field(activity,"navigationMenu")!=null))
            runOnMainSync(()->((MenuDialog)field(activity,"navigationMenu")).dismiss());

        View row=onMain(()->resultFor(file));
        int[] point=screenPoint(row);long since=SystemClock.uptimeMillis();
        injectHeld(since,MotionEvent.ACTION_DOWN,point);
        await(()->onMain(held::isTracking),"opted-in file menu tracks the held file before release");
        check(onMain(()->field(held,"quickCard")==null),"no decode or playback during hold-to-retarget");
        injectHeld(since,MotionEvent.ACTION_UP,point);
        await(()->onMain(()->field(held,"quickCard")!=null),"release shows the optional quick preview popup");
        QuickPreviewCard card=onMain(()->(QuickPreviewCard)field(held,"quickCard"));
        await(()->onMain(()->((TextView)field(card,"status")).getText().toString().contains("Synthetic UI fixture.")),
                "text is shown inline");
        android.widget.ScrollView scroll=onMain(()->(android.widget.ScrollView)field(card,"previewScroll"));
        await(()->onMain(()->scroll.canScrollVertically(1)), "bounded text scrolls inside the fixed chrome");
        check(onMain(()->((TextView)field(card,"status")).getText().toString().contains("Preview shortened")),
                "large previews explain their limit instead of clipping silently");
        View more=onMain(()->(View)field(card,"more"));
        int[] morePosition=screenPoint(more),sheetPosition=screenPoint(card);
        int offset=onMain(()->{
            scroll.scrollTo(0,220);
            return scroll.getScrollY();
        });
        check(offset>0,"text scrolls independently");
        check(java.util.Arrays.equals(morePosition,screenPoint(more)),"scroll does not move the thumb-level toolbar");
        check(onMain(()->{
            android.graphics.Rect viewport=new android.graphics.Rect();
            card.getWindowVisibleDisplayFrame(viewport);
            int[] pos=new int[2];card.getLocationOnScreen(pos);
            return card.getHeight()>=Math.min(viewport.height(),Math.round(240*card.getResources().getDisplayMetrics().density))
                    &&pos[1]+card.getHeight()>=viewport.bottom-Math.round(48*card.getResources().getDisplayMetrics().density);
        }),"quick preview occupies a larger window anchored at the bottom");
        java.util.ArrayList<String> copied=new java.util.ArrayList<>();
        runOnMainSync(()->{
            card.clipboardForProbe(clip->copied.add(clip.getItemAt(0).getText().toString()));
            check(visibleNamedView(card,"Copy filename").performClick(),"filename copy control");
            check(visibleNamedView(card,"Copy containing folder").performClick(),"containing folder copy control");
            check(copied.equals(java.util.Arrays.asList(file,documents.getPath())),"footer copies distinct, exact values");
            check(clickDescription(card,"Show all file actions"),"accessible icon opens current full context actions");
        });
        await(()->onMain(()->held.isOpen()&&field(held,"quickCard")==card
                &&Boolean.TRUE.equals(field(card,"overflow"))
                &&visibleNamedView((View)field(held,"card"),"Select items")!=null),
                "full existing actions and selection stay inside the same anchored sheet");
        check(java.util.Arrays.equals(morePosition,screenPoint(more))
                &&java.util.Arrays.equals(sheetPosition,screenPoint(card)),
                "overflow and its three-dot control stay in exactly the same location");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->held.isOpen()&&field(held,"quickCard")==card
                &&Boolean.FALSE.equals(field(card,"overflow"))),"system Back returns to the same preview");
        check(onMain(()->scroll.getScrollY()==offset),"returning from overflow preserves text scroll");
        runOnMainSync(()->{
            check(clickDescription(card,"Show all file actions"),"three dots reopen actions in place");
            View back=(View)field(card,"back");
            check(back.isShown()&&back.performClick(),"visible Back button restores quick preview");
        });
        check(onMain(()->scroll.getScrollY()==offset&&held.isOpen()
                &&Boolean.FALSE.equals(field(card,"overflow"))),"visible Back preserves exact quick-preview state");
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->!held.isOpen()),"second system Back dismisses preview without navigation");

        File source=new File(documents.getParentFile(),"Photos/Saturn.jpg");
        File destination=new File(documents,source.getName());
        check(source.isFile()&&!destination.exists(),"only the synthetic copy source exists before Quick Paste");
        FileClipboard.save(target,java.util.Collections.singletonList(new FileEntry(
                source.getPath(),false,source.length(),source.lastModified())),false,false);
        runOnMainSync(()->{
            try{
                java.lang.reflect.Method update=MainActivity.class.getDeclaredMethod("updateClipboard");
                update.setAccessible(true);update.invoke(activity);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        hold(onMain(()->resultFor(file)));
        QuickPreviewCard withPaste=onMain(()->(QuickPreviewCard)field(held,"quickCard"));
        runOnMainSync(()->check(clickDescription(withPaste,"Paste to "+documents.getPath()),
                "Quick Paste is labeled with the exact containing folder"));
        await(()->activeTextStartsWith(documents.getPath())&&activeTextStartsWith("Destination"),
                "real confirmation reviews the exact destination before any file operation");
        clickText("Cancel");
        check(source.isFile()&&!destination.exists(),"cancel never starts the pending copy");
        FileClipboard.clear(target);

        setQuery("Earthquake");waitResults();
        hold(onMain(()->resultFor("Earthquake.wav")));
        QuickPreviewCard audio=onMain(()->(QuickPreviewCard)field(held,"quickCard"));
        check(audio!=null&&onMain(()->field(audio,"player")==null),"media never auto-plays on popup open");
        runOnMainSync(()->check(clickDescription(audio,"Play quick preview"),"accessible inline Play control"));
        await(()->onMain(()->Boolean.TRUE.equals(field(audio,"ready"))),"explicit Play prepares the synthetic WAV");
        Object player=onMain(()->field(audio,"player"));
        runOnMainSync(()->check(clickDescription(audio,"Show all file actions"),"media actions open in the same sheet"));
        sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        await(()->onMain(()->held.isOpen()&&field(audio,"player")==player
                &&Boolean.TRUE.equals(field(audio,"ready"))&&Boolean.FALSE.equals(field(audio,"overflow"))),
                "returning from actions retains the prepared media player");
        runOnMainSync(()->check(clickDescription(audio,"Close quick preview"),"visible X closes quick preview"));
        await(()->onMain(()->!held.isOpen()&&field(audio,"player")==null),"closing releases playback immediately");

        setQuery("Opaque artifact");waitResults();
        hold(onMain(()->resultFor("Opaque artifact.qafx")));
        QuickPreviewCard opaque=onMain(()->(QuickPreviewCard)field(held,"quickCard"));
        check(opaque!=null&&onMain(()->((TextView)field(opaque,"status")).getText().toString().contains("No quick preview")),
                "unsupported file has no decoding or implicit navigation");
        check(onMain(()->visibleNamedView(opaque,"Open with")!=null
                &&visibleNamedView(opaque,"Open")!=null),"opaque preview shows explicit Open and Open with actions");
        runOnMainSync(held::stop);
        verifyQuickPaging(held);
        setQuery("Opaque artifact");waitResults();
        final String exact=documents.getAbsolutePath();
        final java.util.ArrayList<String> requests=new java.util.ArrayList<>();
        runOnMainSync(()->{
            View anchor=resultFor("Opaque artifact.qafx");
            FileEntry own=null;
            for(FileEntry entry:index.getState().entries)if(entry.pathEquals(exact+"/Opaque artifact.qafx")){own=entry;break;}
            check(own!=null,"exact destination hook only uses a synthetic source");
            FileHoldActions separate=new FileHoldActions(UiTheme.resolve(activity),(x,y)->null,
                    new FileHoldActions.QuickMode(()->true,()->false,java.util.concurrent.Executors.newSingleThreadExecutor()));
            try{
                separate.begin(new FileHoldActions.Target(anchor,own,false,true,action->{throw new AssertionError(action);})
                        .withQuickPaste(exact,()->requests.add(exact)));
                QuickPreviewCard offered=(QuickPreviewCard)field(separate,"quickCard");
                check(clickDescription(offered,"Paste to "+exact),"pinned Paste labels its exact destination");
                check(requests.equals(java.util.Collections.singletonList(exact))&&!separate.isOpen(),
                        "Paste closes the preview and requests exactly one confirmation; it does not copy directly");
            }finally{
                separate.stop();
                FileHoldActions.QuickMode mode=(FileHoldActions.QuickMode)field(separate,"quickMode");
                mode.worker.shutdownNow();
            }
        });
        check(viewer==null&&onMain(()->!((Boolean)field(activity,"selectionMode"))),
                "inline preview never opens viewer or enters batch selection by itself");
        runOnMainSync(()->activity.findViewById(R.id.overflow).performClick());
        clickText("Appearance");clickText(QuickPreviewCard.SETTING);
        check(!target.getSharedPreferences("ui",0).getBoolean(QuickPreviewCard.PREFERENCE,true),
                "turning off quick preview restores the persisted standard choice");
        if(onMain(()->field(activity,"navigationMenu")!=null))
            runOnMainSync(()->((MenuDialog)field(activity,"navigationMenu")).dismiss());
        hold(onMain(()->resultFor("Opaque artifact.qafx")));
        await(()->onMain(()->held.isOpen()&&field(held,"quickCard")==null
                &&visibleNamedView((View)field(held,"card"),"Properties")!=null),"standard popup returns after turning off quick preview");
        runOnMainSync(held::stop);
    }

    private void verifyItemMenus(File documents,File pictures)throws Exception{
        String name="Opaque artifact.qafx";
        File opaque=new File(documents,name);
        setQuery("Opaque artifact");waitResults();
        await(()->onMain(()->{
            AbsListView list=activity.findViewById(R.id.results);
            return list.getCount()==1&&list.getChildCount()==1;
        }),"scope and actual Search result settle before hold");
        hold(onMain(()->resultFor(name)));
        await(()->onMain(()->((FileHoldActions)field(activity,"itemActions")).isOpen()),"holding a Search row opens its item menu");
        check(viewer==null&&!onMain(()->(Boolean)field(activity,"selectionMode")),
                "long press neither opens a preview nor selects the file");
        FileHoldActions menu=onMain(()->(FileHoldActions)field(activity,"itemActions"));
        runOnMainSync(()->check(clickDescription((View)field(menu,"card"),"Close file actions"),
                "the file action card exposes its own accessible close button"));
        await(()->onMain(()->!menu.isOpen()),"explicit close returns to the same file list");
        hold(onMain(()->resultFor(name)));
        clickText("Properties");
        await(()->activeTextStartsWith(opaque.getPath()),"Properties belongs to the held unsupported file");
        closeProperties();
        setQuery("ext:qafx");waitResults();
        verifyHeldSlide(opaque,new File(documents,"Sandbox.qafx"));
        setQuery("");waitResults();
        openFolder(documents);
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());clickText("Files");waitResults();
        hold(onMain(()->resultFor(name)));
        clickText("Copy");
        await(()->FileClipboard.count(target)==1,"copy works directly from a Files row");
        check(FileClipboard.read(target).entries.getJSONObject(0).getString("path").equals(opaque.getPath()),
                "clipboard includes only the held file");
        FileClipboard.clear(target);
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());clickText("Storage map");waitResults();
        StorageMapView map=onMain(()->(StorageMapView)field(activity,"mapView"));
        await(()->onMain(()->map.isShown()&&map.getChildCount()>0),"map in the same folder displays tiles");
        View tile=onMain(()->findVisibleMapTile(map,name));
        check(tile!=null,"unsupported file has a visible map tile");
        hold(tile);clickText("Properties");
        await(()->activeTextStartsWith(opaque.getPath()),"holding a Map tile opens actions for that tile");
        closeProperties();
        hold(onMain(()->findVisibleMapTile(map,name)));clickText("Select items");
        await(()->selectionLabel().startsWith("1 "),"explicit multi-select still works on the map");
        runOnMainSync(activity::onBackPressed);
        verifyImageActionGrid(documents);
        verifyVisiblePanes(documents,pictures);
    }

    private void verifyHeldSlide(File first,File second)throws Exception{
        View from=onMain(()->resultFor(first.getName())),to=onMain(()->resultFor(second.getName()));
        int[] a=screenPoint(from),b=screenPoint(to);
        FileHoldActions actions=onMain(()->(FileHoldActions)field(activity,"itemActions"));
        long began=SystemClock.uptimeMillis();
        injectHeld(began,android.view.MotionEvent.ACTION_DOWN,a);
        await(()->onMain(()->actions.isTracking()&&first.getPath().equals(actions.targetPath())),
                "holding the first real row shows its passive menu");
        injectHeld(began,android.view.MotionEvent.ACTION_MOVE,b);
        await(()->onMain(()->actions.isTracking()&&second.getPath().equals(actions.targetPath())),
                "dragging while held retargets to the next row before release");
        check(onMain(()->{
            View card=(View)field(actions,"card");
            Object anchored=field(actions,"target");
            View highlighted=(View)field(anchored,"anchor");
            return containsTextMain(card,second.getName())&&containsTextMain(card,second.getPath())
                    &&visibleNamedView(highlighted,second.getName())!=null&&field(actions,"marker")!=null;
        }),"passive menu shows the retargeted full name and path while highlighting the row");
        for(int i=0;i<3;i++){
            injectHeld(began,android.view.MotionEvent.ACTION_MOVE,a);SystemClock.sleep(16);
            injectHeld(began,android.view.MotionEvent.ACTION_MOVE,b);SystemClock.sleep(16);
        }
        check(onMain(actions::isTracking),"rapid changes do not interrupt the held gesture");
        injectHeld(began,android.view.MotionEvent.ACTION_UP,b);
        await(()->onMain(()->actions.isOpen()&&!actions.isTracking()&&second.getPath().equals(actions.targetPath())),
                "release leaves the last item menu open");
        check(onMain(()->field(actions,"marker")!=null),"final row keeps its highlight while the menu is open");
        check(viewer==null&&FileClipboard.count(target)==0&&first.isFile()&&second.isFile(),
                "release opens no preview, changes no clipboard, and does not modify either file");
        capture("file-action-grid.png");
        verifyHeaderCopy(actions,second);
        clickText("Properties");
        await(()->activeTextStartsWith(second.getPath()),"explicit action after release uses the last held file");
        closeProperties();
        check(onMain(()->field(actions,"marker")==null),"closing the menu removes its row highlight");
        long cancelled=SystemClock.uptimeMillis();
        injectHeld(cancelled,android.view.MotionEvent.ACTION_DOWN,a);
        await(()->onMain(actions::isTracking),"following hold starts normally");
        injectHeld(cancelled,android.view.MotionEvent.ACTION_CANCEL,a);
        await(()->onMain(()->!actions.isOpen()&&!actions.isTracking()),"gesture interruption dismisses its passive menu");
        long swipe=SystemClock.uptimeMillis();
        injectHeld(swipe,android.view.MotionEvent.ACTION_DOWN,b);
        injectHeld(swipe,android.view.MotionEvent.ACTION_MOVE,new int[]{b[0],b[1]-180});
        injectHeld(swipe,android.view.MotionEvent.ACTION_UP,new int[]{b[0],b[1]-180});
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout()+100);
        check(onMain(()->!actions.isOpen()),"a quick scroll does not activate long press");
    }

    private void verifyImageActionGrid(File documents)throws Exception{
        File image=new File(documents,"Earthrise.jpg");
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());clickText("Files");waitResults();
        setQuery("file: Earthrise ext:jpg");waitResults();
        runOnMainSync(()->{
            AbsListView results=activity.findViewById(R.id.results);
            android.widget.ListAdapter rows=results.getAdapter();
            for(int i=0;i<rows.getCount();i++){
                Object row=rows.getItem(i);
                if(row instanceof FileEntry&&((FileEntry)row).pathEquals(image.getPath())){
                    results.performItemClick(null,i,i);return;
                }
            }
            throw new AssertionError("Synthetic JPG result not visible");
        });
        await(()->viewer!=null&&onMain(()->viewer.hasWindowFocus()&&image.getPath().equals(field(viewer,"path"))),
                "synthetic JPG opens in image preview");
        runOnMainSync(()->clickDescription(viewer.getWindow().getDecorView(),"More file actions"));
        FileHoldActions menu=onMain(()->(FileHoldActions)field(viewer,"previewActions"));
        await(()->onMain(menu::isOpen),"JPG three-dot overflow opens shared card");
        check(onMain(()->{
            View card=(View)field(menu,"card");
            return containsTextMain(card,image.getName())&&containsTextMain(card,image.getPath())
                    &&containsTextMain(card,"Share file")&&containsTextMain(card,"Open in another app");
        }),"JPG card shows the full name, path and viewer-specific actions");
        if(target.getResources().getConfiguration().fontScale<=1.15f)await(()->onMain(()->{
            View footer=(View)field(field(menu,"card"),"metadata");
            android.graphics.Rect visible=new android.graphics.Rect();
            return footer.getLocalVisibleRect(visible)&&footer.getHeight()>0&&visible.height()>=footer.getHeight();
        }),"JPG card footer fits without clipping at this font scale");
        capture("jpg-action-grid.png");
        verifyHeaderCopy(menu,image);
        clickText("Properties");
        await(()->onMain(()->{
            Object properties=field(viewer,"activeSheet");
            return properties instanceof AppSheet&&((AppSheet)properties).isShowing()&&image.getPath().equals(field(viewer,"path"));
        }),"JPG Properties launches in the same viewer");
        closeProperties();
        await(()->onMain(()->viewer.hasWindowFocus()&&!menu.isOpen()),"closing Properties returns to the same JPG preview");
        runOnMainSync(viewer::finish);
        await(()->onMain(()->activity.hasWindowFocus()),"close preview and return to the file list");
        viewer=null;setQuery("");waitResults();
    }

    private void verifyHeaderCopy(FileHoldActions actions,File expected){
        java.util.concurrent.atomic.AtomicReference<android.content.ClipData> capture=new java.util.concurrent.atomic.AtomicReference<>();
        runOnMainSync(()->{
            try{
                Field clipboard=FileActionCard.class.getDeclaredField("probeClipboard");
                clipboard.setAccessible(true);clipboard.set(field(actions,"card"),(java.util.function.Consumer<android.content.ClipData>)capture::set);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
        });
        clickText(expected.getName());
        check(capture.get()!=null&&expected.getName().contentEquals(capture.get().getItemAt(0).getText()),
                "tapping the header name copies just the exact filename");
        capture.set(null);
        clickText(expected.getPath());
        check(capture.get()!=null&&expected.getPath().contentEquals(capture.get().getItemAt(0).getText()),
                "tapping the header path copies the complete nested path");
        check(onMain(actions::isOpen)&&FileClipboard.count(target)==0,"text copy leaves actions open without queuing transfer");
    }

    private int[] screenPoint(View view){
        return onMain(()->{
            android.graphics.Rect bounds=new android.graphics.Rect();
            if(!view.getLocalVisibleRect(bounds))throw new AssertionError("synthetic row not visible");
            int[] at=new int[2];view.getLocationOnScreen(at);
            return new int[]{at[0]+bounds.centerX(),at[1]+bounds.centerY()};
        });
    }

    private void injectHeld(long began,int action,int[] point){
        android.view.MotionEvent event=android.view.MotionEvent.obtain(began,SystemClock.uptimeMillis(),action,point[0],point[1],0);
        event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try{check(getUiAutomation().injectInputEvent(event,true),"inject gesture event "+action);}finally{event.recycle();}
    }

    private View resultFor(String name){
        AbsListView results=activity.findViewById(R.id.results);
        View row=visibleNamedView(results,name);
        if(row==null)throw new AssertionError("visible result not found: "+name);
        return row;
    }

    private View findVisibleMapTile(StorageMapView map,String name){
        for(int i=0;i<map.getChildCount();i++){
            ViewGroup band=(ViewGroup)map.getChildAt(i);
            for(int j=0;j<band.getChildCount();j++){
                View tile=band.getChildAt(j);CharSequence caption=tile.getContentDescription();
                if(caption!=null&&caption.toString().startsWith(name)&&tile.getGlobalVisibleRect(new android.graphics.Rect()))
                    return tile;
            }
        }
        return null;
    }

    private String workspaceLabel(int index){
        try{
            java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod("workspaceLabel",int.class);
            method.setAccessible(true);
            return (String)method.invoke(activity,index);
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }

    private void closeProperties(){
        await(()->{
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root==null)return false;
            try{
                for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText("Close")){
                    try{
                        if("Close".contentEquals(node.getContentDescription()==null?"":node.getContentDescription())
                                &&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return true;
                    }finally{node.recycle();}
                }
                return false;
            }finally{root.recycle();}
        },"Properties has an explicit Close button");
        SystemClock.sleep(150);
        waitForIdleSync();
    }

    private boolean activeTextStartsWith(String text){
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null)return false;
        try{for(AccessibilityNodeInfo node:root.findAccessibilityNodeInfosByText(text)){
            try{if(node.getText()!=null&&node.getText().toString().startsWith(text))return true;}
            finally{node.recycle();}
        }return false;}finally{root.recycle();}
    }

    private void verifyBulkDelete(File documents,File pictures)throws Exception{
        File first=new File(documents,"AA Bulk delete sample.txt"),second=new File(documents,"AB Bulk delete sample.txt");
        File retained=new File(pictures,"Saturn.jpg");
        check(first.isFile()&&second.isFile()&&retained.isFile(),"only owned synthetic deletion fixtures selected");
        setQuery("Bulk delete sample");
        await(()->onMain(()->{
            AbsListView list=activity.findViewById(R.id.results);
            return list.getCount()==2&&list.getChildCount()==2;
        }),"both synthetic bulk-delete results visible before selection");
        View initial=onMain(()->resultFor(first.getName()));
        hold(initial);clickText("Select items");
        await(()->selectionLabel().startsWith("1 "),"held result enters selection");
        SystemClock.sleep(200);
        int[] next=screenPoint(onMain(()->resultFor(second.getName())));
        long tap=SystemClock.uptimeMillis();
        injectHeld(tap,MotionEvent.ACTION_DOWN,next);
        injectHeld(tap,MotionEvent.ACTION_UP,next);
        await(()->selectionLabel().startsWith("2 "),"both search rows selected");
        hold(onMain(()->resultFor(first.getName())));
        await(()->onMain(()->field(activity,"navigationMenu")!=null),"hold on a marked row opens the batch menu");
        check(!activeTextStartsWith("Properties"),"batch menu hides single-file Properties");
        clickText("Delete selected");await(()->activeTextStartsWith("Delete 2 items?"),"Files selection menu shows count");
        clickText("Cancel");check(first.isFile()&&second.isFile(),"Files cancellation leaves all selected items untouched");
        runOnMainSync(()->clickDescription(activity.getWindow().getDecorView(),"Clear selection"));
        setQuery("");
        Bundle left=new Bundle(),right=new Bundle(),state=new Bundle();
        left.putString("path",documents.getPath());right.putString("path",pictures.getPath());
        state.putBundle("left",left);state.putBundle("right",right);
        runOnMainSync(()->{
            try{Field field=MainActivity.class.getDeclaredField("dualPaneState");field.setAccessible(true);field.set(activity,state);}
            catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            activity.findViewById(R.id.browse_locations).performClick();
        });
        clickText("Two-pane Files");
        await(()->onMain(()->{
            AppSheet panes=(AppSheet)field(activity,"activeSheet");
            return panes!=null&&visibleNamedView(panes.getWindow().getDecorView(),first.getName())!=null
                    &&visibleNamedView(panes.getWindow().getDecorView(),second.getName())!=null;
        }),"both pane checkboxes available");
        AppSheet panes=onMain(()->(AppSheet)field(activity,"activeSheet"));
        runOnMainSync(()->{
            check(clickDescription(panes.getWindow().getDecorView(),"Select "+first.getName()),"first pane checkbox selected");
            check(clickDescription(panes.getWindow().getDecorView(),"Select "+second.getName()),"second pane checkbox selected");
        });
        View paneItem=onMain(()->visibleNamedView(panes.getWindow().getDecorView(),first.getName()));
        holdScreen(paneItem);
        await(()->activeTextStartsWith("Delete selected"),"hold on a marked pane item opens the batch menu");
        clickText("Delete selected");
        await(()->activeTextStartsWith("Delete 2 items?"),"two-pane deletion confirms exact selected count");
        clickText("Cancel");
        check(first.isFile()&&second.isFile()&&retained.isFile(),"pane cancellation leaves selected and nonselected files");
        runOnMainSync(panes::dismiss);
        // Exercise real same-process worker on the two private cache fixtures, not personal storage.
        List<FileEntry> files=Arrays.asList(
                new FileEntry(first.getPath(),false,first.length(),first.lastModified()),
                new FileEntry(second.getPath(),false,second.length(),second.lastModified()));
        FileClipboard.Snapshot captured=FileClipboard.capture(target,files,true,false);
        org.json.JSONObject result=FileOperationWorker.runLocal(target,captured.deleteRequest(),()->false,null);
        check(result.optBoolean("ok")&&result.getJSONArray("completedIndices").length()==2,
                "delete worker acknowledges both captured synthetic inputs");
        check(!first.exists()&&!second.exists()&&retained.isFile(),"delete removes only the selected synthetic files");
    }

    private void verifyVisiblePanes(File documents,File pictures)throws Exception{
        Bundle left=new Bundle(),right=new Bundle(),state=new Bundle();
        left.putString("path",documents.getPath());right.putString("path",pictures.getPath());
        state.putBundle("left",left);state.putBundle("right",right);
        runOnMainSync(()->{
            try{
                Field field=MainActivity.class.getDeclaredField("dualPaneState");
                field.setAccessible(true);field.set(activity,state);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            activity.findViewById(R.id.browse_locations).performClick();
        });
        clickText("Two-pane Files");
        await(()->onMain(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            if(sheet==null||!sheet.isShowing())return false;
            View root=sheet.getWindow().getDecorView();
            View source=visibleNamedView(root,"Guide.pdf"),destination=visibleNamedView(root,"Saturn.jpg");
            if(source==null||destination==null)return false;
            int[] a=new int[2],b=new int[2];source.getLocationOnScreen(a);destination.getLocationOnScreen(b);
            return a[0]<b[0];
        }),"two synthetic folders are visible simultaneously in separate columns");
        capture("recovery-two-pane.png");
        File original=new File(documents,"Guide.pdf"),copied=new File(pictures,"Guide.pdf");
        check(original.isFile()&&!copied.exists(),"file begins only in source pane");
        if(contextOnly){
            AppSheet panes=onMain(()->(AppSheet)field(activity,"activeSheet"));
            View item=onMain(()->visibleNamedView(panes.getWindow().getDecorView(),"Guide.pdf"));
            holdScreen(item);
            FileHoldActions paneActions=onMain(()->panes.fileHoldActions);
            await(()->onMain(()->paneActions!=null&&paneActions.isOpen()),"holding a two-pane file exposes the compact action card");
            runOnMainSync(()->check(clickDescription((View)field(paneActions,"card"),"Close file actions"),
                    "two-pane card can close explicitly without leaving the workspace"));
            waitForIdleSync();
            item=onMain(()->visibleNamedView(panes.getWindow().getDecorView(),"Guide.pdf"));
            holdScreen(item);clickText("Properties");
            await(()->activeTextStartsWith(original.getPath())&&activeTextStartsWith("Copy path")
                    &&activeTextStartsWith("Modified"),"two-pane Properties exposes location, Copy path and Modified");
            capture("recovery-two-pane-properties.png");
            closeProperties();
            item=onMain(()->visibleNamedView(panes.getWindow().getDecorView(),"Guide.pdf"));
            holdScreen(item);clickText("Copy to other pane");clickText("Copy here");
            await(()->copied.isFile()&&!FileOperationService.getState().running,"held pane item copied directly without opening a preview");
            check(viewer==null&&copied.length()==original.length(),"pane context menu preserves the original and copies its bytes");
            check(copied.delete(),"remove copied synthetic file before testing preview flow");
            await(()->onMain(()->visibleNamedView(panes.getWindow().getDecorView(),"Guide.pdf")!=null),"source row is ready after direct transfer");
        }
        runOnMainSync(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            View row=visibleNamedView(sheet.getWindow().getDecorView(),"Guide.pdf");
            ((View)row.getParent()).performClick();
        });
        await(()->viewer!=null&&onMain(()->viewer.hasWindowFocus()),"tapping a source file opens its preview");
        runOnMainSync(()->clickDescription(viewer.getWindow().getDecorView(),"More file actions"));
        clickText("Copy to other pane");clickText("Copy here");
        await(()->copied.isFile()&&!FileOperationService.getState().running,"current preview copies directly to the opposite pane");
        check(original.isFile()&&copied.length()==original.length(),"copy leaves the original bytes in place");
        runOnMainSync(()->clickDescription(viewer.getWindow().getDecorView(),"More file actions"));
        clickText("Move to other pane");clickText("Cancel");
        check(original.isFile()&&copied.isFile(),"cancelling a move leaves both files in place");
        runOnMainSync(viewer::finish);
        await(()->onMain(()->{AppSheet sheet=(AppSheet)field(activity,"activeSheet");return sheet!=null&&sheet.isShowing();}),
                "leaving preview returns to the two panes");
        runOnMainSync(()->((AppSheet)field(activity,"activeSheet")).dismiss());waitForIdleSync();
        check(onMain(()->{
            Bundle saved=(Bundle)field(activity,"dualPaneState");
            return documents.getPath().equals(saved.getBundle("left").getString("path"))
                    &&pictures.getPath().equals(saved.getBundle("right").getString("path"));
        }),"both independent pane paths survive closing the workspace");
    }
    private static View visibleNamedView(View view,String name){
        if(!view.isShown())return null;
        if(view instanceof TextView&&name.contentEquals(((TextView)view).getText()))return view;
        if(view instanceof ViewGroup){
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++){
                View hit=visibleNamedView(group.getChildAt(i),name);if(hit!=null)return hit;
            }
        }
        return null;
    }

    private void verifyFolderTree(File documents, File photos, File deep)throws Exception{
        runOnMainSync(()->{
            // Search can retain a scoped folder; seed the fixture so this test never preloads shared storage.
            try{
                Field scope=MainActivity.class.getDeclaredField("folderScope");
                scope.setAccessible(true);scope.set(activity,fixture.getPath());
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            activity.findViewById(R.id.browse_locations).performClick();
        });
        clickText("Folder tree");clickText("Use current folder");
        await(()->onMain(()->fixture.getPath().equals(field(activity,"folderScope"))&&
                ((Spinner)activity.findViewById(R.id.category)).getSelectedItemPosition()==FileTypes.LABELS.length),
                "sidebar folder tree is reachable from Search and opens Files");
        waitResults();
        openFolder(documents);waitResults();
        runOnMainSync(()->((View)field(activity,"folderPath")).performClick());
        await(()->treeControl("Current folder: Documents",false),"tree highlights current Files location");
        clickTreeControl("Expand Folder One");
        await(()->treeControl("Choose folder: Folder Two",false),"nested child appears under the expanded branch");
        check(onMain(()->{
            FolderPathDialog picker=(FolderPathDialog)field(activity,"pathDialog");
            for(Object row:(List<?>)field(picker,"treeRows")){
                FolderTree.Node node=((FolderTree.Row)row).node;
                if(node!=null&&photos.getPath().equals(node.path))return true;
            }
            return false;
        }),"the other branch remains in the displayed tree adapter");
        capture("folder-tree.png");
        clickTreeControl("Choose folder: Folder Two");
        await(()->onMain(()->deep.getPath().equals(field(activity,"folderScope"))),"one folder tap opens the exact two-level folder");
        waitResults();

        Bundle left=new Bundle(),right=new Bundle(),state=new Bundle();
        left.putString("path",documents.getPath());right.putString("path",photos.getPath());
        state.putBundle("left",left);state.putBundle("right",right);
        runOnMainSync(()->{
            try{
                Field stateField=MainActivity.class.getDeclaredField("dualPaneState");
                stateField.setAccessible(true);stateField.set(activity,state);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            activity.findViewById(R.id.browse_locations).performClick();
        });
        clickText("Two-pane Files");
        runOnMainSync(()->{
            AppSheet pane=(AppSheet)field(activity,"activeSheet");
            check(clickDescription(pane.getWindow().getDecorView(),"Active pane options"),"active pane options open");
        });
        clickText("Storage locations");
        await(()->treeControl("Current folder: Documents",false),"active pane opens its own current location");
        clickTreeControl("Expand Folder One");
        clickTreeControl("Choose folder: Folder Two");
        await(()->onMain(()->{
            Bundle saved=(Bundle)field(activity,"dualPaneState");
            return deep.getPath().equals(saved.getBundle("left").getString("path"));
        }),"tree changes the active pane to the exact nested location");
        check(onMain(()->photos.getPath().equals(((Bundle)field(activity,"dualPaneState"))
                .getBundle("right").getString("path"))),"inactive pane remains unchanged");
        runOnMainSync(()->((AppSheet)field(activity,"activeSheet")).dismiss());waitForIdleSync();

        File sample=new File(documents,"Guide.pdf"),notPasted=new File(photos,sample.getName());
        FileClipboard.save(target,java.util.Collections.singletonList(new FileEntry(sample.getPath(),false,
                sample.length(),sample.lastModified())),false,false);
        String original=FileClipboard.read(target).id;
        overflow("File actions","Paste");
        clickTreeControl("Browse destination");
        await(()->treeControl("Current folder: Folder Two",false),"Paste starts its chooser at the scoped folder");
        clickTreeControl("Choose folder: Photos");
        await(()->{
            AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
            if(root==null)return false;
            try{
                List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(photos.getPath());
                boolean exact=false;
                for(AccessibilityNodeInfo node:nodes){
                    if(node.isEditable()&&node.getText()!=null&&photos.getPath().contentEquals(node.getText()))exact=true;
                    node.recycle();
                }
                return exact;
            }finally{root.recycle();}
        },"chooser fills the exact destination in the Paste confirmation");
        check(sample.isFile()&&!notPasted.exists()&&!FileOperationService.getState().running,
                "choosing a folder does not start a pending transfer");
        check(original.equals(FileClipboard.read(target).id),"destination selection preserves the actual clipboard");
        clickTreeControl("Close");
    }

    private void clickTreeControl(String description){
        await(()->treeControl(description,true),"activate " + description);
        waitForIdleSync();
    }

    private boolean treeControl(String description,boolean click){
        AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();
        if(root==null)return false;
        try{
            List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByText(description);
            boolean matched=false;
            for(AccessibilityNodeInfo node:nodes){
                if(!matched&&node.isVisibleToUser()&&node.getContentDescription()!=null&&description.contentEquals(node.getContentDescription()))
                    matched=!click||clickAccessible(node);
                node.recycle();
            }
            if(click&&!matched)scrollAccessible(root);
            return matched;
        }finally{root.recycle();}
    }

    private void verifyVisibleFreshness(File documents,File deep)throws Exception{
        long stamp=System.currentTimeMillis()+10_000;
        File created=new File(deep,"Freshness-one.txt"),renamed=new File(deep,"Freshness-two.txt");
        File moved=new File(documents,"Freshness-three.txt");
        check(index.getState().watchCount==0,"disable watch events to exercise cross-mount fallback");
        await(()->onMain(activity::hasWindowFocus),"Anything visible and focused");
        int whole=index.wholeReconcileStartsForProbe(),full=index.fullScanStartsForProbe();
        setQuery("Freshness-");
        long begin=SystemClock.elapsedRealtime();
        write(created,"owned freshness fixture");
        check(deep.setLastModified(stamp),"distinct external create stamp");
        await(()->hasResult(created),"create in non-common nested directory appears in Search");
        long create=SystemClock.elapsedRealtime()-begin;
        begin=SystemClock.elapsedRealtime();
        check(created.renameTo(renamed)&&deep.setLastModified(stamp+2000),"rename owned Search fixture");
        await(()->hasResult(renamed)&&!hasResult(created),"rename replaces old Search result");
        long rename=SystemClock.elapsedRealtime()-begin;
        setQuery("");openFolder(documents);
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());
        clickText("Files");waitResults();
        check(onMain(()->"File Manager".equals(((Spinner)activity.findViewById(R.id.category)).getSelectedItem().toString())
                &&documents.getPath().equals(field(activity,"folderScope"))),"Files on source move destination");
        begin=SystemClock.elapsedRealtime();
        check(renamed.renameTo(moved)&&deep.setLastModified(stamp+4000)
                &&documents.setLastModified(stamp+4000),"move owned fixture to displayed Files folder");
        await(()->hasResult(moved),"move appears in Files while the folder remains open");
        long move=SystemClock.elapsedRealtime()-begin;
        runOnMainSync(()->((View)field(activity,"mapListButton")).performClick());waitResults();
        check(onMain(()->Boolean.TRUE.equals(field(activity,"storageMap")))&&hasResult(moved),
                "folder Map initially contains the moved file");
        begin=SystemClock.elapsedRealtime();
        check(moved.delete()&&documents.setLastModified(stamp+6000),"delete owned fixture in foreground Map");
        await(()->!hasResult(moved),"delete disappears from the open Map");
        long delete=SystemClock.elapsedRealtime()-begin;
        check(index.fullScanStartsForProbe()==full&&index.wholeReconcileStartsForProbe()==whole,
                "visible changes never start a full build or reconcile");
        report.append("Visible Search create/rename, Files move, Map delete (ms): ")
                .append(create).append('/').append(rename).append('/').append(move).append('/').append(delete).append('\n');
    }
    private boolean hasResult(File file){
        return onMain(()->{
            List<?> rows=(List<?>)field(field(activity,"adapter"),"items");
            for(Object row:rows)if(row instanceof FileEntry&&file.getPath().equals(((FileEntry)row).path()))return true;
            return false;
        });
    }

    private void verifyStorageOverview(File documents)throws Exception{
        check(!index.isRootEnabled(),"storage overview requires no root privilege");
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());
        clickText("Storage overview");
        await(()->onMain(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            if(sheet==null||!sheet.isShowing()||sheet.content.getChildCount()!=2)return false;
            android.os.StatFs fs=new android.os.StatFs(fixture.getPath());
            LinearLayout tile=(LinearLayout)sheet.content.getChildAt(1);
            TextView details=(TextView)tile.getChildAt(1);
            android.widget.ProgressBar bar=(android.widget.ProgressBar)tile.getChildAt(2);
            int expected=(int)Math.round(1000.0*(fs.getTotalBytes()-fs.getFreeBytes())/fs.getTotalBytes());
            return details.getText().toString().contains(" used") && details.getText().toString().contains(" available")
                    && bar.getVisibility()==View.VISIBLE && Math.abs(bar.getProgress()-expected)<=2;
        }),"owned internal volume shows actual StatFs used/available and proportional bar");
        capture("storage-overview.png");
        clickText("Internal storage");waitResults();
        check(onMain(()->fixture.getPath().equals(field(activity,"folderScope"))
                &&((Spinner)activity.findViewById(R.id.category)).getSelectedItem().toString().equals("File Manager")),
                "opening the volume starts Files at the real mounted path");
        runOnMainSync(()->activity.onBackPressed());
        await(()->onMain(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            return sheet!=null&&sheet.isShowing()&&containsTextMain(sheet.getWindow().getDecorView(),"Storage overview");
        }),"system Back from a volume reopens overview");
        runOnMainSync(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            check(clickDescription(sheet.getWindow().getDecorView(),"Close"),"overview close button is available");
        });waitForIdleSync();
        runOnMainSync(()->((View)field(activity,"folderPath")).performClick());clickText("Storage overview");
        await(()->onMain(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            return sheet!=null&&sheet.isShowing();
        }),"folder tree opens that same overview");
        clickText("Internal storage");waitResults();
        runOnMainSync(()->((View)field(activity,"mapListButton")).performClick());waitResults();
        check(onMain(()->(Boolean)field(activity,"storageMap")&&fixture.getPath().equals(field(activity,"folderScope"))),
                "Map uses the same volume root");
        StorageMapView map=onMain(()->(StorageMapView)field(activity,"mapView"));
        await(()->onMain(()->findVisibleMapTile(map,"Documents")!=null),"fixture folder is visible on volume map");
        runOnMainSync(()->findVisibleMapTile(map,"Documents").performClick());waitResults();
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"Map drills into the fixture folder");
        runOnMainSync(()->activity.onBackPressed());waitResults();
        check(onMain(()->fixture.getPath().equals(field(activity,"folderScope"))),"Back first returns to the mounted volume");
        runOnMainSync(()->((View)field(activity,"folderUp")).performClick());
        await(()->onMain(()->{
            AppSheet sheet=(AppSheet)field(activity,"activeSheet");
            return sheet!=null&&sheet.isShowing()&&containsTextMain(sheet.getWindow().getDecorView(),"Storage overview");
        }),"Map parent returns to storage overview rather than a virtual path");
    }

    private void verifyNestedMap(File documents)throws Exception{
        runOnMainSync(()->{
            try{
                Field scope=MainActivity.class.getDeclaredField("folderScope");
                scope.setAccessible(true);scope.set(activity,fixture.getPath());
            }catch(ReflectiveOperationException error){throw new AssertionError(error);}
        });
        runOnMainSync(()->activity.findViewById(R.id.browse_locations).performClick());
        clickText("Storage map");waitResults();
        StorageMapView map=onMain(()->(StorageMapView)field(activity,"mapView"));
        await(()->onMain(()->findVisibleMapTile(map,"Documents")!=null),"fixture folder appears in map");
        check(onMain(()->{
            StorageMapData.Scale scale=(StorageMapData.Scale)field(map,"scopeScale");
            return scale.band(0)==0&&scale.band(96L<<20)==4&&scale.band(-1)==StorageMapData.UNKNOWN
                    &&containsTextStarting(activity.getWindow().getDecorView(),"Largest file: Lab capture.txt");
        }),"scope identifies the exact largest file and blue/red endpoints; unknowns are neutral");
        runOnMainSync(()->{
            android.widget.TextView[] swatches=(android.widget.TextView[])field(activity,"mapSizeSwatches");
            View toggle=(View)field(activity,"mapLayoutToggle");
            int min=Math.round(48*map.getResources().getDisplayMetrics().density);
            check(swatches.length==5&&swatches[4].getHeight()==toggle.getHeight()&&toggle.getHeight()>=min,
                    "legend and list toggle align without shrinking touch targets");
            check(swatches[4].getContentDescription().toString().contains("largest files"),"size band has its own accessible action label");
            swatches[4].performClick();
            check(swatches[4].isSelected()&&(Integer)field(map,"highlightedSizeBand")==4,
                    "tap largest size band highlights it without rebuilding the map");
            swatches[4].performClick();
            check(!swatches[4].isSelected()&&(Integer)field(map,"highlightedSizeBand")==StorageMapData.UNKNOWN,
                    "tap selected size band to reset");
        });
        int[][] tapPoints=onMain(()->{
            View tile=findVisibleMapTile(map,"Documents"),band=(View)tile.getParent();
            List<?> regions=(List<?>)field(tile,"regions");
            for(Object region:regions){
                FileEntry entry=(FileEntry)field(region,"file");
                if(entry==null||!entry.name.equals("Lab capture.txt"))continue;
                if((Integer)field(region,"color")!=map.fillColor(4))throw new AssertionError("largest child did not render in red");
                android.graphics.RectF b=(android.graphics.RectF)field(region,"bounds");
                int target=Math.round(48*map.getResources().getDisplayMetrics().density);
                if(b.width()<target||b.height()<target||field(region,"name")==null)
                    throw new AssertionError("largest child needs a labeled "+target+"px hit region; was "
                            +b.width()+" x "+b.height()+"px in "+tile.getWidth()+" x "+tile.getHeight()+"px folder tile");
                android.graphics.Rect visible=new android.graphics.Rect();
                if(!tile.getLocalVisibleRect(visible)||!visible.contains((int)b.centerX(),(int)b.centerY()))
                    throw new AssertionError("largest child hit is clipped by the map viewport");
                return new int[][]{
                        {(int)(band.getLeft()+tile.getLeft()+b.centerX()),
                         (int)(band.getTop()+tile.getTop()+b.centerY())},
                        {band.getLeft()+tile.getLeft()+tile.getWidth()/2,
                         band.getTop()+tile.getTop()+tile.getPaddingTop()+4}};
            }
            return null;
        });
        check(tapPoints!=null,"the largest file has its own readable hit region within the folder tile");
        capture("nested-storage-map.png");
        long pressed=SystemClock.uptimeMillis();
        mapTouch(map,pressed,android.view.MotionEvent.ACTION_DOWN,tapPoints[0]);
        SystemClock.sleep(45);
        mapTouch(map,pressed,android.view.MotionEvent.ACTION_UP,tapPoints[0]);
        await(()->viewer!=null&&onMain(()->viewer.hasWindowFocus()),"nested file tap opens viewer");
        check(onMain(()->{
            PreviewSession session=PreviewSession.get(viewer.getIntent().getStringExtra(PreviewSession.EXTRA));
            return session!=null&&session.entries.get(session.position).pathEquals(new File(documents,"Lab capture.txt").getPath());
        }),"nested file preview is the correct file");
        runOnMainSync(()->viewer.onBackPressed());waitResults();
        pressed=SystemClock.uptimeMillis();
        mapTouch(map,pressed,android.view.MotionEvent.ACTION_DOWN,tapPoints[1]);
        mapTouch(map,pressed,android.view.MotionEvent.ACTION_UP,tapPoints[1]);
        waitResults();
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"folder header drills down independently of child tap");
        clickText("List");waitForIdleSync();
        check(onMain(()->{
            List<?> items=(List<?>)field(field(activity,"adapter"),"items");
            return (Boolean)field(activity,"mapList")&&((AbsListView)activity.findViewById(R.id.results)).isShown()
                    &&items.size()==java.util.Objects.requireNonNull(documents.list()).length
                    &&items.stream().anyMatch(row->((FileEntry)row).name.equals("Zero bytes.txt"));
        }),"accessible List button shows every child, including zero-byte files");
        runOnMainSync(()->((View)field(activity,"mapLayoutToggle")).performClick());waitForIdleSync();
        check(onMain(map::isShown),"Map returns from List without leaving the folder");
        runOnMainSync(()->((View)field(activity,"folderUp")).performClick());waitResults();
        check(onMain(()->fixture.getPath().equals(field(activity,"folderScope"))),"Back reaches the previous map folder");
    }

    private static boolean containsTextStarting(View v,String prefix){
        if(v instanceof TextView)
            for(String line:((TextView)v).getText().toString().split("\\n"))if(line.startsWith(prefix))return true;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)
            if(containsTextStarting(((ViewGroup)v).getChildAt(i),prefix))return true;
        return false;
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
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"File Manager shows the current folder path");
        check(onMain(()->{AbsListView list=activity.findViewById(R.id.results);
            for(int i=0;i<list.getCount();i++)if(!documents.getPath().equals(((FileEntry)list.getItemAtPosition(i)).parent))return false;
            return list.getCount()>0;}),"File Manager shows immediate directory entries only");
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Bookmark this folder");
        runOnMainSync(()->((View)field(activity,"folderPath")).performClick());
        clickText("Type path");
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
        await(()->onMain(()->typed.equals(field(activity,"folderScope"))),"inline suggestions, subfolder drilldown and Parent open the chosen folder");
        waitResults();
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Recent folders");clickText(documents.getPath());waitResults();
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"recent directory opens");
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Recent folders");clickText(typed);waitResults();
        runOnMainSync(()->((View)field(activity,"folderHistoryButton")).performClick());clickText("Bookmarked folders");clickText(documents.getPath());waitResults();
        check(onMain(()->documents.getPath().equals(field(activity,"folderScope"))),"bookmark opens its directory");
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
        check(onMain(()->{
            StorageMapData.Scale scale=(StorageMapData.Scale)field(map,"scopeScale");
            return scale.max>scale.min&&scale.band(scale.min)==0&&scale.band(scale.max)==4
                    &&scale.band(-1)==StorageMapData.UNKNOWN&&map.fillColor(0)!=map.fillColor(4);
        }),"legend colors relative to the current folder with neutral unknowns");
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
        await(()->onMain(()->((FileHoldActions)field(activity,"itemActions")).isOpen()),"hold opens item actions on the map");
        mapTouch(map,pressedAt,android.view.MotionEvent.ACTION_UP,points[0]);
        clickText("Select items");
        await(()->selectionLabel().startsWith("1 "),"item menu enters map selection");
        pressedAt=SystemClock.uptimeMillis();
        mapTouch(map,pressedAt,android.view.MotionEvent.ACTION_DOWN,points[0]);
        await(()->onMain(()->(Boolean)field(map,"inspecting")),"holding a tile in selection mode starts inspection");
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
        android.os.ParcelFileDescriptor back=getUiAutomation().executeShellCommand("input keyevent 4");
        try(java.io.InputStream event=new android.os.ParcelFileDescriptor.AutoCloseInputStream(back)){
            while(event.read()!=-1){} // Wait for the actual Android Back key, not an Activity callback.
        }
        await(()->!activeTextStartsWith("Paste files"),"Android Back dismisses Paste without transferring files");
        check(!FileOperationService.getState().running,"dismissing Paste does not start a transfer");
        FileClipboard.clear(target);
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
        check(onMain(()->((TextView)activity.findViewById(R.id.query)).getTypeface().equals(target.getResources().getFont(R.font.inter))),"search uses the bundled Inter family");
        // Actual held input brings up item actions before explicit selection.
        View result=onMain(()->((AbsListView)activity.findViewById(R.id.results)).getChildAt(0));hold(result);clickText("Select items");
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
        hold(onMain(()->((AbsListView)activity.findViewById(R.id.results)).getChildAt(0)));clickText("Select items");
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
    private void holdScreen(View view){
        android.graphics.Rect bounds=onMain(()->{android.graphics.Rect r=new android.graphics.Rect();
            if(!view.getGlobalVisibleRect(r))throw new AssertionError("file row is offscreen");
            int[] window=new int[2],screen=new int[2];view.getLocationInWindow(window);view.getLocationOnScreen(screen);
            r.offset(screen[0]-window[0],screen[1]-window[1]);return r;});
        long time=SystemClock.uptimeMillis();
        for(int action:new int[]{android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP}){
            android.view.MotionEvent event=android.view.MotionEvent.obtain(time,SystemClock.uptimeMillis(),action,bounds.centerX(),bounds.centerY(),0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try{check(getUiAutomation().injectInputEvent(event,true),"inject pane held touch "+action);}finally{event.recycle();}
            if(action==android.view.MotionEvent.ACTION_DOWN)SystemClock.sleep(850);
        }
        waitForIdleSync();
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
            check(onMain(()->((TextView)activity.findViewById(R.id.query)).getTypeface().equals(target.getResources().getFont(R.font.inter))),"Aeonik survives theme recreation");
            await(()->onMain(()->{ThumbnailLoader loader=(ThumbnailLoader)field(activity,"thumbnails");if(loader.activeJobs()!=0||loader.queuedJobs()!=0)return false;
                AbsListView list=activity.findViewById(R.id.results);if(list.getChildCount()!=3)return false;
                for(int n=0;n<list.getChildCount();n++)if(!(((ImageView)list.getChildAt(n).findViewById(R.id.file_icon)).getDrawable() instanceof BitmapDrawable))return false;
                return true;}),"all sample photo thumbnails decoded before capture");
            capture("theme-"+choice.toLowerCase(java.util.Locale.ROOT)+".png");
        }
    }

    private void launch() {
        Intent intent = new Intent(target, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if(quickPopupOnly)intent.putExtra("permissionProbe",true);
        activity = (MainActivity) startActivitySync(intent); waitForIdleSync();
        runOnMainSync(()->{
            activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        });
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
