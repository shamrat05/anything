package dev.fastsearch.android;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.SubMenu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.*;
import dev.fastsearch.core.FileEntry;
import dev.fastsearch.core.MetadataOrder;
import dev.fastsearch.core.SearchQuery;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** The results own the screen; secondary controls live in native menus. */
public final class MainActivity extends Activity implements FileIndex.Listener {
    private static final int FILE_MANAGER=FileTypes.LABELS.length;
    private static volatile FileIndex probeIndex;
    static void setProbeIndex(FileIndex value){if(!BuildConfig.DEBUG)throw new IllegalStateException("Debug probe only");probeIndex=value;}
    private static volatile boolean permissionProbe;
    static void setPermissionProbe(boolean value){if(!BuildConfig.DEBUG)throw new IllegalStateException("Debug probe only");permissionProbe=value;}
    private int INK=0xFF202020, CHROME=0xFFF0F1FA, LINE=0xFFE4E6EF,
            MUTED=0xFF60656B, BLUE=0xFF1A2FFB;
    private UiTheme theme;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor searches=executor("file-search",1);
    private final ThreadPoolExecutor fileActions=executor("file-action",2);
    private final ThreadPoolExecutor mapOrders=executor("map-order",1);
    private final AtomicInteger sequence=new AtomicInteger();
    private final Set<String> selection=new HashSet<>();
    private final FileOperationService.Listener operationListener=this::onOperationChanged;
    private Future<?> pending;
    private CancellationSignal deviceCancellation;
    private FileIndex index;
    private FileIndex.State state;
    private SharedPreferences prefs;
    private DeviceSearch deviceSearch;
    private ThumbnailLoader thumbnails;
    private AutoCompleteTextView query;
    private Spinner category;
    private ImageButton refresh,viewMode,sortButton,optionsButton,pasteButton,clearSearch,subtypeButton,locationsButton;
    private TextView status,message,emptyText,selectionCount,sectionLabel,folderPath,mapSelection;
    private LinearLayout folderBar;
    private LinearLayout mapLegend;
    private ScrollView mapDetails;
    private ImageButton folderUp,folderHistoryButton,mapListButton;
    private StorageMapView mapView;
    private Button emptyAction;
    private ProgressBar progress;
    private LinearLayout controls,selectionBar;
    private FrameLayout body;
    private AbsListView results;
    private LinearLayout empty;
    private LoadingSkeleton skeleton;
    private boolean longPressConsumed;
    private boolean pullRefreshArmed;
    private float pullStartX, pullStartY;
    private ResultsAdapter adapter;
    private List<FileEntry> lastEntries;
    private FileEntry selected,mapPreview;
    private String folderScope,rootHome;
    private final ArrayList<Bundle> folderHistory=new ArrayList<>();
    private Bundle browseRestore;
    private boolean openingPreview;
    private String subtype;
    private int subtypeCategory;
    private List<FileSubtypes.Choice> availableSubtypes=Collections.emptyList();
    private PopupWindow subtypePopup;
    private MenuDialog navigationMenu;
    private FolderPathDialog pathDialog;
    private int sort,layoutMode,activeLayout=-1,multiTypes;
    private AppSheet activeSheet;
    private int gridWidth=112;
    private android.view.ScaleGestureDetector resultPinch;
    private boolean pinchArmed,pinchingResults;
    private float pinchScale=1,screenOffsetX,screenOffsetY;
    private boolean mixedDeviceSearch;
    private boolean descending,initialRequested,waitingPermission,searchRunning,showHidden,reportedFullyDrawn;
    private boolean matchCase,wholeWord,matchPath,regex,selectionMode,allSelected;
    private String provisionalSearchKey;
    private Runnable saveSearchRows;
    private long totalFileMatches,lastSearchMillis;
    // Accessed only by the search worker. Keep one full Root view for repeat location switches.
    private RootSearchResult rootSearchResult;
    private final ArrayList<DeviceSearch.Page> devicePages=new ArrayList<>();
    private final ArrayList<String> deviceStarts=new ArrayList<>();
    private final ArrayList<Long> deviceOffsets=new ArrayList<>();
    private int firstDevicePage;
    private String deviceNext;
    private boolean restoringDeviceScroll;
    private boolean refreshResults;
    private boolean storageMap,mapList;
    private String mapRowsScope;
    private List<StorageMapData.Tile> mapTiles=Collections.emptyList();
    private int mapSort;
    private boolean mapDescending;
    private Future<?> pendingMapOrder;
    private final Map<String,Long> mapSizes=new HashMap<>();
    private long mapTotalBytes;
    private final Runnable folderRefresh=this::refreshOpenFolder;

    private static ThreadPoolExecutor executor(String name,int waiting) {
        return new ThreadPoolExecutor(1,1,15,TimeUnit.SECONDS,new ArrayBlockingQueue<>(waiting),
                task->new Thread(task,name),new ThreadPoolExecutor.AbortPolicy());
    }
    private static final class RootSearchResult {
        final java.lang.ref.WeakReference<List<FileEntry>> snapshot;
        final int sort;
        final boolean descending, showHidden;
        final List<FileEntry> rows;
        final FileSections sections;

        RootSearchResult(List<FileEntry> snapshot, int sort, boolean descending, boolean showHidden,
                         List<FileEntry> rows, FileSections sections) {
            this.snapshot = new java.lang.ref.WeakReference<>(snapshot);
            this.sort = sort;
            this.descending = descending;
            this.showHidden = showHidden;
            this.rows = rows;
            this.sections = sections;
        }
    }
    @Override public void onCreate(Bundle saved) {
        theme=UiTheme.apply(this);INK=theme.ink;CHROME=theme.chrome;LINE=theme.line;MUTED=theme.muted;BLUE=theme.accent;
        super.onCreate(saved);
        theme.bars(this);
        if(BuildConfig.DEBUG&&getPackageName().endsWith(".fresh")&&getIntent().getBooleanExtra("permissionProbe",false))permissionProbe=true;
        prefs=getSharedPreferences("ui",MODE_PRIVATE);
        boolean initialDescending=!prefs.getBoolean("descendingDefault4",false);
        if(initialDescending)prefs.edit().putBoolean("descending",true).putBoolean("descendingDefault4",true).apply();
        index=probeIndex==null?FileIndex.get(this):probeIndex;state=index.getState();
        deviceSearch=new DeviceSearch(this);thumbnails=new ThumbnailLoader(this);
        sort=saved==null?prefs.getInt("sort",3):saved.getInt("sort",3);
        descending=initialDescending||(saved==null?prefs.getBoolean("descending",true):saved.getBoolean("descending",true));
        gridWidth=Math.max(72,Math.min(240,prefs.getInt("gridWidth",112)));layoutMode=prefs.getInt("view",0);multiTypes=saved==null?prefs.getInt("types",0):saved.getInt("types",0);
        showHidden=prefs.getBoolean("showHidden",true);
        storageMap=prefs.getBoolean("storageMap",false);
        mapSort=prefs.getInt("mapSort",2);mapDescending=prefs.getBoolean("mapDescending",true);
        mapList=saved!=null&&saved.getBoolean("mapList");
        matchCase=prefs.getBoolean("case",false);wholeWord=prefs.getBoolean("word",false);
        matchPath=prefs.getBoolean("path",false);regex=prefs.getBoolean("regex",false);
        initialRequested=saved!=null&&saved.getBoolean("initialRequested",false);
        Bundle start=saved==null?startingLocation():null;
        folderScope=saved==null?start.getString("scope"):saved.getString("scope");
        rootHome=saved==null?start.getString("rootHome"):saved.getString("rootHome");
        subtype=saved==null?null:saved.getString("subtype");
        subtypeCategory=Math.min(FILE_MANAGER,saved==null?prefs.getInt("filter",0):saved.getInt("filter",0));
        if(saved==null&&prefs.getInt("startLocation",2)<2&&isDeviceCategory(subtypeCategory))subtypeCategory=0;
        if(storageMap&&isDeviceCategory(subtypeCategory))subtypeCategory=0;
        if(subtypeCategory==FILE_MANAGER&&folderScope==null)folderScope=internalStoragePath();
        if(saved!=null){ArrayList<Bundle> history=saved.getParcelableArrayList("folderHistory");if(history!=null)folderHistory.addAll(history);
            browseRestore=saved.getBundle("browseLocation");}else browseRestore=start;
        buildUi();initResultPinch();
        category.setSelection(subtypeCategory);
        replaceResults();
        optionsButton.setVisibility(isDeviceCategory(subtypeCategory)?View.GONE:View.VISIBLE);sortButton.setVisibility(isDeviceCategory(subtypeCategory)?View.GONE:View.VISIBLE);
        query.setText(saved==null?start.getString("query",""):saved.getString("query",""));
        if(saved!=null){selectionMode=saved.getBoolean("selecting",false);allSelected=saved.getBoolean("allSelected",false);
            ArrayList<String> marked=saved.getStringArrayList("selectedPaths");if(marked!=null)selection.addAll(marked);lastEntries=state.entries;updateSelection();}
        if(Build.VERSION.SDK_INT>=33)getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,this::handleBack);
        index.load();
        ui.post(this::offerFileAccess);
    }
    @Override protected void onStart(){
        super.onStart();thumbnails.resume();index.addListener(this);FileOperationService.addListener(operationListener);
        onChanged(index.getState());updateClipboard();checkUpdates();
        if(isFolderView()&&!searchRunning)updateFiles();
        ui.postDelayed(folderRefresh,5000);
    }
    @Override protected void onStop(){
        ui.removeCallbacks(folderRefresh);
        thumbnails.pause();
        if(activeSheet!=null){activeSheet.dismiss();activeSheet=null;}
        if(navigationMenu!=null)navigationMenu.dismiss();
        if(pathDialog!=null)pathDialog.dismiss();
        saveHistory();saveBrowseLocation();prefs.edit().putInt("sort",sort).putBoolean("descending",descending)
                .putInt("filter",category.getSelectedItemPosition()).apply();
        index.removeListener(this);FileOperationService.removeListener(operationListener);super.onStop();
    }
    @Override protected void onDestroy(){
        if(navigationMenu!=null)navigationMenu.dismiss();
        if(subtypePopup!=null)subtypePopup.dismiss();
        sequence.incrementAndGet();if(pending!=null)pending.cancel(true);
        if(deviceCancellation!=null)deviceCancellation.cancel();
        searches.shutdownNow();fileActions.shutdownNow();mapOrders.shutdownNow();thumbnails.close();
        ui.removeCallbacksAndMessages(null);index.removeListener(this);FileOperationService.removeListener(operationListener);
        results.setOnScrollListener(null);results.setAdapter(null);adapter.items=Collections.emptyList();
        adapter.devices=Collections.emptyList();lastEntries=null;selection.clear();super.onDestroy();
    }
    @Override public void onTrimMemory(int level){super.onTrimMemory(level);if(thumbnails!=null&&(level==TRIM_MEMORY_RUNNING_LOW||level==TRIM_MEMORY_RUNNING_CRITICAL||level>=TRIM_MEMORY_BACKGROUND))thumbnails.trim();}
    @Override protected void onSaveInstanceState(Bundle b){
        b.putInt("types",multiTypes);b.putString("query",query.getText().toString());b.putString("scope",folderScope);b.putString("rootHome",rootHome);b.putInt("sort",sort);
        b.putBoolean("descending",descending);b.putInt("filter",category.getSelectedItemPosition());
        b.putString("subtype",subtype);
        b.putBoolean("mapList",mapList);
        b.putParcelableArrayList("folderHistory",folderHistory);b.putBundle("browseLocation",browseLocation());
        b.putBoolean("initialRequested",initialRequested);super.onSaveInstanceState(b);
        b.putBoolean("selecting",selectionMode);b.putBoolean("allSelected",allSelected);int bytes=0;for(String path:selection)bytes+=path.length()*2;
        if(bytes<128*1024)b.putStringArrayList("selectedPaths",new ArrayList<>(selection));
    }
    @Override protected void onResume(){super.onResume();if(waitingPermission){waitingPermission=false;
        if(isDeviceCategory(category.getSelectedItemPosition()))runSearch();else if(isFolderView())updateFiles();else if(FileIndex.hasAccess(this))startIndexing();}}
    // Android 13+ uses the registered platform callback; this handles Android 8-12.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed(){handleBack();}
    private void handleBack(){if(selectionMode)clearSelection();else if(canGoUp()&&!isDeviceCategory(category.getSelectedItemPosition())){backFolder();}
        else if(query.length()>0)query.setText("");else finish();}
    private Bundle browseLocation(){if(browseRestore!=null)return new Bundle(browseRestore);
        Bundle location=new Bundle();location.putString("scope",folderScope);location.putString("query",query.getText().toString());
        location.putString("rootHome",rootHome);
        if(searchRunning&&!refreshResults)return location;
        int first=results.getFirstVisiblePosition();location.putInt("position",first);
        location.putInt("top",results.getChildCount()==0?0:results.getChildAt(0).getTop()-results.getPaddingTop());
        FileEntry anchor=adapter.fileAt(first);if(anchor!=null)location.putString("anchor",anchor.path());return location;}
    private Bundle startingLocation(){
        Bundle location=new Bundle();int choice=prefs.getInt("startLocation",2);
        location.putString("query",prefs.getString("query",""));
        try{JSONObject saved=new JSONObject(prefs.getString("browseLocation","{}"));
            if(saved.optBoolean("root",index.isRootEnabled())!=index.isRootEnabled())return choice<2?fixedStart(choice):location;
            Bundle current=readLocation(saved.getJSONObject("current"));JSONArray history=saved.optJSONArray("history");
            if(choice<2&&current.getString("rootHome")==null)return fixedStart(choice);
            ArrayList<Bundle> restored=new ArrayList<>();if(history!=null)for(int i=Math.max(0,history.length()-64);i<history.length();i++)restored.add(readLocation(history.getJSONObject(i)));
            folderHistory.addAll(restored);return current;
        }catch(org.json.JSONException ignored){return choice<2?fixedStart(choice):location;}
    }
    private Bundle fixedStart(int choice){Bundle location=new Bundle();
        location.putString("scope",choice==1&&index.isRootEnabled()?"/":internalStoragePath());return location;}
    private static Bundle readLocation(JSONObject saved)throws org.json.JSONException{
        Bundle location=new Bundle();String scope=saved.has("scope")?saved.getString("scope"):null;
        if(scope!=null&&(!scope.startsWith("/")||scope.indexOf('\0')>=0))throw new org.json.JSONException("Invalid folder");
        location.putString("scope",scope);location.putString("query",saved.optString("query",""));
        if(saved.has("rootHome"))location.putString("rootHome",saved.getString("rootHome"));
        location.putInt("position",Math.max(0,saved.optInt("position")));location.putInt("top",saved.optInt("top"));
        if(saved.has("anchor"))location.putString("anchor",saved.getString("anchor"));return location;
    }
    private static JSONObject writeLocation(Bundle location)throws org.json.JSONException{
        return new JSONObject().put("scope",location.getString("scope")).put("query",location.getString("query",""))
            .put("position",location.getInt("position")).put("top",location.getInt("top")).put("anchor",location.getString("anchor"))
            .put("rootHome",location.getString("rootHome"));
    }
    private void saveBrowseLocation(){try{JSONArray history=new JSONArray();for(Bundle location:folderHistory)history.put(writeLocation(location));
        JSONObject saved=new JSONObject().put("root",index.isRootEnabled()).put("current",writeLocation(browseLocation())).put("history",history);
        prefs.edit().putString("browseLocation",saved.toString()).apply();
        }catch(org.json.JSONException ignored){/* Keep the previously saved location if serialization fails. */}}
    private String internalStoragePath(){
        String shared=Environment.getExternalStorageDirectory().getAbsolutePath();
        // Root traversal excludes storage aliases and indexes their /data/media backing directory.
        if(index.isRootEnabled()&&shared.startsWith("/storage/emulated/")){
            String user=new File(shared).getName();if(!user.isEmpty()&&TextUtils.isDigitsOnly(user))return "/data/media/"+user;
        }
        return shared;
    }
    private void navigateFolder(String path){navigateFolder(path,true);}
    private void navigateFolder(String path,boolean rememberOrigin){
        if(path.equals(folderScope)&&!isDeviceCategory(category.getSelectedItemPosition()))return;
        if(rememberOrigin){if(folderHistory.size()>=64)folderHistory.remove(0);folderHistory.add(browseLocation());}
        browseRestore=null;
        if(isDeviceCategory(category.getSelectedItemPosition()))category.setSelection(0);
        clearResultRows();adapter.notifyDataSetChanged();
        folderScope=path;clearSelection();if(query.length()>0)query.setText("");else runSearch();
        saveBrowseLocation();
    }
    private Map<String,String> removableLocations(){
        Map<String,String> volumes=new LinkedHashMap<>();
        if(Build.VERSION.SDK_INT>=30){
            android.os.storage.StorageManager storage=getSystemService(android.os.storage.StorageManager.class);
            if(storage!=null)for(android.os.storage.StorageVolume volume:storage.getStorageVolumes()){
                File directory=volume.getDirectory();
                if(directory==null||volume.isPrimary())continue;
                String label="SD card"+(volumes.isEmpty()?"":" "+(volumes.size()+1));
                volumes.put(label,directory.getAbsolutePath());
            }
        }else for(File appDir:getExternalFilesDirs(null)){
            if(appDir==null)continue;
            String path=appDir.getAbsolutePath();int marker=path.indexOf("/Android/data/");
            if(marker>0&&Environment.isExternalStorageRemovable(appDir)){
                String label="SD card"+(volumes.isEmpty()?"":" "+(volumes.size()+1));
                volumes.put(label,path.substring(0,marker));
            }
        }
        return volumes;
    }
    private String mapScope(){if(folderScope!=null)return folderScope;
        Iterator<String> volumes=removableLocations().values().iterator();return volumes.hasNext()?volumes.next():internalStoragePath();}
    private void editFolderPath(){
        if(pathDialog!=null||folderScope==null)return;
        pathDialog=new FolderPathDialog(this,index,folderScope,this::normalizeFolderPath,
                this::jumpToFolder,()->pathDialog=null);
        pathDialog.show();
    }
    private String normalizeFolderPath(String path){
        path=new File(path).toPath().normalize().toString();
        String shared=Environment.getExternalStorageDirectory().getAbsolutePath();
        if(isInside(path,"/sdcard"))path=internalStoragePath()+path.substring("/sdcard".length());
        else if(isInside(path,shared))path=internalStoragePath()+path.substring(shared.length());
        return path;
    }
    private void jumpToFolder(String path){
        path=normalizeFolderPath(path);
        boolean storage=isInside(path,internalStoragePath());
        for(String volume:removableLocations().values())storage|=isInside(path,volume);
        if(index.isRootEnabled()&&rootHome==null&&!storage)chooseLocation(path,true);
        else navigateFolder(path,true);
    }
    private static boolean isInside(String path,String directory){return path.equals(directory)||path.startsWith(directory+"/");}
    private void showFolderHistory(View anchor){
        String path=folderScope;
        List<String> bookmarks=stored("folderBookmarks");
        boolean bookmarked=bookmarks.contains(path);
        String toggle=bookmarked?"Remove bookmark":"Bookmark this folder";
        Menu menu=theme.popup(this,anchor).getMenu();
        menu.add(toggle).setEnabled(path!=null);
        menu.add("Recent folders");
        menu.add("Bookmarked folders");
        showMenu(anchor,menu,"Folder history",choice->{
            if(choice.equals(toggle)){
                if(bookmarked)bookmarks.remove(path);else bookmarks.add(0,path);
                prefs.edit().putString("folderBookmarks",new JSONArray(bookmarks).toString()).apply();
                toast(bookmarked?"Bookmark removed":"Folder bookmarked");
            }else{
                boolean recent=choice.equals("Recent folders");
                List<String> folders=stored(recent?"folderRecents":"folderBookmarks");
                AlertDialog.Builder picker=new AlertDialog.Builder(this).setTitle(choice).setNegativeButton("Close",null);
                if(folders.isEmpty())picker.setMessage(recent?"No recent folders yet":"No bookmarked folders yet");
                else picker.setItems(folders.toArray(new String[0]),(dialog,which)->jumpToFolder(folders.get(which)));
                picker.show();
            }
        });
    }
    private void showLocations(View anchor){PopupMenu popup=theme.popup(this,anchor);Menu menu=popup.getMenu();
        Map<String,String> volumes=removableLocations();
        menu.add("Internal storage").setCheckable(true).setChecked(internalStoragePath().equals(folderScope));
        for(Map.Entry<String,String> volume:volumes.entrySet())menu.add(volume.getKey()).setCheckable(true).setChecked(volume.getValue().equals(folderScope));
        menu.add("Root").setCheckable(true).setChecked(rootHome!=null||"/".equals(folderScope)).setEnabled(index.isRootEnabled());
        showMenu(anchor,menu,"Browse locations",label->{haptic(anchor);
            if(label.equals("Root")){if(rootHome==null)chooseLocation("/",true);else if(isDeviceCategory(category.getSelectedItemPosition()))category.setSelection(0);}
            else chooseLocation(volumes.getOrDefault(label,internalStoragePath()),false);
        });
    }
    private void chooseLocation(String path,boolean root){
        boolean leavingRoot=!root&&(rootHome!=null||"/".equals(folderScope));
        rootHome=root?"/":null;
        if(root||leavingRoot){folderHistory.clear();folderScope=null;}
        navigateFolder(path,!root&&!leavingRoot);
    }
    private void chooseStartLocation(){String[] labels={"Internal storage","Root","Last location"};
        ArrayAdapter<String> choices=new ArrayAdapter<String>(this,android.R.layout.select_dialog_singlechoice,labels){
            @Override public boolean areAllItemsEnabled(){return false;}
            @Override public boolean isEnabled(int position){return position!=1||index.isRootEnabled();}
            @Override public View getView(int position,View recycled,ViewGroup parent){View row=super.getView(position,recycled,parent);
                row.setEnabled(isEnabled(position));row.setAlpha(isEnabled(position)?1f:.38f);return row;}
        };
        new AlertDialog.Builder(this).setTitle("Start location").setSingleChoiceItems(choices,prefs.getInt("startLocation",2),(dialog,which)->{
            prefs.edit().putInt("startLocation",which).apply();dialog.dismiss();}).setNegativeButton("Cancel",null).show();
    }
    private boolean canGoUp(){return folderScope!=null&&!folderScope.equals(rootHome)
            &&(!folderHistory.isEmpty()||parentFolder()!=null);}
    private String parentFolder(){
        if(folderScope==null||folderScope.equals(rootHome)||folderScope.equals(internalStoragePath())
                ||removableLocations().containsValue(folderScope))return null;
        File parent=new File(folderScope).getParentFile();
        return parent==null?null:parent.getPath();
    }
    private void backFolder(){if(!canGoUp())return;clearSelection();
        if(folderHistory.isEmpty()){
            browseRestore=new Bundle();
            if(isFolderView()||rootHome!=null)browseRestore.putString("scope",parentFolder());
        }else browseRestore=folderHistory.remove(folderHistory.size()-1);
        folderScope=browseRestore.getString("scope");
        if(folderScope==null&&(isFileManager()||rootHome!=null))folderScope=rootHome!=null?rootHome:internalStoragePath();
        clearResultRows();adapter.notifyDataSetChanged();
        String input=browseRestore.getString("query","");
        if(!query.getText().toString().equals(input))query.setText(input);else runSearch();saveBrowseLocation();}
    private void restoreBrowsePosition(Bundle location){if(location==null)return;
        int position=location.getInt("position",0);String anchor=location.getString("anchor");
        if(anchor!=null)for(int i=0;i<adapter.items.size();i++)if(adapter.items.get(i).pathEquals(anchor)){position=adapter.devices.size()+i;break;}
        position=Math.max(0,Math.min(position,adapter.getCount()-1));
        restoreResultOffset(position,location.getInt("top",0));updateSection(position);}
    private void restoreResultOffset(int position,int offset){
        if(results instanceof ListView){((ListView)results).setSelectionFromTop(position,offset);return;}
        AbsListView grid=results;
        grid.setSelection(position);
        int ticket=sequence.get();
        grid.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener(){
            public boolean onPreDraw(){
                grid.getViewTreeObserver().removeOnPreDrawListener(this);
                if(grid!=results||sequence.get()!=ticket)return true;
                View anchor=grid.getChildAt(position-grid.getFirstVisiblePosition());
                if(anchor!=null)grid.scrollListBy(anchor.getTop()-grid.getPaddingTop()-offset);
                return true;
            }
        });
    }
    private int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private TextView text(String value,float size){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);
        t.setTextColor(INK);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    private LinearLayout row(){LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);return r;}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private ImageButton icon(int resource,String description){ImageButton b=new ImageButton(this);b.setImageResource(resource);
        b.setImageTintList(ColorStateList.valueOf(INK));b.setContentDescription(description);b.setTooltipText(description);
        b.setBackgroundResource(android.R.drawable.list_selector_background);b.setPadding(dp(12),dp(12),dp(12),dp(12));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48),dp(48)));return b;}
    private Button button(String value){Button b=new Button(this);b.setText(value);b.setAllCaps(false);b.setMinHeight(dp(48));return b;}
    private void haptic(View v){if(prefs.getBoolean("haptics",true))v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(theme.surface);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets();});
        setContentView(root);
        LinearLayout search=row();search.setPadding(dp(8),dp(2),dp(4),dp(2));search.setBackgroundColor(CHROME);
        query=new AutoCompleteTextView(this);query.setId(R.id.query);query.setSaveEnabled(false);query.setTextSize(16);query.setSingleLine(true);
        query.setHint("Search");query.setContentDescription(BuildConfig.SMS_ENABLED?"Search files, apps, contacts and SMS":"Search files, apps and contacts");query.setThreshold(1);
        query.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        query.setPadding(dp(48),0,dp(6),0);query.setBackground(background(theme.surface,24));query.setTextColor(INK);query.setHintTextColor(MUTED);
        FrameLayout searchField=new FrameLayout(this);searchField.addView(query,new FrameLayout.LayoutParams(-1,-1));
        locationsButton=icon(R.drawable.ic_locations,"Browse locations");locationsButton.setId(R.id.browse_locations);locationsButton.setOnClickListener(this::showLocations);
        searchField.addView(locationsButton,new FrameLayout.LayoutParams(dp(48),-1,Gravity.START));
        clearSearch=icon(R.drawable.ic_close,"Clear search");clearSearch.setId(R.id.clear_search);
        clearSearch.setVisibility(View.GONE);clearSearch.setOnClickListener(v->clearSearchOrFolder());
        clearSearch.setOnLongClickListener(v->{showClearOptions();return true;});
        searchField.addView(clearSearch,new FrameLayout.LayoutParams(dp(48),-1,Gravity.END));
        search.addView(searchField,new LinearLayout.LayoutParams(0,dp(48),1));
        refresh=icon(R.drawable.ic_refresh,"Refresh index");refresh.setId(R.id.refresh);
        refresh.setOnClickListener(v->{haptic(v);if(isFolderView())updateFiles();
            else if(isDeviceCategory(category.getSelectedItemPosition())){deviceSearch.invalidateApps();runSearch();}
            else if(state.scanning)index.cancelScan();else startIndexing();});search.addView(refresh);
        ImageButton overflow=icon(R.drawable.ic_more,"More actions");overflow.setId(R.id.overflow);
        overflow.setOnClickListener(this::showOverflow);search.addView(overflow);root.addView(search);
        controls=row();controls.setPadding(dp(4),0,dp(4),0);
        category=new Spinner(this);category.setId(R.id.category);category.setContentDescription("Search category");
        category.setAdapter(new CategoryAdapter());category.setTooltipText("Hold to choose file types");
        category.setOnLongClickListener(v->{chooseTypes();return true;});controls.addView(category,new LinearLayout.LayoutParams(0,dp(48),1));
        subtypeButton=icon(R.drawable.ic_subtype,"File extensions");subtypeButton.setId(R.id.file_subtypes);subtypeButton.setVisibility(View.GONE);
        subtypeButton.setOnClickListener(v->showSubtypes(false));controls.addView(subtypeButton);
        viewMode=icon(R.drawable.ic_list,"Change view");viewMode.setId(R.id.view_mode);viewMode.setOnClickListener(v->chooseView());controls.addView(viewMode);
        sortButton=icon(R.drawable.ic_sort,"Sort results");sortButton.setId(R.id.sort);sortButton.setOnClickListener(this::showSort);controls.addView(sortButton);
        optionsButton=icon(R.drawable.ic_options,"Search options");optionsButton.setId(R.id.search_options);optionsButton.setOnClickListener(v->searchOptions());controls.addView(optionsButton);
        pasteButton=icon(R.drawable.ic_paste,"Paste files");pasteButton.setId(R.id.paste);pasteButton.setOnClickListener(v->paste());controls.addView(pasteButton);root.addView(controls);
        selectionBar=row();selectionBar.setId(R.id.selection_bar);selectionBar.setBackgroundColor(CHROME);selectionBar.setVisibility(View.GONE);
        ImageButton close=icon(R.drawable.ic_close,"Clear selection");close.setOnClickListener(v->clearSelection());selectionBar.addView(close);
        selectionCount=text("",14);selectionCount.setId(R.id.selection_count);selectionCount.setSingleLine();selectionBar.addView(selectionCount,new LinearLayout.LayoutParams(0,dp(48),1));
        ImageButton select=icon(R.drawable.ic_select,"Selection options");select.setOnClickListener(this::selectionMenu);selectionBar.addView(select);
        if(getResources().getConfiguration().screenWidthDp>=360){
            ImageButton share=icon(R.drawable.ic_share,"Share selected files");share.setOnClickListener(v->shareSelection());selectionBar.addView(share);
        }
        ImageButton copy=icon(R.drawable.ic_copy,"Copy selected files");copy.setOnClickListener(v->saveClipboard(false));selectionBar.addView(copy);
        ImageButton cut=icon(R.drawable.ic_cut,"Move selected files");cut.setOnClickListener(v->saveClipboard(true));selectionBar.addView(cut);
        ImageButton delete=icon(R.drawable.ic_delete,"Delete selected files");delete.setId(R.id.delete);delete.setOnClickListener(v->deleteSelection());selectionBar.addView(delete);root.addView(selectionBar);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        root.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));progress.setVisibility(View.GONE);
        message=text("",12);message.setPadding(dp(12),dp(6),dp(12),dp(6));message.setBackgroundColor(theme.warning);message.setVisibility(View.GONE);root.addView(message);
        folderBar=row();folderBar.setBackgroundColor(CHROME);folderBar.setVisibility(View.GONE);
        folderUp=icon(R.drawable.ic_back,"Parent folder");folderUp.setOnClickListener(v->backFolder());folderBar.addView(folderUp);
        folderPath=text("",13);folderPath.setSingleLine();folderPath.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        folderPath.setPadding(dp(4),0,dp(12),0);folderBar.addView(folderPath,new LinearLayout.LayoutParams(0,dp(40),1));
        folderPath.setOnClickListener(v->editFolderPath());
        folderHistoryButton=icon(R.drawable.ic_history,"Folder history");
        folderHistoryButton.setOnClickListener(this::showFolderHistory);
        folderBar.addView(folderHistoryButton);
        mapListButton=icon(R.drawable.ic_list,"Show file list");mapListButton.setOnClickListener(v->showMapList(!mapList));
        folderBar.addView(mapListButton);
        root.addView(folderBar);
        mapLegend=row();mapLegend.setPadding(dp(6),dp(2),dp(6),dp(2));
        mapLegend.setContentDescription("Block color by size");mapLegend.setVisibility(View.GONE);root.addView(mapLegend);
        body=new FrameLayout(this);adapter=new ResultsAdapter();replaceResults();
        mapView=new StorageMapView(this,theme,MainActivity::size);
        mapView.labels=tile->edgeFileLabel(tile.entry,mapSort,tile.bytes);
        mapView.setVisibility(View.GONE);body.addView(mapView,new FrameLayout.LayoutParams(-1,-1));
        for(int i=0;i<StorageMapView.SIZE_LABELS.length;i++){
            TextView swatch=text(StorageMapView.SIZE_LABELS[i],11);swatch.setGravity(Gravity.CENTER);
            swatch.setBackground(background(mapView.fillColor(i),5));
            LinearLayout.LayoutParams labelParams=new LinearLayout.LayoutParams(0,dp(25),1);
            labelParams.setMargins(dp(2),0,dp(2),0);mapLegend.addView(swatch,labelParams);
        }
        sectionLabel=text("",11);sectionLabel.setId(R.id.result_section);sectionLabel.setSingleLine();sectionLabel.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        sectionLabel.setTextColor(MUTED);sectionLabel.setPadding(dp(12),0,dp(16),0);sectionLabel.setBackgroundColor(theme.surface);
        sectionLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);sectionLabel.setVisibility(View.GONE);
        body.addView(sectionLabel,new FrameLayout.LayoutParams(-1,dp(24),Gravity.TOP));
        skeleton=new LoadingSkeleton(this,activeLayout==3?LoadingSkeleton.GRID:LoadingSkeleton.LIST);skeleton.setVisibility(View.GONE);body.addView(skeleton,new FrameLayout.LayoutParams(-1,-1));
        empty=new LinearLayout(this);empty.setOrientation(LinearLayout.VERTICAL);empty.setGravity(Gravity.CENTER);empty.setPadding(dp(24),dp(16),dp(24),dp(16));empty.setBackgroundColor(theme.surface);
        emptyText=text("Loading index",16);emptyText.setGravity(Gravity.CENTER);empty.addView(emptyText,new LinearLayout.LayoutParams(-1,-2));
        emptyAction=button("Build index");emptyAction.setOnClickListener(v->{int c=category.getSelectedItemPosition();if(c==10||c==11)requestDataAccess(c);else startIndexing();});
        LinearLayout.LayoutParams actionParams=new LinearLayout.LayoutParams(-2,dp(48));actionParams.topMargin=dp(12);empty.addView(emptyAction,actionParams);body.addView(empty,new FrameLayout.LayoutParams(-1,-1));root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Loading",11);status.setId(R.id.status);status.setSingleLine();status.setEllipsize(TextUtils.TruncateAt.END);status.setTextColor(MUTED);
        status.setPadding(dp(9),dp(5),dp(9),dp(5));status.setMaxWidth(dp(280));status.setBackground(background(CHROME,12));
        FrameLayout.LayoutParams statusParams=new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.END);
        statusParams.setMargins(dp(8),dp(4),dp(8),dp(5));body.addView(status,statusParams);updateStatusVisibility();
        mapSelection=text("",12);mapSelection.setTextIsSelectable(true);
        mapSelection.setPadding(dp(12),dp(7),dp(12),dp(7));
        mapDetails=new ScrollView(this){
            @Override protected void onMeasure(int widthSpec,int heightSpec){
                int max=Math.min(MeasureSpec.getSize(heightSpec),dp(104));
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(max,MeasureSpec.AT_MOST));
            }
        };
        mapDetails.setBackground(background(CHROME,10));mapDetails.setElevation(dp(3));mapDetails.setVisibility(View.GONE);
        mapDetails.addView(mapSelection,new ScrollView.LayoutParams(-1,-2));
        FrameLayout.LayoutParams detailsParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        detailsParams.setMargins(dp(6),0,dp(6),dp(5));body.addView(mapDetails,detailsParams);
        category.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> p){}
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(position==subtypeCategory)return;
                prefs.edit().putInt("filter",position).apply();
                if(position!=0&&position!=FILE_MANAGER&&multiTypes!=0){multiTypes=0;prefs.edit().putInt("types",0).apply();}clearSelection();
                if(position!=subtypeCategory){subtype=null;subtypeCategory=position;if(subtypePopup!=null)subtypePopup.dismiss();}
                if(isDeviceCategory(position)&&rootHome==null){folderScope=null;folderHistory.clear();}
                if(isDeviceCategory(position)&&storageMap)setStorageMap(false);
                if(position==FILE_MANAGER&&folderScope==null)folderScope=internalStoragePath();
                saveBrowseLocation();
                replaceResults();optionsButton.setVisibility(isDeviceCategory(position)?View.GONE:View.VISIBLE);
                sortButton.setVisibility(isDeviceCategory(position)?View.GONE:View.VISIBLE);runSearch();}});
        query.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int c,int f){}
            public void onTextChanged(CharSequence s,int a,int b,int c){clearSelection();runSearch();}public void afterTextChanged(Editable value){}});
        query.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_SEARCH){saveHistory();query.dismissDropDown();runSearch();return true;}return false;});refreshHistory();
    }
    private static boolean isDeviceCategory(int position){return position>=9&&position<FILE_MANAGER;}
    private boolean isFileManager(){return category.getSelectedItemPosition()==FILE_MANAGER;}
    private boolean isFolderView(){return storageMap||isFileManager();}
    private int effectiveLayout(){if(storageMap)return 1;int c=category==null?0:category.getSelectedItemPosition();boolean visual=c==6||c==7||(c==0&&multiTypes!=0&&(multiTypes&~((1<<6)|(1<<7)))==0);return layoutMode==0?(visual?3:1):layoutMode;}
    private void replaceResults(){
        int next=effectiveLayout();boolean layoutChanged=next!=activeLayout;viewModeIcon(next);if(next==activeLayout&&results!=null)return;
        int position=results==null?0:results.getFirstVisiblePosition();
        int offset=results==null||results.getChildCount()==0?0:results.getChildAt(0).getTop()-results.getPaddingTop();
        thumbnails.setFlinging(false);
        if(results!=null){results.setOnScrollListener(null);results.setAdapter(null);body.removeView(results);}
        activeLayout=next;
        if(skeleton!=null)skeleton.setStyle(next==3?LoadingSkeleton.GRID:LoadingSkeleton.LIST);
        if(next==3){GridView grid=new EdgeFastScroll.Grid(this);grid.setNumColumns(GridView.AUTO_FIT);grid.setColumnWidth(dp(gridWidth));grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            grid.setHorizontalSpacing(dp(6));grid.setVerticalSpacing(dp(6));grid.setPadding(dp(8),dp(6),dp(8),dp(6));results=grid;}
        else{ListView list=new EdgeFastScroll.List(this);list.setDivider(new android.graphics.drawable.ColorDrawable(LINE));list.setDividerHeight(dp(0.5f));results=list;}
        java.util.function.IntFunction<String> label=p->{
            FileEntry entry=adapter.fileAt(p);
            if(entry!=null)return edgeFileLabel(entry,storageMap?mapSort:sort,storageMap?mapSizes.getOrDefault(entry.path(),entry.size):entry.size);
            DeviceSearch.Result device=adapter.deviceAt(p);
            return device==null?"":device.title;
        };
        if(results instanceof EdgeFastScroll.Grid)((EdgeFastScroll.Grid)results).labels=label;
        else ((EdgeFastScroll.List)results).labels=label;
        results.setId(R.id.results);results.setSaveEnabled(false);results.setClipToPadding(false);results.setAdapter(adapter);
        results.setPadding(results.getPaddingLeft(),results.getPaddingTop(),results.getPaddingRight(),dp(prefs.getBoolean("showStatus",true)?30:0));
        results.setOnItemClickListener((p,v,pos,id)->{if(longPressConsumed)return;FileEntry entry=adapter.fileAt(pos);if(entry!=null){if(selectionMode)toggle(entry,v);else{selected=entry;open(entry);}}
            else openDevice(adapter.deviceAt(pos));});
        results.setOnItemLongClickListener((p,v,pos,id)->{FileEntry entry=adapter.fileAt(pos);if(entry==null)return false;beginSelection(entry,v);return true;});
        results.setOnScrollListener(new AbsListView.OnScrollListener(){public void onScrollStateChanged(AbsListView v,int state){
                thumbnails.setFlinging(state == SCROLL_STATE_FLING);
            }
            public void onScroll(AbsListView v,int first,int visible,int count){thumbnails.viewportChanged();updateSection(first);if(visible==0||searchRunning||restoringDeviceScroll)return;
                if(isDeviceCategory(category.getSelectedItemPosition())||mixedDeviceSearch){
                    if(first<=8&&firstDevicePage>0)loadDevicePage(true);
                    else if(first<=adapter.devices.size()&&first+visible>=adapter.devices.size()-16&&deviceNext!=null)loadDevicePage(false);
                }
                }});
        FrameLayout.LayoutParams layout=new FrameLayout.LayoutParams(-1,-1);
        layout.topMargin=adapter.items.isEmpty()||isFolderView()?0:dp(24);
        body.addView(results,0,layout);if(adapter.getCount()>0)restoreResultOffset(position,layoutChanged?0:offset);updateSection(position);
    }
    private static boolean matchesType(FileEntry entry,int kind,int types){return kind==0&&types!=0?(types&(1<<FileTypes.category(entry)))!=0:FileTypes.matches(entry,kind);}
    private String typeLabel(){int first=0,count=Integer.bitCount(multiTypes);for(int type:new int[]{6,7,3,4,8,2,5,1})if((multiTypes&(1<<type))!=0){first=type;break;}
        return (first==1?"Other files":FileTypes.LABELS[first])+(count>1?" +"+(count-1):"");}
    private void chooseTypes(){int[] types={6,7,3,4,8,2,5,1};String[] labels={"Photos","Videos","Audio","Documents","Archives","Folders","Executables","Other files"};
        int kind=category.getSelectedItemPosition(),initial=multiTypes!=0?multiTypes:kind>=2&&kind<=8?1<<kind:kind==1?0x1FA:0x1FE;
        boolean[] checked=new boolean[types.length];for(int n=0;n<types.length;n++)checked[n]=(initial&(1<<types[n]))!=0;
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("File types").setMultiChoiceItems(labels,checked,(d,which,on)->checked[which]=on)
            .setNegativeButton("Cancel",null).setNeutralButton("All types",(d,w)->applyTypes(0)).setPositiveButton("Apply",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{int mask=0;for(int n=0;n<types.length;n++)if(checked[n])mask|=1<<types[n];
            if(mask==0){toast("Choose a file type");return;}applyTypes(mask==0x1FE?0:mask);dialog.dismiss();}));dialog.show();}
    private void applyTypes(int mask){multiTypes=mask;subtype=null;prefs.edit().putInt("types",mask).putInt("filter",0).apply();category.setSelection(0);
        ((BaseAdapter)category.getAdapter()).notifyDataSetChanged();category.setContentDescription(mask==0?"Search category":"File types: "+typeLabel());
        clearSelection();replaceResults();runSearch();}
    private void viewModeIcon(int style){if(viewMode!=null)viewMode.setImageResource(style==3?R.drawable.ic_grid:style==2?R.drawable.ic_compact:R.drawable.ic_list);}
    private void chooseView(){
        boolean mapAvailable=!isDeviceCategory(category.getSelectedItemPosition());
        String[] choices=mapAvailable?new String[]{"Automatic","List","Compact list","Grid","Storage map"}:new String[]{"Automatic","List","Compact list","Grid"};
        new AlertDialog.Builder(this).setTitle("View").setSingleChoiceItems(choices,storageMap?4:layoutMode,(dialog,which)->{
            dialog.dismiss();
            if(which==4){setStorageMap(true);return;}
            layoutMode=which;prefs.edit().putInt("view",which).apply();
            if(storageMap)setStorageMap(false);else replaceResults();
        }).setNegativeButton("Close",null).show();
    }
    private void setStorageMap(boolean enabled){
        if(storageMap==enabled)return;
        storageMap=enabled;mapList=false;clearSelection();browseRestore=null;
        prefs.edit().putBoolean("storageMap",enabled).apply();
        clearResultRows();adapter.notifyDataSetChanged();replaceResults();runSearch();
    }
    private void showMapList(boolean shown){
        mapList=shown;
        mapListButton.setImageResource(shown?R.drawable.ic_grid:R.drawable.ic_list);
        mapListButton.setContentDescription(shown?"Show storage map":"Show file list");
        results.setVisibility(!storageMap||shown?View.VISIBLE:View.GONE);
        mapView.setVisibility(storageMap&&!shown?View.VISIBLE:View.GONE);
        mapLegend.setVisibility(storageMap&&!shown?View.VISIBLE:View.GONE);
        updateMapDetails();
        thumbnails.viewportChanged();
    }
    private void showSubtypes(boolean expanded){
        if(searchRunning)return;if(subtypePopup!=null)subtypePopup.dismiss();
        List<FileSubtypes.Choice> choices=availableSubtypes;
        int width=Math.min(dp(312),getResources().getDisplayMetrics().widthPixels-dp(24)),columns=width>=dp(240)?3:2;
        boolean more=!expanded&&choices.size()>columns*3-1;
        int shown=more?columns*3-2:choices.size(),count=1+shown+(more?1:0),rowHeight=Math.max(dp(56),(int)(getResources().getDisplayMetrics().scaledDensity*36));
        GridView grid=new GridView(this);grid.setNumColumns(columns);grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);grid.setPadding(dp(4),dp(4),dp(4),dp(4));
        grid.setAdapter(new BaseAdapter(){
            public int getCount(){return count;}public Object getItem(int position){return position>0&&position<=shown?choices.get(position-1):null;}public long getItemId(int position){return position;}
            public View getView(int position,View recycled,ViewGroup parent){LinearLayout cell;TextView label,value;
                if(recycled==null){cell=new LinearLayout(MainActivity.this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER_VERTICAL);cell.setPadding(dp(10),dp(4),dp(6),dp(4));
                    label=text("",14);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);value=text("",11);value.setTextColor(MUTED);
                    cell.addView(label,new LinearLayout.LayoutParams(-1,-2));cell.addView(value,new LinearLayout.LayoutParams(-1,-2));cell.setTag(new TextView[]{label,value});cell.setLayoutParams(new AbsListView.LayoutParams(-1,rowHeight));
                }else{cell=(LinearLayout)recycled;TextView[] labels=(TextView[])cell.getTag();label=labels[0];value=labels[1];}
                boolean seeMore=more&&position==count-1;FileSubtypes.Choice choice=position==0||seeMore?null:choices.get(position-1);
                boolean checked=!seeMore&&(position==0?subtype==null:choice.extension.equals(subtype));
                label.setText(seeMore?"More":position==0?"All":choice.extension.isEmpty()?"No extension":choice.extension.toUpperCase(Locale.ROOT));
                long total=0;if(position==0)for(FileSubtypes.Choice option:choices)total+=option.count;
                value.setText(seeMore?"":format(position==0?total:choice.count));value.setVisibility(seeMore?View.GONE:View.VISIBLE);
                label.setTextColor(checked?BLUE:INK);cell.setBackground(background(checked?theme.selected:theme.surface,8));
                cell.setContentDescription(label.getText()+(seeMore?" file extensions":", "+value.getText()+((position==0?total:choice.count)==1?" file":" files"))+(checked?", selected":""));return cell;}
        });
        int height=Math.min(rowHeight*((count+columns-1)/columns)+dp(8),(int)(getResources().getDisplayMetrics().heightPixels*.45f));
        PopupWindow window=new PopupWindow(grid,width,height,true);window.setBackgroundDrawable(background(theme.surface,12));window.setElevation(dp(8));
        window.setOutsideTouchable(true);window.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED);subtypePopup=window;
        window.setOnDismissListener(()->{if(subtypePopup==window)subtypePopup=null;});
        grid.setOnItemClickListener((parent,view,position,id)->{window.dismiss();if(more&&position==count-1){showSubtypes(true);return;}
            subtype=position==0?null:choices.get(position-1).extension;clearSelection();runSearch();});
        window.showAsDropDown(subtypeButton,0,dp(4),Gravity.END);
    }
    private void showSort(View anchor){PopupMenu p=theme.popup(this,anchor);String[] names={"Name","Path","Size","Modified"};for(int i=0;i<names.length;i++)p.getMenu().add(0,i,0,names[i]).setCheckable(true).setChecked((storageMap?mapSort:sort)==i);
        p.getMenu().add(0,4,1,"Descending").setCheckable(true).setChecked(storageMap?mapDescending:descending);
        p.setOnMenuItemClickListener(item->{
            int choice=item.getItemId();
            if(storageMap){
                if(choice==4)mapDescending=!mapDescending;else mapSort=choice;
                prefs.edit().putInt("mapSort",mapSort).putBoolean("mapDescending",mapDescending).apply();
                orderMap(true);
            }else{if(choice==4)descending=!descending;else sort=choice;runSearch();}
            return true;
        });p.show();}
    private void searchOptions(){boolean[] checks={matchCase,wholeWord,matchPath,regex};new AlertDialog.Builder(this).setTitle("Search options")
        .setMultiChoiceItems(new String[]{"Match case","Whole word","Match path","Regular expressions"},checks,(dialog,which,checked)->{
            if(which==0)matchCase=checked;else if(which==1)wholeWord=checked;else if(which==2)matchPath=checked;else regex=checked;
            prefs.edit().putBoolean("case",matchCase).putBoolean("word",wholeWord).putBoolean("path",matchPath).putBoolean("regex",regex).apply();clearSelection();runSearch();})
        .setPositiveButton("Done",null).show();}
    private void showOverflow(View anchor){PopupMenu p=theme.popup(this,anchor);Menu m=p.getMenu();
        SubMenu search=m.addSubMenu("Search");search.add("Search options");search.add("Clear search");search.add("Recent searches");search.add("Save search");search.add("Saved searches");
        SubMenu filters=m.addSubMenu("Filters");filters.add("File types");filters.add("Hidden files").setCheckable(true).setChecked(showHidden);
        SubMenu files=m.addSubMenu("File actions");files.add("Select all");files.add("Paste").setEnabled(FileClipboard.count(this)>0);files.add("Export results");
        SubMenu storage=m.addSubMenu("Storage");storage.add("Start location");SubMenu scope=storage.addSubMenu("Index scope");
        scope.add("Root folders").setCheckable(true).setChecked(index.isRootEnabled());scope.add("Shared storage").setCheckable(true).setChecked(!index.isRootEnabled());
        storage.add("File access");storage.add("Index statistics");
        SubMenu appearance=m.addSubMenu("Appearance");appearance.add("Theme");appearance.add("Show status").setCheckable(true).setChecked(prefs.getBoolean("showStatus",true));appearance.add("Touch feedback").setCheckable(true).setChecked(prefs.getBoolean("haptics",true));
        m.add("Help");
        if (FundingSupport.action(this) != null) m.add("Support development");
        showMenu(anchor,m,"More actions",this::action);}
    private void showMenu(View anchor,Menu model,String title,java.util.function.Consumer<String> action){
        if(navigationMenu!=null)navigationMenu.dismiss();
        MenuDialog next=new MenuDialog(this,theme,anchor,model,title,action);navigationMenu=next;
        next.setOnDismissListener(dialog->{if(navigationMenu==next)navigationMenu=null;});next.show();}
    private void action(String value){switch(value){
        case "Clear search":clearSearchAndFolder(true,true);break;
        case "Select all":selectAll();break;case "Paste":paste();break;
        case "Root folders":switchStorage(true);break;case "Shared storage":switchStorage(false);break;
        case "Start location":chooseStartLocation();break;case "Search options":searchOptions();break;
        case "Recent searches":chooseQuery("Recent searches","history");break;
        case "Save search":String q=query.getText().toString().trim();if(q.isEmpty())toast("Enter a search");else{addQuery("bookmarks",q);toast("Search saved");}break;
        case "Saved searches":chooseQuery("Saved searches","bookmarks");break;
        case "Export results":
            if(provisionalSearchKey!=null){toast("Wait for the index before exporting");break;}
            if(isDeviceCategory(category.getSelectedItemPosition())){toast("Choose a file category");break;}
            Intent create=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/csv").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"file-search.csv");
            try{startActivityForResult(create,20);}catch(Exception e){toast("No document picker available");}break;
        case "Index statistics":stats();break;case "File types":chooseTypes();break;
        case "File access":waitingPermission=true;openFileSettings();break;
        case "Touch feedback":prefs.edit().putBoolean("haptics",!prefs.getBoolean("haptics",true)).apply();break;
        case "Hidden files":showHidden=!showHidden;prefs.edit().putBoolean("showHidden",showHidden).apply();clearSelection();runSearch();break;
        case "Show status":prefs.edit().putBoolean("showStatus",!prefs.getBoolean("showStatus",true)).apply();updateStatusVisibility();break;
        case "Theme":new AlertDialog.Builder(this).setTitle("Theme").setSingleChoiceItems(new String[]{"System","Light","Dark"},prefs.getInt("theme",0),(dialog,which)->{
            prefs.edit().putInt("theme",which).apply();dialog.dismiss();recreate();}).setNegativeButton("Close",null).show();break;
        case "Help":
            if(activeSheet!=null)activeSheet.dismiss();
            AppSheet help=new HelpSheet(this,index).dialog();activeSheet=help;
            help.setOnDismissListener(d->{if(activeSheet==help)activeSheet=null;});
            help.show();break;
        case "Support development":
            Runnable funding = FundingSupport.action(this);
            if (funding != null) funding.run();
            break;
    }}
    private void updateSearchControls(){
        boolean scoped=folderScope!=null&&!isDeviceCategory(category.getSelectedItemPosition()),hasQuery=query.length()>0;
        boolean back=scoped&&canGoUp();
        boolean visible=back||(hasQuery&&!storageMap);
        folderBar.setVisibility(isFolderView()?View.VISIBLE:View.GONE);
        folderPath.setText(storageMap?mapScope():folderScope);folderUp.setEnabled(back);folderUp.setAlpha(back?1f:.38f);
        boolean editable=isFileManager()&&!storageMap;
        folderPath.setClickable(editable);folderPath.setFocusable(editable);
        folderPath.setContentDescription(editable?"Edit folder path, "+folderScope:null);
        folderHistoryButton.setVisibility(editable?View.VISIBLE:View.GONE);
        query.setEnabled(!storageMap);mapListButton.setVisibility(storageMap?View.VISIBLE:View.GONE);
        updateRefreshControl();
        showMapList(mapList);
        clearSearch.setVisibility(visible?View.VISIBLE:View.GONE);query.setPadding(dp(48),0,dp(visible?48:6),0);
        String folder=scoped?new File(folderScope).getName():"";
        boolean rootFolder="/".equals(folderScope),internalFolder=scoped&&internalStoragePath().equals(folderScope);
        String location=!scoped?getString(R.string.app_name):rootFolder?"Root":internalFolder?"Internal storage":folder;
        query.setHint(!scoped?"Search":rootFolder?"Search root":internalFolder?"Search internal storage":"Search in "+folder);
        query.setContentDescription(scoped?"Search in "+folderScope:BuildConfig.SMS_ENABLED?"Search files, apps, contacts and SMS":"Search files, apps and contacts");
        String description=back?"Back to previous folder":"Clear search";
        clearSearch.setContentDescription(description);clearSearch.setTooltipText(scoped?folderScope:description);
        locationsButton.setImageTintList(ColorStateList.valueOf(scoped?BLUE:MUTED));
        locationsButton.setContentDescription("Browse locations: "+location);locationsButton.setTooltipText(scoped?folderScope:"Browse locations");
    }
    private void clearSearchOrFolder(){
        if(!isDeviceCategory(category.getSelectedItemPosition())&&canGoUp()){backFolder();return;}clearSearchAndFolder(true,false);
    }
    private void showClearOptions(){
        if(folderScope==null){clearSearchAndFolder(true,false);return;}
        PopupMenu menu=theme.popup(this,clearSearch);for(String label:new String[]{"Clear search","Clear folder","Clear both"})menu.getMenu().add(label);
        menu.setOnMenuItemClickListener(item->{String label=item.getTitle().toString();clearSearchAndFolder(!label.equals("Clear folder"),!label.equals("Clear search"));return true;});menu.show();
    }
    private void clearSearchAndFolder(boolean text,boolean folder){
        clearSelection();if(folder){folderScope=rootHome!=null?rootHome:isFileManager()?internalStoragePath():null;folderHistory.clear();browseRestore=null;}
        if(text&&query.length()>0)query.setText("");else runSearch();
        saveBrowseLocation();
    }
    private void switchStorage(boolean root){if(root==index.isRootEnabled())return;clearResultRows();clearSelection();rootHome=null;folderHistory.clear();browseRestore=null;
        folderScope=null;index.setRootEnabled(root);if(isFileManager())folderScope=internalStoragePath();
        initialRequested=true;runSearch();saveBrowseLocation();startIndexing();}
    private void openFileSettings(){try{startActivity(new Intent(Build.VERSION.SDK_INT>=30?Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION:Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}catch(Exception failure){startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));}}
    private void offerFileAccess(){
        if((BuildConfig.DEBUG&&probeIndex==index)||isFinishing()||index.isRootEnabled()||FileIndex.hasAccess(this)
                ||prefs.getBoolean("fileAccessIntroShown",false))return;
        prefs.edit().putBoolean("fileAccessIntroShown",true).apply();
        new AlertDialog.Builder(this).setTitle("File access")
                .setMessage("Allow file access to find files and folders on this device.")
                .setPositiveButton("Continue",(dialog,which)->startIndexing()).setNegativeButton("Later",null).show();
    }
    private void startIndexing(){initialRequested=true;
        if(BuildConfig.DEBUG&&probeIndex==index){index.refresh();return;}
        if(!index.isRootEnabled()&&!FileIndex.hasAccess(this)){waitingPermission=true;
        if(Build.VERSION.SDK_INT>=30)openFileSettings();else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},10);return;}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED
                &&!prefs.getBoolean("askedNotifications",false)){
            prefs.edit().putBoolean("askedNotifications",true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},32);return;
        }
        if(BuildConfig.DEBUG&&permissionProbe)return;
        try{startForegroundService(new Intent(this,IndexService.class));}catch(Exception failure){showError("Could not start indexing: "+failure.getMessage());}}
    private void requestDataAccess(int kind){String permission=kind==10?Manifest.permission.READ_CONTACTS:Manifest.permission.READ_SMS;
        String asked=kind==10?"askedContacts":"askedSms";if(prefs.getBoolean(asked,false)&&!shouldShowRequestPermissionRationale(permission)){
            waitingPermission=true;startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())));
        }else{prefs.edit().putBoolean(asked,true).apply();requestPermissions(new String[]{permission},kind==10?30:31);}}
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){super.onRequestPermissionsResult(request,permissions,grants);
        if(request==10&&FileIndex.hasAccess(this))startIndexing();else if(request==32)startIndexing();else if(request==30||request==31)runSearch();}
    @Override public void onChanged(FileIndex.State next){boolean changed=state.scanning!=next.scanning;state=next;
        updateRefreshControl();
        if(next.scanning){initialRequested=true;if(!isFolderView())status.setText(next.status.startsWith("Updating")?next.status:"Indexing "+format(next.discoveredItems));}
        if(next.error!=null&&!next.error.isEmpty())showError(next.error);
        boolean entriesChanged=next.entries!=lastEntries;
        if(entriesChanged||changed){lastEntries=next.entries;
            if(!searchRunning&&!isDeviceCategory(category.getSelectedItemPosition())&&(!isFolderView()||entriesChanged))updateFiles();}
        if(next.loaded&&!next.scanning&&!initialRequested){initialRequested=true;checkUpdates();}
        updateEmpty();}
    private void updateRefreshControl(){
        boolean indexing=state.scanning&&!isFolderView();
        progress.setVisibility(indexing?View.VISIBLE:View.GONE);
        refresh.setImageResource(indexing?R.drawable.ic_stop:R.drawable.ic_refresh);
        refresh.setContentDescription(storageMap?"Refresh storage map":isFileManager()?"Refresh folder":indexing?"Stop indexing":"Refresh index");
        refresh.setTooltipText(refresh.getContentDescription());
    }
    private void updateStatusVisibility(){if(status==null)return;boolean shown=prefs.getBoolean("showStatus",true);
        updateStatusOverlay(shown);
        if(results!=null)results.setPadding(results.getPaddingLeft(),results.getPaddingTop(),results.getPaddingRight(),dp(shown?30:0));}
    private void updateStatusOverlay(boolean shown){
        status.setVisibility(shown&&(mapDetails==null||mapDetails.getVisibility()!=View.VISIBLE)?View.VISIBLE:View.GONE);
    }
    private String edgeFileLabel(FileEntry entry,int field,long bytes){
        if(field==1)return entry.path();
        if(field==2)return size(bytes)+" · "+entry.name;
        if(field==3)return (entry.modified>0?DateFormat.getDateTimeInstance().format(new Date(entry.modified)):"Date unknown")+" · "+entry.name;
        return entry.name;
    }
    private void updateSection(int position){
        if(sectionLabel==null||results==null)return;
        boolean visible=!isFolderView()&&!adapter.items.isEmpty();sectionLabel.setVisibility(visible?View.VISIBLE:View.GONE);
        if(visible){DeviceSearch.Result device=adapter.deviceAt(position);
            String label=device==null?adapter.sections.labelAt(Math.max(0,position-adapter.devices.size())):FileTypes.LABELS[device.domain+8];
            if(!TextUtils.equals(sectionLabel.getText(),label))sectionLabel.setText(label);}
        FrameLayout.LayoutParams layout=(FrameLayout.LayoutParams)results.getLayoutParams();
        int top=visible?dp(24):0;if(layout!=null&&layout.topMargin!=top){layout.topMargin=top;results.setLayoutParams(layout);}
    }
    private void checkUpdates(){if(!state.loaded||state.scanning||state.lastScanMillis==0
            ||(BuildConfig.DEBUG&&permissionProbe)||probeIndex==index||(!index.isRootEnabled()&&!FileIndex.hasAccess(this)))return;
        index.discoverOnOpen(folderScope);
        index.checkChanges();}
    private SearchQuery.Options options(){SearchQuery.Options o=new SearchQuery.Options();o.matchCase=matchCase;o.wholeWord=wholeWord;o.matchPath=matchPath;o.regex=regex;return o;}
    private Comparator<FileEntry> comparator(int field,boolean reverse){Comparator<FileEntry> c;
        Comparator<FileEntry> byName=Comparator.comparing((FileEntry entry)->entry.name,String.CASE_INSENSITIVE_ORDER)
                .thenComparing(FileEntry::comparePath);
        if(field==2||field==3){
            c=Comparator.comparingLong(entry->field==2?entry.size:entry.modified);
            return (reverse?c.reversed():c).thenComparing(byName);
        }
        c=field==1?((Comparator<FileEntry>)FileEntry::comparePathIgnoreCase).thenComparing(FileEntry::comparePath):byName;
        return reverse?c.reversed():c;}
    private void refreshOpenFolder(){
        if(hasWindowFocus()){
            if(isFileManager()&&!storageMap&&!searchRunning)updateFiles();
            if(state.loaded&&state.lastScanMillis>0){
                String folder=folderScope;
                try{fileActions.execute(()->index.probeForegroundChanges(folder));}
                catch(RejectedExecutionException ignored){}
            }
        }
        ui.postDelayed(folderRefresh,5000);
    }
    private void updateFiles(){runSearch(!searchRunning||refreshResults);}
    private void runSearch(){runSearch(false);}
    private void runSearch(boolean refreshViewport){if(query==null||state==null||searches.isShutdown())return;
        boolean map=storageMap,manager=isFileManager()&&!map;
        int ticket=sequence.incrementAndGet(),kind=manager||map?0:category.getSelectedItemPosition(),field=sort,types=manager||map?0:multiTypes;
        boolean reverse=descending,includeHidden=showHidden;
        String input=map?"":query.getText().toString(),scope=map?mapScope():isDeviceCategory(kind)?null:folderScope,extension=map?null:subtype;
        SearchQuery.Options opts=options();List<FileEntry> entries=state.entries;
        boolean fileSearch = !manager && !map && kind < 9;
        String searchKey = fileSearch ? new JSONArray().put(index.isRootEnabled()).put(kind).put(types)
                .put(field).put(reverse).put(includeHidden).put(input).put(scope).put(extension)
                .put(opts.matchCase).put(opts.wholeWord).put(opts.matchPath).put(opts.regex).toString() : null;
        if (saveSearchRows != null) ui.removeCallbacks(saveSearchRows);
        if (provisionalSearchKey != null && !Objects.equals(provisionalSearchKey, searchKey)) {
            clearResultRows();
            adapter.notifyDataSetChanged();
        }
        if(map&&!Objects.equals(mapRowsScope,scope)&&!adapter.items.isEmpty()){
            clearResultRows();adapter.notifyDataSetChanged();
        }
        boolean subtypes=(kind>0&&kind<9&&kind!=2)||(kind==0&&types!=0&&(types&~(1<<2))!=0);
        subtypeButton.setVisibility(subtypes?View.VISIBLE:View.GONE);subtypeButton.setImageTintList(ColorStateList.valueOf(subtype==null?INK:BLUE));
        subtypeButton.setContentDescription(subtype==null?"File extensions":"File extension: "+(subtype.isEmpty()?"No extension":subtype));
        updateSearchControls();
        if(pending!=null)pending.cancel(true);searches.purge();if(deviceCancellation!=null)deviceCancellation.cancel();
        if (fileSearch && !state.loaded && !selectionMode) {
            searchRunning = false;
            if (provisionalSearchKey == null && (index.isRootEnabled() || FileIndex.hasAccess(this))) {
                List<FileEntry> saved = SavedSearchRows.read(prefs, searchKey);
                if (!saved.isEmpty()) {
                    provisionalSearchKey = searchKey;
                    adapter.items = saved;
                    adapter.sections = FileSections.build(saved, field);
                    adapter.notifyDataSetChanged();
                }
            }
            status.setText(provisionalSearchKey == null ? "Loading index…" : "Saved results · loading index…");
            updateEmpty();
            return;
        }
        final CancellationSignal cancellation=new CancellationSignal();deviceCancellation=cancellation;searchRunning=true;refreshResults=refreshViewport;
        List<FileEntry> previousRows=adapter.items;
        if(map)status.setText(mapTotalBytes>0?size(mapTotalBytes)+" • Checking storage…":"Checking storage…");
        else if(adapter.items.isEmpty()&&kind<9)status.setText(state.loaded||manager?"Loading results…":"Loading index…");
        mixedDeviceSearch=!manager&&!map&&kind==0&&types==0&&scope==null&&input.trim().length()>=2&&!regex&&!input.contains(":")&&!input.contains("|");
        boolean combined=mixedDeviceSearch;boolean preserveDevices=refreshViewport&&!devicePages.isEmpty();
        List<DeviceSearch.Result> preserved=preserveDevices?adapter.devices:null;
        if(!preserveDevices){devicePages.clear();deviceStarts.clear();deviceStarts.add(null);deviceOffsets.clear();deviceOffsets.add(0L);firstDevicePage=0;deviceNext=null;}
        updateEmpty();
        try{pending=searches.submit(()->{long begin=System.nanoTime();try{
            List<FileEntry> candidates=manager?index.listFolder(scope,cancellation):entries;
            boolean reuse=!manager&&!map&&kind==0&&types==0&&extension==null&&scope==null&&field==0&&!reverse&&includeHidden&&input.trim().isEmpty()&&!opts.regex;
            boolean rootOverview=!manager&&!map&&kind==0&&types==0&&extension==null&&"/".equals(scope)
                    &&input.trim().isEmpty()&&!opts.regex;
            RootSearchResult cached=rootSearchResult;
            if(cached!=null&&cached.snapshot.get()!=entries){rootSearchResult=null;cached=null;}
            boolean reuseRoot=rootOverview&&cached!=null&&cached.sort==field&&cached.descending==reverse
                    &&cached.showHidden==includeHidden;
            List<FileEntry> matchesInOrder=reuseRoot?cached.rows:reuse?entries:new ArrayList<>();
            long count=matchesInOrder.size();Map<String,Integer> extensionCounts=new HashMap<>();
            List<StorageMapData.Tile> tiles;
            int unreadable;
            if(map){
                StorageMapData data=new StorageMapData(scope,includeHidden);
                if(previousRows.isEmpty()&&!entries.isEmpty()){
                    StorageMapData saved=new StorageMapData(scope,includeHidden);
                    for(int i=0;i<entries.size();i++){
                        if((i&4095)==0)cancellation.throwIfCanceled();
                        saved.add(entries.get(i));
                    }
                    List<StorageMapData.Tile> initial=saved.tiles();
                    if(!initial.isEmpty())ui.post(()->showIndexedMap(ticket,scope,initial));
                }
                unreadable=index.scanMap(scope,includeHidden,cancellation,data);tiles=data.tiles();
                for(StorageMapData.Tile tile:tiles)matchesInOrder.add(tile.entry);
                count=matchesInOrder.size();
            }else{tiles=Collections.emptyList();unreadable=0;}
            if(kind<9&&!reuse&&!reuseRoot&&!map){SearchQuery matcher=SearchQuery.compile(input,opts);Comparator<FileEntry> cmp=comparator(field,reverse);
                String scopePrefix=scope==null?null:scope.endsWith("/")?scope:scope+"/";
                for(int i=0;i<candidates.size();i++){if((i&511)==0&&(Thread.currentThread().isInterrupted()||sequence.get()!=ticket))return;
                    FileEntry e=candidates.get(i);if(!includeHidden&&e.hidden)continue;
                    if(!manager&&scopePrefix!=null&&!e.parent.equals(scope)&&!e.parent.startsWith(scopePrefix))continue;
                    if(!rootOverview&&(!matchesType(e,kind,types)||!matcher.matches(e)))continue;
                    if(subtypes&&!e.directory){String ext=FileSubtypes.extension(e);extensionCounts.put(ext,extensionCounts.getOrDefault(ext,0)+1);}
                    if(!FileSubtypes.matches(e,extension))continue;count++;matchesInOrder.add(e);}
                if(manager)matchesInOrder.sort(Comparator.comparing((FileEntry entry)->!entry.directory).thenComparing(cmp));
                else if(field!=0){
                    Comparator<FileEntry> cancellable=(left,right)->{
                        if(sequence.get()!=ticket)throw new CancellationException();
                        return cmp.compare(left,right);
                    };
                    if(matchesInOrder.size()>=65_536&&(field==2||field==3)){
                        matchesInOrder=MetadataOrder.sort(matchesInOrder,field==3,reverse,()->{
                            if(sequence.get()!=ticket)throw new CancellationException();
                        });
                    }else if(rootOverview&&matchesInOrder.size()>=65_536){
                        FileEntry[] ordered=matchesInOrder.toArray(new FileEntry[0]);
                        Arrays.parallelSort(ordered,cancellable);
                        matchesInOrder=Arrays.asList(ordered);
                    }else matchesInOrder.sort(cancellable);
                }else if(reverse)Collections.reverse(matchesInOrder);}
            List<FileEntry> found=matchesInOrder;
            FileSections sections=manager||map?FileSections.EMPTY:reuseRoot?cached.sections:FileSections.build(found,field);
            if(rootOverview&&!reuseRoot&&!entries.isEmpty()&&sequence.get()==ticket){
                rootSearchResult=new RootSearchResult(entries,field,reverse,includeHidden,found,sections);
            }
            List<DeviceSearch.Result> devices=Collections.emptyList();DeviceSearch.Page fetched=null;
            if(preserved!=null)devices=preserved;
            else if(kind>=9||combined){fetched=deviceSearch.page(input,kind>=9?kind-8:DeviceSearch.ALL,null,cancellation);devices=fetched.results;}
            final List<FileSubtypes.Choice> subtypeChoices=FileSubtypes.sorted(extensionCounts);
            boolean sameRows=refreshViewport&&!map&&sameResults(previousRows,found);
            final long matches=count,elapsed=(System.nanoTime()-begin)/1000000;final List<DeviceSearch.Result> deviceResults=devices;final DeviceSearch.Page firstPage=fetched;
            ui.post(()->{if(sequence.get()!=ticket||isDestroyed())return;searchRunning=false;
                Bundle restore=browseRestore;
                if(restore==null&&refreshViewport&&results.getChildCount()>0){
                    Bundle visible=browseLocation();
                    if(selectionMode||visible.getInt("position")!=0||visible.getInt("top")<0)restore=visible;
                }
                boolean changed=provisionalSearchKey!=null||!sameRows||adapter.devices!=deviceResults
                        ||(map&&tiles.stream().anyMatch(tile->!Objects.equals(mapSizes.get(tile.entry.path()),tile.bytes)));
                provisionalSearchKey = null;
                if(changed)retainSelection(found);
                adapter.items=found;adapter.devices=deviceResults;adapter.sections=sections;
                if(changed){
                    mapSizes.clear();
                    mapTotalBytes=0;
                    if(map){
                        showMapTiles(scope,tiles);
                    }
                }
                availableSubtypes=subtypeChoices;
                if(firstPage!=null){devicePages.add(firstPage);deviceNext=firstPage.next;if(deviceNext!=null){deviceStarts.add(deviceNext);deviceOffsets.add((long)firstPage.results.size());}}
                totalFileMatches=matches;lastSearchMillis=elapsed;
                if(changed){adapter.notifyDataSetChanged();
                    if(restore!=null){if(state.loaded||manager||map){restoreBrowsePosition(restore);browseRestore=null;}}
                    else results.setSelection(0);
                }
                if(map&&unreadable>0)showError("Some entries could not be read ("+format(unreadable)+").");
                else if(manager||map||state.error==null||state.error.isEmpty())message.setVisibility(View.GONE);
                if(manager&&!refreshViewport){
                    List<String> recent=stored("folderRecents");
                    if(recent.isEmpty()||!scope.equals(recent.get(0)))addQuery("folderRecents",scope);
                }
                if(map)status.setText(size(tiles.stream().mapToLong(tile->tile.bytes).sum())+" • "+format(matches)+" items");
                else if(manager||!state.scanning)status.setText(resultCount(matches+deviceResults.size(),deviceNext!=null));
                updateSelection();updateEmpty();
                if (fileSearch && state.loaded && state.entries == entries && state.error.isEmpty()) {
                    List<FileEntry> page = new ArrayList<>(found.subList(0, Math.min(found.size(), SavedSearchRows.MAX_ROWS)));
                    saveSearchRows = () -> SavedSearchRows.write(prefs, searchKey, page);
                    ui.postDelayed(saveSearchRows, 500);
                }
                if(!reportedFullyDrawn&&(state.loaded||kind>=9)){reportedFullyDrawn=true;reportFullyDrawn();}
                if(!manager&&!map&&kind<9&&state.entries!=entries)runSearch(true);});
        }catch(android.os.OperationCanceledException|java.util.concurrent.CancellationException ignored){}catch(Exception failure){ui.post(()->{if(sequence.get()!=ticket||isDestroyed())return;searchRunning=false;
            if(!refreshViewport){clearResultRows();adapter.notifyDataSetChanged();}
            showError(failure.getMessage());status.setText(manager||map?"Folder unavailable":"Search unavailable");updateEmpty();});}});
        }catch(RejectedExecutionException busy){searchRunning=false;}}
    private void clearResultRows(){
        provisionalSearchKey = null;
        adapter.items=Collections.emptyList();adapter.devices=Collections.emptyList();
        adapter.sections=FileSections.EMPTY;totalFileMatches=0;
        mapView.clear();
        mapRowsScope=null;
        mapTiles=Collections.emptyList();
        if(pendingMapOrder!=null)pendingMapOrder.cancel(true);
        mapSizes.clear();mapTotalBytes=0;
    }
    private void showIndexedMap(int ticket,String scope,List<StorageMapData.Tile> tiles){
        if(sequence.get()!=ticket||isDestroyed()||!adapter.items.isEmpty())return;
        List<FileEntry> rows=new ArrayList<>(tiles.size());
        for(StorageMapData.Tile tile:tiles)rows.add(tile.entry);
        adapter.items=rows;adapter.sections=FileSections.EMPTY;totalFileMatches=rows.size();
        showMapTiles(scope,tiles);adapter.notifyDataSetChanged();
        status.setText(size(mapTotalBytes)+" • Checking storage…");
        updateSelection();updateEmpty();
    }
    private void showMapTiles(String scope,List<StorageMapData.Tile> tiles){
        mapRowsScope=scope;
        mapTiles=tiles;
        mapSizes.clear();mapTotalBytes=0;
        for(StorageMapData.Tile tile:tiles){mapSizes.put(tile.entry.path(),tile.bytes);mapTotalBytes+=tile.bytes;}
        displayMapOrder(scope,tiles);
        if(mapSort!=2||!mapDescending)orderMap(false);
    }
    private void orderMap(boolean fromTop){
        List<StorageMapData.Tile> source=mapTiles;
        if(source.isEmpty())return;
        if(pendingMapOrder!=null)pendingMapOrder.cancel(true);
        mapOrders.purge();
        int field=mapSort;boolean reverse=mapDescending;
        if(field==2&&reverse){applyMapOrder(source,fromTop);return;}
        Runnable sortTiles=()->{
            List<StorageMapData.Tile> ordered=new ArrayList<>(source);
            Comparator<StorageMapData.Tile> order=StorageMapData.order(field,reverse);
            ordered.sort((a,b)->{
                if(Thread.currentThread().isInterrupted())throw new CancellationException();
                return order.compare(a,b);
            });
            if(Looper.myLooper()==Looper.getMainLooper())applyMapOrder(ordered,fromTop);
            else ui.post(()->{
                if(storageMap&&!isDestroyed()&&source==mapTiles&&field==mapSort&&reverse==mapDescending)applyMapOrder(ordered,fromTop);
            });
        };
        if(source.size()<4096)sortTiles.run();else pendingMapOrder=mapOrders.submit(sortTiles);
    }
    private void applyMapOrder(List<StorageMapData.Tile> ordered,boolean fromTop){
        List<FileEntry> rows=new ArrayList<>(ordered.size());
        for(StorageMapData.Tile tile:ordered)rows.add(tile.entry);
        adapter.items=rows;
        displayMapOrder(mapRowsScope,ordered);
        adapter.notifyDataSetChanged();
        if(fromTop){mapView.setSelectionFromTop(0,0);results.setSelection(0);}
    }
    private void displayMapOrder(String scope,List<StorageMapData.Tile> tiles){
        mapView.show(scope,tiles,e->{if(selectionMode)toggle(e,mapView);else open(e);},
                e->{longPressConsumed=false;beginSelection(e,mapView);},
                e->{mapPreview=e;updateMapDetails();});
    }
    private static boolean sameResults(List<FileEntry> previous,List<FileEntry> next){
        if(previous==next)return true;
        if(previous.size()!=next.size())return false;
        for(int i=0;i<previous.size();i++){
            FileEntry a=previous.get(i),b=next.get(i);
            if(a!=b&&(a.size!=b.size||a.modified!=b.modified||a.directory!=b.directory||a.comparePath(b)!=0))return false;
        }
        return true;
    }
    private void retainSelection(List<FileEntry> next){
        if(!selectionMode||adapter.items.isEmpty())return;
        Set<String> previous=new HashSet<>();
        if(allSelected)for(FileEntry entry:adapter.items)previous.add(entry.path());
        Set<String> stillMarked=new HashSet<>();
        for(FileEntry entry:next)
            if(selection.contains(entry.path())||(allSelected&&!previous.contains(entry.path())))stillMarked.add(entry.path());
        selection.clear();selection.addAll(stillMarked);
    }
    private void loadDevicePage(boolean backwards){
        if(searchRunning||searches.isShutdown())return;
        int pageIndex=backwards?firstDevicePage-1:firstDevicePage+devicePages.size();
        if(pageIndex<0||(!backwards&&deviceNext==null))return;
        String cursor=backwards?deviceStarts.get(pageIndex):deviceNext;
        int ticket=sequence.incrementAndGet(),kind=category.getSelectedItemPosition();String input=query.getText().toString();
        CancellationSignal signal=new CancellationSignal();deviceCancellation=signal;searchRunning=true;
        try{pending=searches.submit(()->{try{DeviceSearch.Page page=deviceSearch.page(input,kind>=9?kind-8:DeviceSearch.ALL,cursor,signal);
            ui.post(()->{if(isDestroyed()||sequence.get()!=ticket)return;
                int first=results.getFirstVisiblePosition(),top=results.getChildCount()==0?0:results.getChildAt(0).getTop(),shift=0;
                restoringDeviceScroll=true;
                if(backwards){devicePages.add(0,page);firstDevicePage--;shift=page.results.size();
                    if(devicePages.size()>20)devicePages.remove(devicePages.size()-1);}
                else{devicePages.add(page);if(devicePages.size()>20){shift=-devicePages.remove(0).results.size();firstDevicePage++;}}
                int following=pageIndex+1;
                if(page.next!=null){while(deviceStarts.size()<=following){deviceStarts.add(null);deviceOffsets.add(0L);}deviceStarts.set(following,page.next);deviceOffsets.set(following,deviceOffsets.get(pageIndex)+page.results.size());}
                deviceNext=devicePages.get(devicePages.size()-1).next;
                ArrayList<DeviceSearch.Result> rows=new ArrayList<>();for(DeviceSearch.Page part:devicePages)rows.addAll(part.results);
                adapter.devices=rows;adapter.notifyDataSetChanged();
                if(results instanceof ListView)((ListView)results).setSelectionFromTop(Math.max(0,first+shift),top);else results.setSelection(Math.max(0,first+shift));
                searchRunning=false;long offset=deviceOffsets.get(firstDevicePage);status.setText(resultCount(totalFileMatches+offset+rows.size(),deviceNext!=null));
                ui.post(()->{restoringDeviceScroll=false;});updateEmpty();});
            }catch(android.os.OperationCanceledException ignored){}catch(Exception failure){ui.post(()->{if(isDestroyed()||sequence.get()!=ticket)return;searchRunning=false;showError(failure.getMessage());});}});
        }catch(RejectedExecutionException busy){searchRunning=false;}
    }
    private void updateEmpty(){if(empty==null)return;updateSection(results.getFirstVisiblePosition());boolean noResults=adapter.getCount()==0;int categoryPosition=category.getSelectedItemPosition();
        boolean waiting=(isFileManager()&&!storageMap&&searchRunning&&!refreshResults)
                ||(noResults&&(searchRunning||(categoryPosition<9&&!storageMap&&(!state.loaded||state.scanning))));
        boolean hideFolderContent=isFolderView()&&(waiting||noResults);
        results.setVisibility(storageMap&&!mapList?View.GONE:hideFolderContent?View.INVISIBLE:View.VISIBLE);
        mapView.setVisibility(!storageMap||mapList?View.GONE:hideFolderContent?View.INVISIBLE:View.VISIBLE);
        boolean coldLoading=noResults&&(!state.loaded||state.lastScanMillis==0&&state.scanning);
        skeleton.setStyle(coldLoading?LoadingSkeleton.BRAND:activeLayout==3?LoadingSkeleton.GRID:LoadingSkeleton.LIST);
        skeleton.setVisibility(waiting?View.VISIBLE:View.GONE);empty.setVisibility(noResults&&!waiting?View.VISIBLE:View.GONE);emptyAction.setVisibility(View.GONE);if(!noResults)return;
        int c=category.getSelectedItemPosition();if(isFolderView())emptyText.setText(query.length()==0||storageMap?"Folder is empty":"No matches in this folder");
        else if(c==10&&!DeviceSearch.hasContacts(this)){emptyText.setText("Search contacts by name or number");emptyAction.setText("Allow Contacts");emptyAction.setVisibility(View.VISIBLE);}
        else if(c==11&&!DeviceSearch.hasSms(this)){emptyText.setText("Search SMS by sender or message");emptyAction.setText("Allow SMS");emptyAction.setVisibility(View.VISIBLE);}
        else if(c>=9)emptyText.setText(searchRunning?"Searching":"No results");
        else if(!state.loaded)emptyText.setText("Loading index");else if(state.scanning)emptyText.setText("Building file index");
        else if(state.entries.isEmpty()){emptyText.setText(state.error!=null&&!state.error.isEmpty()?state.error:"Find files and folders on this device");emptyAction.setText(index.isRootEnabled()||FileIndex.hasAccess(this)?"Build index":"Allow file access");emptyAction.setVisibility(View.VISIBLE);}
        else emptyText.setText("No results");}
    private boolean isSelected(FileEntry e){return allSelected!=selection.contains(e.path());}
    private int gridTilePixels(){int width=results==null?getResources().getDisplayMetrics().widthPixels:results.getWidth();
        if(width<=0)width=getResources().getDisplayMetrics().widthPixels;
        int available=Math.max(dp(72),width-dp(16)),columns=Math.max(1,(available+dp(6))/(dp(gridWidth)+dp(6)));
        return Math.max(dp(64),(available-dp(6)*(columns-1))/columns);}
    private void initResultPinch(){resultPinch=new android.view.ScaleGestureDetector(this,new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener(){
        @Override public boolean onScaleBegin(android.view.ScaleGestureDetector detector){pinchScale=1;return pinchArmed;}
        @Override public boolean onScale(android.view.ScaleGestureDetector detector){
            pinchScale*=detector.getScaleFactor();if(pinchScale>1.20f||pinchScale<.83f){changeDensity(pinchScale>1?1:-1,detector.getFocusX()+screenOffsetX,detector.getFocusY()+screenOffsetY);pinchScale=1;}return true;}
    });resultPinch.setQuickScaleEnabled(false);}
    private void changeDensity(int direction,float x,float y){if(results==null||adapter.getCount()==0)return;
        int[] screen=new int[2];results.getLocationOnScreen(screen);
        int anchor=results.pointToPosition((int)(x-screen[0]),(int)(y-screen[1]));if(anchor<0)anchor=results.getFirstVisiblePosition();
        View child=results.getChildAt(anchor-results.getFirstVisiblePosition());int top=child==null?0:child.getTop();
        int[] widths={72,96,128,180,240};int level=activeLayout==2?0:activeLayout==1?1:2;
        if(activeLayout==3){int distance=Integer.MAX_VALUE;for(int i=0;i<widths.length;i++){int delta=Math.abs(widths[i]-gridWidth);if(delta<distance){distance=delta;level=i+2;}}}
        int next=Math.max(0,Math.min(6,level+direction));if(next==level)return;
        layoutMode=next==0?2:next==1?1:3;if(next>=2)gridWidth=widths[next-2];
        prefs.edit().putInt("view",layoutMode).putInt("gridWidth",gridWidth).apply();activeLayout=-1;replaceResults();
        int position=Math.max(0,Math.min(anchor,adapter.getCount()-1));
        // GridView inherits AbsListView's touch-mode LAYOUT_SPECIFIC with selection -1.
        // At one column that becomes an invalid row; only ListView supports this offset restore.
        if(results instanceof ListView)((ListView)results).setSelectionFromTop(position,top);
        else results.setSelection(position);haptic(results);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event){
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            View content=empty!=null&&empty.getVisibility()==View.VISIBLE?empty:storageMap&&!mapList?mapView:results;
            android.graphics.Rect bounds=new android.graphics.Rect();
            pullRefreshArmed=content!=null&&content.getVisibility()==View.VISIBLE
                    &&content.getGlobalVisibleRect(bounds)&&bounds.contains((int)event.getRawX(),(int)event.getRawY())
                    &&event.getRawX()<bounds.right-dp(24)&&!content.canScrollVertically(-1)&&!selectionMode&&refresh.isEnabled();
            pullStartX=event.getRawX();
            pullStartY=event.getRawY();
        }else if(action==MotionEvent.ACTION_POINTER_DOWN||action==MotionEvent.ACTION_CANCEL){
            pullRefreshArmed=false;
        }else if(pullRefreshArmed&&action==MotionEvent.ACTION_MOVE){
            float distance=event.getRawY()-pullStartY;
            float sideways=Math.abs(event.getRawX()-pullStartX);
            if(distance< -dp(12)||sideways>Math.max(dp(24),distance))pullRefreshArmed=false;
        }else if(action==MotionEvent.ACTION_UP){
            boolean pulled=pullRefreshArmed&&!longPressConsumed&&!selectionMode
                    &&event.getRawY()-pullStartY>=dp(72)
                    &&event.getRawY()-pullStartY>Math.abs(event.getRawX()-pullStartX)*2;
            pullRefreshArmed=false;
            if(pulled){
                MotionEvent cancel=MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                // A pull is a check for changes. The toolbar retains explicit index controls.
                haptic(refresh);
                int kind=category.getSelectedItemPosition();
                if(isDeviceCategory(kind)){deviceSearch.invalidateApps();runSearch(true);}
                else if(isFolderView()){
                    runSearch(true);
                    index.discoverOnOpen(storageMap?mapScope():folderScope);
                    toast(getString(R.string.app_name)+" · Checking folder");
                }else if(state.lastScanMillis>0){
                    index.discoverOnOpen(folderScope);
                    if(index.checkChangesNow())toast(getString(R.string.app_name)+" · Checking for changes");
                }else if(!state.scanning){
                    status.setText("Build the index to search this storage");
                }
                return true;
            }
        }
        if(action==MotionEvent.ACTION_DOWN){longPressConsumed=false;pinchingResults=false;pinchArmed=false;
            if(results!=null){android.graphics.Rect bounds=new android.graphics.Rect();results.getGlobalVisibleRect(bounds);
                pinchArmed=!storageMap&&bounds.contains((int)event.getRawX(),(int)event.getRawY())&&!isDeviceCategory(category.getSelectedItemPosition());}}
        if(pinchArmed&&resultPinch!=null){
            screenOffsetX=event.getRawX()-event.getX();screenOffsetY=event.getRawY()-event.getY();
            if(event.getPointerCount()>1&&!pinchingResults){pinchingResults=true;
                MotionEvent cancel=MotionEvent.obtain(event);cancel.setAction(MotionEvent.ACTION_CANCEL);super.dispatchTouchEvent(cancel);cancel.recycle();}
            resultPinch.onTouchEvent(event);
            if(pinchingResults){if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){pinchingResults=false;pinchArmed=false;}return true;}
        }
        return super.dispatchTouchEvent(event);
    }

    private void beginSelection(FileEntry entry,View source){if(longPressConsumed)return;
        if(provisionalSearchKey!=null){toast("Wait for the index before selecting files");return;}
        longPressConsumed=true;enterSelection();
        if(!isSelected(entry)){if(allSelected)selection.remove(entry.path());else selection.add(entry.path());}selected=entry;haptic(source);updateSelection();adapter.notifyDataSetChanged();}
    private void enterSelection(){selectionMode=true;query.dismissDropDown();query.clearFocus();
        getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(query.getWindowToken(),0);
        if(results.isShown())results.requestFocus();}
    private long selectionSize(){return allSelected?Math.max(0,totalFileMatches-selection.size()):selection.size();}
    private void toggle(FileEntry entry,View source){enterSelection();if(!selection.remove(entry.path()))selection.add(entry.path());selected=entry;haptic(source);updateSelection();adapter.notifyDataSetChanged();}
    private void clearSelection(){selectionMode=false;allSelected=false;longPressConsumed=false;selection.clear();if(selectionBar!=null){updateSelection();adapter.notifyDataSetChanged();}}
    private void updateSelection(){if(selectionBar==null)return;selectionBar.setVisibility(selectionMode?View.VISIBLE:View.GONE);controls.setVisibility(selectionMode?View.GONE:View.VISIBLE);selectionCount.setText(format(selectionSize())+" selected");
        if(mapSelection==null)return;
        if(storageMap){
            mapView.updateSelection(this::isSelected);
            if(selectionMode&&(selected==null||!isSelected(selected)||!mapSizes.containsKey(selected.path()))){
                selected=null;
                for(FileEntry entry:adapter.items)if(isSelected(entry)){selected=entry;break;}
            }
        }
        updateMapDetails();
    }
    private void updateMapDetails(){
        if(mapDetails==null)return;
        FileEntry entry=mapPreview==null?selected:mapPreview;
        Long bytes=entry==null?null:mapSizes.get(entry.path());
        boolean show=storageMap&&!mapList&&selectionMode&&bytes!=null;
        mapDetails.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){
            String share=mapTotalBytes>0?"  •  "+String.format(Locale.getDefault(),"%.1f%% of folder",bytes*100.0/mapTotalBytes):"";
            SpannableStringBuilder details=new SpannableStringBuilder(entry.name).append('\n').append(entry.path())
                    .append('\n').append(StorageMapView.typeLabel(entry)).append("  •  ").append(size(bytes)).append(share);
            details.setSpan(new StyleSpan(Typeface.BOLD),0,entry.name.length(),0);
            if(!TextUtils.equals(mapSelection.getText(),details)){mapSelection.setText(details);mapDetails.scrollTo(0,0);}
        }
        updateStatusOverlay(prefs.getBoolean("showStatus",true));
    }
    private void selectAll(){if(totalFileMatches==0){toast("No files to select");return;}enterSelection();allSelected=true;selection.clear();haptic(results);updateSelection();adapter.notifyDataSetChanged();}
    private void selectionMenu(View anchor){PopupMenu p=theme.popup(this,anchor);for(String s:new String[]{"Select all","Invert selection","Clear selection","Share","Rename","Copy path","Properties"})p.getMenu().add(s);
        if(storageMap&&selected!=null&&selectionSize()==1)p.getMenu().add("Open");
        showMenu(anchor,p.getMenu(),"Selection",value->{switch(value){case "Select all":selectAll();break;case "Invert selection":allSelected=!allSelected;updateSelection();adapter.notifyDataSetChanged();break;
            case "Clear selection":clearSelection();break;case "Share":shareSelection();break;case "Rename":renameSelection();break;case "Copy path":if(selected!=null)copyPath(selected.path());break;case "Properties":if(selected!=null)properties(selected);break;
            case "Open":if(selected!=null)open(selected);break;}});}
    private void saveClipboard(boolean move){if(selectionSize()==0){toast("Select files");return;}if(selectionSize()>512){toast("Choose up to 512 items at once");return;}
        List<FileEntry> entries=adapter.items;Set<String> marked=new HashSet<>(selection);boolean invert=allSelected,root=index.isRootEnabled();
        submitFileAction(()->{ArrayList<FileEntry> chosen=new ArrayList<>();
            for(FileEntry e:entries){if(Thread.currentThread().isInterrupted())return;if(invert!=marked.contains(e.path()))chosen.add(e);}
            FileClipboard.save(getApplicationContext(),chosen,move,root);ui.post(()->{if(isDestroyed())return;haptic(results);clearSelection();updateClipboard();toast(move?"Choose a destination to move":"Choose a destination to paste");});});}
    private void updateClipboard(){if(pasteButton!=null)pasteButton.setVisibility(FileClipboard.count(this)>0?View.VISIBLE:View.GONE);}
    private ArrayList<FileEntry> actionSelection(){
        if(selectionSize()==0){toast("Select files");return null;}if(selectionSize()>FileClipboard.MAX_ITEMS){toast("Choose up to 512 items at once");return null;}
        ArrayList<FileEntry> chosen=new ArrayList<>();for(FileEntry entry:adapter.items)if(isSelected(entry))chosen.add(entry);return chosen;
    }
    private void deleteSelection(){ArrayList<FileEntry> chosen=actionSelection();if(chosen!=null)activeSheet=FileDeletion.confirm(this,chosen,index.isRootEnabled(),fileActions,this::clearSelection);}
    private void renameSelection(){ArrayList<FileEntry> chosen=actionSelection();if(chosen!=null)activeSheet=FileRename.show(this,chosen,index.isRootEnabled(),fileActions,this::clearSelection);}
    private void paste(){FileClipboard.Snapshot clipboard=FileClipboard.read(this);
        if(clipboard==null){updateClipboard();toast("Clipboard is empty");return;}
        AppSheet sheet=new AppSheet(this,clipboard.move?"Move files":"Paste files");
        LinearLayout content=sheet.content;
        TextView itemsLabel=sheet.text(clipboard.entries.length()+(clipboard.move?" to move":" copied"),13,theme.muted);
        content.addView(itemsLabel);
        StringBuilder names=new StringBuilder();
        for(int i=0;i<clipboard.entries.length();i++){
            org.json.JSONObject entry=clipboard.entries.optJSONObject(i);
            if(entry==null)continue;
            if(names.length()>0)names.append('\n');
            names.append(new File(entry.optString("path")).getName());
        }
        TextView destinationLabel=sheet.text("Destination",13,theme.muted);
        destinationLabel.setPadding(0,dp(12),0,0);
        content.addView(destinationLabel);
        LinearLayout navigation=row();
        EditText destination=new EditText(this);
        destination.setSingleLine();destination.setTextSize(14);
        destination.setContentDescription("Destination folder");
        String initial=storageMap?mapScope():folderScope!=null?folderScope:Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).getAbsolutePath();
        destination.setText(initial);
        navigation.addView(destination,new LinearLayout.LayoutParams(0,dp(48),1));
        ImageButton browse=icon(R.drawable.ic_folder,"Browse destination");
        navigation.addView(browse);
        content.addView(navigation);
        TextView copiedNames=sheet.text(names.toString(),15,theme.ink);
        copiedNames.setPadding(0,dp(8),0,dp(4));
        content.addView(copiedNames);
        navigation.setFocusableInTouchMode(true);navigation.requestFocus();
        browse.setOnClickListener(v->{
            if(pathDialog!=null)return;
            String value=destination.getText().toString().trim();
            pathDialog=new FolderPathDialog(this,index,value.isEmpty()?initial:value,this::normalizeFolderPath,
                    destination::setText,()->pathDialog=null);
            pathDialog.show();
        });
        sheet.action("Clear clipboard",false,false,()->{FileClipboard.clear(this);updateClipboard();});
        sheet.action(clipboard.move?"Move here":"Paste here",true,false,()->{
            try{FileOperationService.start(this,destination.getText().toString().trim());haptic(results);clearSelection();}
            catch(Exception failure){showError(failure.getMessage());}
        });
        sheet.show();
    }
    private void onOperationChanged(FileOperationService.State operation){if(isDestroyed())return;if(operation.running){
        if(saveSearchRows!=null)ui.removeCallbacks(saveSearchRows);
        SavedSearchRows.clear(prefs);
        status.setText(operation.status);if(operation.completed>0&&operation.completed==operation.total)updateClipboard();}else{updateClipboard();if(operation.error!=null&&!operation.error.isEmpty())showError(operation.error);
        else if(isFolderView()&&!searchRunning)updateFiles();}}
    private interface FileAction {void run()throws Exception;}
    private void submitFileAction(FileAction action){try{fileActions.execute(()->{try{action.run();}catch(Exception failure){ui.post(()->{if(!isDestroyed())showError(failure.getMessage());});}});}catch(RejectedExecutionException busy){toast("File access is busy");}}
    private void open(FileEntry e){if(e.directory){navigateFolder(e.path());return;}prepareFileAction(e,false,false);}
    private void shareSelection(){
        List<FileEntry> files=actionSelection();
        if(files==null||files.isEmpty())return;
        for(FileEntry entry:files)if(entry.directory){toast("Select files to share");return;}
        if(files.size()==1){prepareFileAction(files.get(0),true,false);return;}
        boolean root=index.isRootEnabled();
        submitFileAction(()->{
            ArrayList<Uri> uris=new ArrayList<>(files.size());
            ClipData clip=null;
            String mime=null;
            for(FileEntry entry:files){
                Uri uri=RootFileProvider.issue(getApplicationContext(),entry,root);
                uris.add(uri);
                String type=getContentResolver().getType(uri);
                if(clip==null){clip=ClipData.newRawUri("Selected files",uri);mime=type==null?"*/*":type;}
                else{clip.addItem(new ClipData.Item(uri));if(!mime.equals(type))mime="*/*";}
            }
            Intent share=new Intent(Intent.ACTION_SEND_MULTIPLE).setType(mime).putParcelableArrayListExtra(Intent.EXTRA_STREAM,uris);
            share.setClipData(clip);share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            ui.post(()->{if(!isDestroyed())try{startActivity(Intent.createChooser(share,"Share"));}catch(Exception failure){toast("No compatible app");}});
        });
    }
    private void prepareFileAction(FileEntry e,boolean share,boolean external){boolean root=index.isRootEnabled();
        if(!share&&!external){if(openingPreview)return;openingPreview=true;List<FileEntry> ordered=adapter.items;
            try{fileActions.execute(()->{PreviewSession session=PreviewSession.create(ordered,e.path(),root);ui.post(()->{openingPreview=false;
                if(isDestroyed()){PreviewSession.release(session.id);return;}
                startActivityForResult(FileViewerActivity.intent(this,null,e.name,e.path(),e.size).putExtra(PreviewSession.EXTRA,session.id),21);});});}
            catch(RejectedExecutionException busy){openingPreview=false;toast("File access is busy");}return;}
        submitFileAction(()->{Uri uri=RootFileProvider.issue(getApplicationContext(),e,root);ui.post(()->{
        if(isDestroyed())return;
        String mime=getContentResolver().getType(uri);Intent intent=share?new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM,uri):new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).setClipData(ClipData.newRawUri(e.name,uri));try{startActivity(Intent.createChooser(intent,share?"Share":"Open with"));}catch(Exception failure){toast("No compatible app");}});});}
    private void openDevice(DeviceSearch.Result result){if(result==null)return;try{
        if(result.domain==DeviceSearch.APPS){Intent launch=getPackageManager().getLaunchIntentForPackage(result.packageName);if(launch!=null)startActivity(launch);else startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+result.packageName)));}
        else if(result.domain==DeviceSearch.CONTACTS){new AlertDialog.Builder(this).setTitle(result.title).setMessage(result.detail).setPositiveButton("Open contact",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,result.uri));}catch(Exception failure){toast("Contact unavailable");}}).setNegativeButton("Close",null).show();}
        else new AlertDialog.Builder(this).setTitle(result.title).setMessage(result.detail).setPositiveButton("Open messages",(d,w)->{try{startActivity(new Intent(Intent.ACTION_VIEW,result.uri));}catch(Exception failure){toast("Messages unavailable");}}).setNegativeButton("Close",null).show();
        }catch(Exception failure){showError(failure.getMessage());}}
    private void properties(FileEntry e){dialog(e.name,e.path()+"\n\n"+(e.directory?"Folder":size(e.size))+"\nModified "+DateFormat.getDateTimeInstance().format(new Date(e.modified)));}
    private void copyPath(String value){getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("File path",value));haptic(results);toast("Path copied");}
    private void stats(){FileIndex.State s=index.getState();dialog("Index",format(s.entries.size())+" files and folders\n"+(index.isRootEnabled()?"Root folders":"Shared storage")+"\n\nLast scan "+(s.lastScanMillis==0?"Never":DateFormat.getDateTimeInstance().format(new Date(s.lastScanMillis)))+
        "\nScan "+String.format(Locale.getDefault(),"%.2f s",s.scanDurationMillis/1000.0)+"\nSearch "+lastSearchMillis+" ms\n\n"+s.status+"\n\n"+format(s.watchCount)+" directory watches\n"+format(s.deniedDirectories)+" inaccessible directories");}
    private void showError(String value){message.setText(value==null||value.isEmpty()?"Action unavailable":value);message.setVisibility(View.VISIBLE);}
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_SHORT).show();}
    private void dialog(String title,String value){new AlertDialog.Builder(this).setTitle(title).setMessage(value).setPositiveButton("Close",null).show();}
    private List<String> stored(String key){ArrayList<String> values=new ArrayList<>();try{JSONArray json=new JSONArray(prefs.getString(key,"[]"));for(int i=0;i<json.length();i++)values.add(json.getString(i));}catch(Exception ignored){}return values;}
    private void addQuery(String key,String value){List<String> values=stored(key);values.remove(value);values.add(0,value);if(values.size()>50)values=values.subList(0,50);prefs.edit().putString(key,new JSONArray(values).toString()).apply();}
    private void saveHistory(){if(query==null)return;String value=query.getText().toString().trim();prefs.edit().putString("query",value).apply();if(!value.isEmpty())addQuery("history",value);refreshHistory();}
    private void refreshHistory(){query.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_dropdown_item_1line,stored("history")));}
    private void chooseQuery(String title,String key){List<String> values=stored(key);if(values.isEmpty()){toast("No saved searches");return;}new AlertDialog.Builder(this).setTitle(title).setItems(values.toArray(new String[0]),(d,n)->query.setText(values.get(n)))
        .setNeutralButton(key.equals("history")?"Clear history":"Clear saved",(d,w)->{prefs.edit().remove(key).apply();if(key.equals("history"))query.setText("");refreshHistory();}).setNegativeButton("Close",null).show();}
    private static String format(long n){return String.format(Locale.getDefault(),"%,d",n);}
    private static String resultCount(long count,boolean more){return format(count)+(more?"+ results":count==1?" result":" results");}
    private static final String[] SIZE_UNITS = {"KB","MB","GB","TB"};
    private static final ThreadLocal<java.text.DecimalFormat> SIZE_FORMAT = ThreadLocal.withInitial(() -> {
        java.text.DecimalFormat format = new java.text.DecimalFormat("0");
        format.setRoundingMode(java.math.RoundingMode.HALF_UP);
        return format;
    });
    private static String size(long bytes) {
        if (bytes < 0) return "";
        if (bytes < 1024) return bytes + " B";
        double amount = bytes / 1024.0;
        int unit = 0;
        while (amount >= 1024 && unit < SIZE_UNITS.length - 1) { amount /= 1024; unit++; }
        java.text.DecimalFormat format = SIZE_FORMAT.get();
        int digits = amount >= 10 ? 0 : 1;
        format.setMinimumFractionDigits(digits);
        format.setMaximumFractionDigits(digits);
        return format.format(amount) + " " + SIZE_UNITS[unit];
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);
        if(request==21&&result==RESULT_OK&&data!=null){PreviewSession browsing=PreviewSession.get(data.getStringExtra(PreviewSession.EXTRA));
            if(browsing!=null){selection.clear();selection.addAll(browsing.selected.keySet());allSelected=false;selectionMode=!selection.isEmpty();updateSelection();adapter.notifyDataSetChanged();PreviewSession.release(browsing.id);}
            updateClipboard();if(data.getBooleanExtra("preview.paste",false))paste();return;}
        if(request!=20||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri destination=data.getData();List<FileEntry> entries=adapter.items;
        submitFileAction(()->{try(OutputStreamWriter out=new OutputStreamWriter(getContentResolver().openOutputStream(destination),"UTF-8")){out.write("Name,Path,Type,Size,Modified\r\n");long count=0;
            for(FileEntry e:entries){if(Thread.currentThread().isInterrupted())return;out.write(csv(e.name)+","+csv(e.path())+","+(e.directory?"folder":"file")+","+e.size+","+e.modified+"\r\n");count++;}
            long exported=count;ui.post(()->{if(!isDestroyed())toast("Exported "+format(exported)+" files");});}});}
    private static String csv(String value){return "\""+value.replace("\"","\"\"")+"\"";}

    private final class CategoryAdapter extends BaseAdapter {
        public int getCount(){return FILE_MANAGER+1;}public Object getItem(int p){return p==FILE_MANAGER?"File Manager":FileTypes.LABELS[p];}public long getItemId(int p){return p;}
        public View getView(int p,View reuse,ViewGroup parent){return make(p,reuse,false);}public View getDropDownView(int p,View reuse,ViewGroup parent){return make(p,reuse,true);}
        private View make(int p,View reuse,boolean dropdown){LinearLayout r;if(reuse==null){r=row();r.setPadding(dp(10),0,dp(8),0);ImageView i=new ImageView(MainActivity.this);i.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);r.addView(i,new LinearLayout.LayoutParams(dp(22),dp(22)));TextView label=text("",14);label.setPadding(dp(10),0,dp(2),0);label.setSingleLine();label.setEllipsize(TextUtils.TruncateAt.END);r.addView(label,new LinearLayout.LayoutParams(0,dp(48),1));r.setTag(new Object[]{i,label});}else r=(LinearLayout)reuse;
            int type=p==FILE_MANAGER?2:p;
            Object[] h=(Object[])r.getTag();((ImageView)h[0]).setImageResource(FileTypes.ICONS[type]);((ImageView)h[0]).setImageTintList(ColorStateList.valueOf(UiTheme.fileColor(MainActivity.this,type)));((TextView)h[1]).setText(!dropdown&&p==0&&multiTypes!=0?typeLabel():(String)getItem(p));r.setBackgroundColor(dropdown?theme.surface:Color.TRANSPARENT);return r;}
    }
    private static final class Holder {
        LinearLayout root; FrameLayout picture; ImageView image; TextView check, name, path, size, date;
        GradientDrawable card;
        FileEntry entry;
        DeviceSearch.Result device;
        boolean marked;
    }
    private final class ResultsAdapter extends BaseAdapter implements SectionIndexer {
        List<FileEntry> items=Collections.emptyList();List<DeviceSearch.Result> devices=Collections.emptyList();
        private final DateFormat dates = new java.text.SimpleDateFormat("dd MMM yy", Locale.getDefault());
        private final Date date = new Date();
        private final android.util.LruCache<Long, String> dayLabels = new android.util.LruCache<>(128);

        private String dateLabel(long time) {
            if (time <= 0) return "";
            long day = (time + dates.getTimeZone().getOffset(time)) / 86_400_000L;
            String label = dayLabels.get(day);
            if (label == null) {
                date.setTime(time);
                label = dates.format(date);
                dayLabels.put(day, label);
            }
            return label;
        }
        FileSections sections=FileSections.EMPTY;
        public Object[] getSections(){if(devices.isEmpty())return sections.labels;
            String[] labels=new String[sections.labels.length+1];labels[0]=isDeviceCategory(category.getSelectedItemPosition())?FileTypes.LABELS[category.getSelectedItemPosition()]:BuildConfig.SMS_ENABLED?"Apps / Contacts / SMS":"Apps / Contacts";
            System.arraycopy(sections.labels,0,labels,1,sections.labels.length);return labels;}
        public int getPositionForSection(int section){return !devices.isEmpty()&&section==0?0:devices.size()+sections.positionAt(section-(devices.isEmpty()?0:1));}
        public int getSectionForPosition(int position){return position<devices.size()?0:sections.sectionAt(position-devices.size())+(devices.isEmpty()?0:1);}
        public int getCount(){return devices.size()+items.size();}public Object getItem(int p){return p<devices.size()?devices.get(p):items.get(p-devices.size());}
        public long getItemId(int p){return p;}public int getViewTypeCount(){return 3;}public int getItemViewType(int p){return Math.max(0,activeLayout-1);}
        FileEntry fileAt(int p){int file=p-devices.size();return file>=0&&file<items.size()?items.get(file):null;}
        DeviceSearch.Result deviceAt(int p){return p>=0&&p<devices.size()?devices.get(p):null;}
        public View getView(int pos, View reuse, ViewGroup parent) {
            Holder h = reuse == null ? makeHolder(activeLayout) : (Holder) reuse.getTag();
            int iconEdge = activeLayout == 3 ? gridTilePixels() : dp(40);
            if (activeLayout == 3) {
                ViewGroup.LayoutParams picture = h.picture.getLayoutParams();
                int edge = iconEdge - 2 * dp(1);
                if (picture.height != edge) { picture.height = edge; h.picture.setLayoutParams(picture); }
            }
            FileEntry e = fileAt(pos);
            DeviceSearch.Result device = deviceAt(pos);
            h.entry = e;
            h.device = device;
            boolean marked = e != null && isSelected(e);
            if (h.marked != marked) {
                h.marked = marked;
                h.check.setVisibility(marked ? View.VISIBLE : View.GONE);
                if (h.card != null) {
                    h.card.setColor(marked ? theme.selected : theme.surface);
                    h.card.setStroke(dp(1), marked ? BLUE : LINE);
                } else h.root.setBackgroundColor(marked ? theme.selected : theme.surface);
            }
            if(e!=null){h.name.setText(e.name);if (activeLayout==1) h.path.setText(e.parent);
                Long mappedSize=storageMap?mapSizes.get(e.path()):null;
                String formatted=mappedSize!=null?size(mappedSize):e.directory?"":size(e.size);
                h.size.setText(formatted);h.date.setText(dateLabel(e.modified));
                thumbnails.bind(h.image,e,iconEdge,index.isRootEnabled());h.picture.setContentDescription((activeLayout==3?"Preview ":"Select ")+e.name);
                h.root.setContentDescription(e.name+", "+e.parent+(e.directory?", folder":", "+formatted));
            }else{h.name.setText(device.title);if (activeLayout==1) h.path.setText(device.subtitle);h.size.setText("");h.date.setText(device.domain==DeviceSearch.APPS?"App":device.domain==DeviceSearch.CONTACTS?"Contact":"SMS");
                int type=device.domain==DeviceSearch.APPS?9:device.domain==DeviceSearch.CONTACTS?10:11;h.image.setTag(null);h.image.setTag(R.id.thumbnail_placeholder,null);h.image.setImageResource(FileTypes.ICONS[type]);h.image.setImageTintList(ColorStateList.valueOf(UiTheme.fileColor(MainActivity.this,type)));h.image.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                if(device.domain==DeviceSearch.APPS)thumbnails.bindApp(h.image,device.packageName);h.root.setContentDescription(device.title+", "+device.subtitle);}
            return h.root;}
        // SDK 36 annotates these API 23 Layout constants using the equal-valued API 29 LineBreaker constants.
        @android.annotation.SuppressLint("WrongConstant")
        private Holder makeHolder(int style){Holder h=new Holder();h.root=new LinearLayout(MainActivity.this);h.root.setTag(h);h.root.setGravity(Gravity.CENTER_VERTICAL);h.root.setPadding(dp(8),dp(2),dp(8),dp(2));
            h.picture=new FrameLayout(MainActivity.this);h.image=new ImageView(MainActivity.this);h.image.setId(R.id.file_icon);h.image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            int inset=style==3?0:dp(style==2?12:6);h.picture.setPadding(inset,inset,inset,inset);h.picture.addView(h.image,new FrameLayout.LayoutParams(-1,-1));
            h.check=text("✓",16);h.check.setTextColor(theme.dark?theme.surface:Color.WHITE);h.check.setGravity(Gravity.CENTER);h.check.setBackground(background(BLUE,12));h.check.setContentDescription("Toggle selection");
            h.check.setVisibility(View.GONE);
            h.picture.setOnClickListener(v -> {
                if (h.entry == null) openDevice(h.device);
                else if (!longPressConsumed) {
                    if (style == 3 && !selectionMode) open(h.entry);
                    else toggle(h.entry, v);
                }
            });
            h.picture.setOnLongClickListener(v -> {
                if (h.entry == null) return false;
                beginSelection(h.entry, v);
                return true;
            });
            h.check.setOnClickListener(v -> { if (h.entry != null) toggle(h.entry, v); });
            FrameLayout.LayoutParams checkParams=new FrameLayout.LayoutParams(dp(24),dp(24),Gravity.TOP|Gravity.END);h.picture.addView(h.check,checkParams);
            h.name=text("",14);h.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);h.path=text("",11);h.path.setTextColor(MUTED);h.path.setSingleLine();h.path.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            h.size=text("",11);h.date=text("",10.5f);h.size.setTextColor(MUTED);h.date.setTextColor(MUTED);
            if(style==3){h.root.setOrientation(LinearLayout.VERTICAL);h.root.setGravity(Gravity.TOP|Gravity.START);
                h.card=background(theme.surface,8);h.card.setStroke(dp(1),LINE);h.root.setBackground(h.card);h.root.setClipToOutline(true);
                h.root.setPadding(dp(1),dp(1),dp(1),dp(7));
                h.root.addView(h.picture,new LinearLayout.LayoutParams(-1,gridTilePixels()-2*dp(1)));
                h.name.setLines(2);h.name.setTextSize(12);h.name.setGravity(Gravity.TOP|Gravity.START);h.name.setEllipsize(TextUtils.TruncateAt.END);
                h.name.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);h.name.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
                h.name.setPadding(dp(6),dp(5),dp(6),0);int labelHeight=Math.max(dp(37),h.name.getLineHeight()*2+dp(5));
                h.root.addView(h.name,new LinearLayout.LayoutParams(-1,labelHeight));h.path.setVisibility(View.GONE);
                LinearLayout meta=row();meta.setPadding(dp(6),0,dp(6),0);h.size.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);h.size.setSingleLine();h.date.setSingleLine();h.size.setEllipsize(TextUtils.TruncateAt.END);h.date.setEllipsize(TextUtils.TruncateAt.END);
                int metaHeight=Math.max(dp(16),h.size.getLineHeight());meta.addView(h.size,new LinearLayout.LayoutParams(0,metaHeight,1));h.date.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);meta.addView(h.date,new LinearLayout.LayoutParams(0,metaHeight,1.5f));h.root.addView(meta);
            }else{h.root.setBackgroundColor(theme.surface);h.root.setOrientation(LinearLayout.HORIZONTAL);h.root.setMinimumHeight(dp(style==2?48:54));h.root.addView(h.picture,new LinearLayout.LayoutParams(dp(48),dp(48)));
                LinearLayout names=new LinearLayout(MainActivity.this);names.setOrientation(LinearLayout.VERTICAL);names.setPadding(dp(2),0,dp(6),0);h.name.setSingleLine();names.addView(h.name,new LinearLayout.LayoutParams(-1,dp(20)));if(style!=2)names.addView(h.path,new LinearLayout.LayoutParams(-1,dp(16)));
                h.root.addView(names,new LinearLayout.LayoutParams(0,-2,1));LinearLayout meta=new LinearLayout(MainActivity.this);meta.setOrientation(LinearLayout.VERTICAL);h.size.setGravity(Gravity.END);h.date.setGravity(Gravity.END);meta.addView(h.size,new LinearLayout.LayoutParams(-1,dp(15)));meta.addView(h.date,new LinearLayout.LayoutParams(-1,dp(14)));h.root.addView(meta,new LinearLayout.LayoutParams(dp(62),-2));}
            return h;}
    }
}
