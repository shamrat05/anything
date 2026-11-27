package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;
import dev.fastsearch.core.FileEntry;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/** Scrollable, ordered treemap with recycled horizontal rows. */
final class StorageMapView extends ListView {
    private static final int MIN_TILE_WIDTH_DP = 60;
    private static final int TILE_MARK_STRIP_DP = 16;
    private static final int MAX_ROW_TILES = 8;
    private static final double LABEL_FLOOR_BYTES = 30 * (1L << 20);
    private static final double LARGE_ITEM_WEIGHT = 12;
    private static final double MAX_ITEM_WEIGHT = 15;
    private static final double TARGET_ASPECT = 1.5;
    static final String[] SIZE_LABELS = {"Smallest", "Small", "Medium", "Large", "Largest"};
    private static final int[] LIGHT_COLORS = {0xFFBBD9FF, 0xFFC9D2F8, 0xFFE0CCF3, 0xFFF6C1C6, 0xFFFFAEA4};
    private static final int[] DARK_COLORS = {0xFF235B99, 0xFF414D83, 0xFF634378, 0xFF8C414F, 0xFFA93733};

    Function<StorageMapData.Tile,String> labels = tile -> tile.entry.name;
    private final EdgeFastScroll edge = EdgeFastScroll.rows(this, p -> labels.apply((StorageMapData.Tile)getItemAtPosition(p)));
    private final UiTheme theme;
    private AppOwnerBadges appOwnerBadges;
    private final Function<Long, String> formatSize;
    private final ScaleGestureDetector pinch;
    private final int minTileWidth, minBandHeight, normalStroke, selectedStroke;
    private final Typeface tileTypeface;
    private List<StorageMapData.Tile> tiles = java.util.Collections.emptyList();
    private Predicate<FileEntry> selected = entry -> false;
    private Consumer<FileEntry> open, preview;
    private java.util.function.BiPredicate<List<FileEntry>, FileEntry> openChild;
    private int highlightedSizeBand=StorageMapData.UNKNOWN;
    private StorageMapData.Scale scopeScale = new StorageMapData.Scale(0, 0);
    private java.util.function.BiPredicate<View,FileEntry> hold;
    private String folder;
    private int mapWidth, viewportHeight, bandCount;
    private int[] bandStarts = new int[1], bandHeights = new int[1];
    private double[] weights = new double[0];
    private float zoom = 1, pendingZoom = 1, focusX, focusY, focusFraction, dragX, dragY;
    private int focusIndex;
    private boolean touching, scaling, inspecting, touchCancelled;
    private FileEntry inspected;
    private final Runnable scrollDuringInspection = new Runnable() {
        @Override public void run() {
            int amount = edgeScroll(dragY);
            if (!inspecting || amount == 0 || !canScrollVertically(Integer.signum(amount))) return;
            scrollListBy(amount);
            inspectAt(dragX, dragY);
            postOnAnimation(this);
        }
    };
    private final BaseAdapter bands = new BaseAdapter() {
        @Override public int getCount() { return bandCount; }
        @Override public Object getItem(int position) { return tiles.get(bandStarts[position]); }
        @Override public long getItemId(int position) { return position; }
        // Native tile views own click, focus and long-click.
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Band band = recycled == null ? new Band() : (Band) recycled;
            band.bind(bandStarts[position], bandStarts[position + 1], bandHeight(position));
            return band;
        }
    };

    StorageMapView(Context context, UiTheme theme, Function<Long, String> formatSize) {
        super(context);
        this.theme = theme;
        this.formatSize = formatSize;
        float labelSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, getResources().getDisplayMetrics());
        minTileWidth = Math.max(dp(MIN_TILE_WIDTH_DP), Math.round(labelSize * 4.5f) + dp(18));
        minBandHeight = Math.max(dp(44), Math.round(labelSize * 2.5f) + dp(9));
        normalStroke = Math.max(1, dp(1));
        selectedStroke = dp(2);
        tileTypeface = getResources().getFont(R.font.inter_medium);
        setDivider(null);
        setSelector(android.R.color.transparent);
        setPadding(dp(4), dp(4), dp(4), dp(44));
        setClipToPadding(false);
        setItemsCanFocus(true);
        setAdapter(bands);
        setFastScrollEnabled(false);
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                if (!scaling || tiles.isEmpty()) return false;
                focusX = detector.getFocusX();
                focusY = detector.getFocusY();
                Band band = bandAt(focusX, focusY);
                int tile = tileAt(focusX, focusY);
                focusIndex = tile >= 0 ? tile : band == null ? firstTile() : band.start;
                focusFraction = band == null ? 0 : (focusY - band.getTop()) / Math.max(1, band.getHeight());
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector detector) {
                pendingZoom = Math.max(1, Math.min(4, pendingZoom * detector.getScaleFactor()));
                invalidate();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        pinch.setStylusScaleEnabled(false);
    }

    void setAppOwnerBadges(AppOwnerBadges badges){this.appOwnerBadges=badges;}

    @Override protected void onScrollChanged(int l,int t,int oldl,int oldt){
        super.onScrollChanged(l,t,oldl,oldt);
        if(appOwnerBadges!=null)appOwnerBadges.viewportChanged();
    }

    void clear() {
        if (tiles.isEmpty()) return;
        finishInspection();
        tiles = java.util.Collections.emptyList();
        folder = null;
        zoom = 1;
        rebuildBands();
        bands.notifyDataSetChanged();
    }

    void show(String folder, List<StorageMapData.Tile> tiles, StorageMapData.Scale scale,
            Consumer<FileEntry> open, java.util.function.BiPredicate<List<FileEntry>, FileEntry> openChild,
            java.util.function.BiPredicate<View,FileEntry> hold, Consumer<FileEntry> preview) {
        boolean sameFolder = Objects.equals(this.folder, folder);
        this.open = open;
        this.openChild = openChild;
        this.hold = hold;
        this.preview = preview;
        if (sameFolder && this.tiles == tiles && scopeScale == scale) return;
        scopeScale = scale;
        int oldIndex = firstTile(), oldTop = getChildCount() == 0 ? getPaddingTop() : getChildAt(0).getTop();
        String anchor = sameFolder && oldIndex < this.tiles.size() ? this.tiles.get(oldIndex).entry.path() : null;
        if (!sameFolder) { finishInspection(); zoom = 1; }
        this.folder = folder;
        this.tiles = tiles;
        rebuildWeights();
        rebuildBands();
        bands.notifyDataSetChanged();
        if (sameFolder && bandCount > 0) {
            int index = Math.min(oldIndex, tiles.size() - 1);
            if (anchor != null) for (int i = 0; i < tiles.size(); i++) {
                if (anchor.equals(tiles.get(i).entry.path())) { index = i; break; }
            }
            setSelectionFromTop(bandFor(index), oldTop - getPaddingTop());
        } else {
            setSelectionFromTop(0, 0);
        }
    }

    void updateSelection(Predicate<FileEntry> selected) {
        this.selected = selected;
        updateVisibleTiles();
    }

    void highlightSizeBand(int band) {
        if(highlightedSizeBand==band)return;
        highlightedSizeBand=band;
        updateVisibleTiles();
    }

    private void updateVisibleTiles() {
        for (int i = 0; i < getChildCount(); i++) ((Band) getChildAt(i)).updateSelection();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if(edge.handle(event, super::dispatchTouchEvent)) return true;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            touching = true;
            scaling = touchCancelled = false;
            pendingZoom = zoom;
            focusIndex = firstTile();
            focusY = event.getY();
            focusFraction = 0;
        } else if (action == MotionEvent.ACTION_POINTER_DOWN && !scaling) {
            scaling = true;
            finishInspection();
        }
        pinch.onTouchEvent(event);

        if (scaling || inspecting) {
            if (!touchCancelled) {
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                touchCancelled = true;
            }
            if (inspecting && action == MotionEvent.ACTION_MOVE) {
                dragX = event.getX();
                dragY = event.getY();
                inspectAt(dragX, dragY);
                removeCallbacks(scrollDuringInspection);
                if (edgeScroll(dragY) != 0) postOnAnimation(scrollDuringInspection);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (scaling && action == MotionEvent.ACTION_UP) applyZoom();
                finishInspection();
                touching = scaling = false;
                invalidate();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false;
        return super.dispatchTouchEvent(event);
    }

    private void applyZoom() {
        if (tiles.isEmpty() || Math.abs(pendingZoom - zoom) < .12f) return;
        zoom = pendingZoom;
        rebuildBands();
        bands.notifyDataSetChanged();
        int band = bandFor(Math.min(focusIndex, tiles.size() - 1));
        int top = Math.round(focusY - focusFraction * bandHeight(band));
        setSelectionFromTop(band, top - getPaddingTop());
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (!scaling || zoom == pendingZoom) {
            super.dispatchDraw(canvas);
            return;
        }
        int saved = canvas.save();
        float previewScale = Math.max(.8f, Math.min(1.35f, pendingZoom / zoom));
        canvas.scale(previewScale, previewScale, focusX, focusY);
        super.dispatchDraw(canvas);
        canvas.restoreToCount(saved);
    }

    private void inspectAt(float x, float y) {
        int index = tileAt(x, y);
        if (index < 0) return;
        FileEntry entry = tiles.get(index).entry;
        if (inspected != null && inspected.path().equals(entry.path())) return;
        inspected = entry;
        preview.accept(entry);
        updateVisibleTiles();
    }

    private void finishInspection() {
        removeCallbacks(scrollDuringInspection);
        if (!inspecting) return;
        inspecting = false;
        inspected = null;
        preview.accept(null);
        updateVisibleTiles();
    }

    @Override protected void onDetachedFromWindow() {
        finishInspection();
        touching = scaling = false;
        edge.clear();
        super.onDetachedFromWindow();
    }

    private int edgeScroll(float y) {
        int edge = dp(40);
        return y < edge ? -dp(10) : y > getHeight() - edge ? dp(10) : 0;
    }

    private final int[] fileScreen=new int[2];
    private final android.graphics.Rect fileVisible=new android.graphics.Rect();

    List<StorageMapData.Tile> previewTiles(){return tiles;}

    FileHoldActions.Target fileTargetAt(float rawX,float rawY,
            java.util.function.BiFunction<View,FileEntry,FileHoldActions.Target> factory){
        if(!FileHoldActions.inside(this,rawX,rawY,fileScreen,fileVisible))return null;
        Band band=bandAt(rawX-fileScreen[0],rawY-fileScreen[1]);
        if(band==null)return null;
        for(int i=0;i<band.getChildCount();i++){
            TileView tile=(TileView)band.getChildAt(i);
            if(tile.tile!=null&&FileHoldActions.inside(tile,rawX,rawY,fileScreen,fileVisible))
                return factory.apply(tile,tile.tile.entry);
        }
        return null;
    }

    private Band bandAt(float x, float y) {
        return (Band) getChildAt(pointToPosition((int) x, (int) y) - getFirstVisiblePosition());
    }

    private int tileAt(float x, float y) {
        Band band = bandAt(x, y);
        if (band == null) return -1;
        x -= band.getLeft();
        y -= band.getTop();
        for (int i = 0; i < band.getChildCount(); i++) {
            View child = band.getChildAt(i);
            if (x >= child.getLeft() && x < child.getRight() && y >= child.getTop() && y < child.getBottom())
                return band.start + i < tiles.size() ? band.start + i : -1;
        }
        return -1;
    }

    private int firstTile() {
        return getChildCount() == 0 ? 0 : ((Band) getChildAt(0)).start;
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec) - getPaddingLeft() - getPaddingRight();
        int height = MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom();
        if (width != mapWidth || height != viewportHeight) {
            int first = firstTile();
            mapWidth = width;
            viewportHeight = height;
            rebuildBands();
            bands.notifyDataSetChanged();
            if (bandCount > 0) setSelectionFromTop(bandFor(first), 0);
        }
        super.onMeasure(widthSpec, heightSpec);
    }

    private void rebuildWeights() {
        if (weights.length < tiles.size()) weights = new double[tiles.size()];
        for (int i = 0; i < tiles.size(); i++) {
            double ratio = tiles.get(i).bytes / LABEL_FLOOR_BYTES;
            double weight = ratio <= 1 ? .8 + .2 * ratio : Math.pow(ratio, .7);
            // Preserve the size ranking at the high end while limiting the influence of outliers.
            if (weight > LARGE_ITEM_WEIGHT) {
                double range = MAX_ITEM_WEIGHT - LARGE_ITEM_WEIGHT;
                double excess = weight - LARGE_ITEM_WEIGHT;
                weight = LARGE_ITEM_WEIGHT + range * excess / (range + excess);
            }
            weights[i] = weight;
        }
    }

    private void rebuildBands() {
        bandCount = 0;
        if (mapWidth <= 0 || tiles.isEmpty()) return;
        int count = tiles.size();
        if (bandStarts.length <= count) {
            bandStarts = new int[count + 1];
            bandHeights = new int[count];
        }
        int rowLimit = Math.max(1, Math.min(MAX_ROW_TILES, mapWidth / minTileWidth));
        int maxHeight = Math.max(minBandHeight, Math.round(viewportHeight * .46f));
        double scale = Math.min(minBandHeight * .5, maxHeight / MAX_ITEM_WEIGHT) * zoom;
        int minHeight = Math.round(minBandHeight * zoom);
        int folderMinHeight = Math.round(Math.min(maxHeight, Math.max(minBandHeight, dp(105))) * zoom);
        for (int start = 0; start < count;) {
            double weight = 0, smallest = Double.POSITIVE_INFINITY, largest = 0;
            double bestScore = Double.POSITIVE_INFINITY;
            int rowEnd = start + 1, rowHeight = minHeight;
            boolean hasSample = false;
            for (int end = start; end < Math.min(start + rowLimit, count); end++) {
                weight += weights[end];
                smallest = Math.min(smallest, weights[end]);
                largest = Math.max(largest, weights[end]);
                hasSample |= !tiles.get(end).children.isEmpty();
                int height = Math.max(hasSample ? folderMinHeight : minHeight, (int) Math.round(weight * scale));
                double narrowest = mapWidth * smallest / weight;
                if (end > start && (narrowest < minTileWidth || height > maxHeight * zoom)) break;
                double widest = mapWidth * largest / weight;
                double score = Math.max(widest / (height * TARGET_ASPECT), height * TARGET_ASPECT / narrowest);
                if (end == count - 2) score += .25; // Prefer to avoid a lone tile on the last row.
                if (score < bestScore) {
                    bestScore = score;
                    rowEnd = end + 1;
                    rowHeight = height;
                }
            }
            bandStarts[bandCount] = start;
            bandHeights[bandCount++] = rowHeight;
            start = rowEnd;
        }
        bandStarts[bandCount] = count;
    }

    private int bandFor(int index) {
        int position = Arrays.binarySearch(bandStarts, 0, bandCount, index);
        return position >= 0 ? position : -position - 2;
    }

    private int bandHeight(int position) {
        return bandHeights[position];
    }

    static String typeLabel(FileEntry entry) {
        if (entry.directory) return "Folder";
        String extension = FileSubtypes.extension(entry);
        return extension.isEmpty() ? "File" : extension.toUpperCase(Locale.ROOT) + " file";
    }

    int fillColor(int sizeBand) {
        return sizeBand < 0 ? (theme.dark ? 0xFF323743 : 0xFFE2E6ED)
                : (theme.dark ? DARK_COLORS : LIGHT_COLORS)[sizeBand];
    }

    private final class Band extends ViewGroup {
        private int start;

        Band() {
            super(StorageMapView.this.getContext());
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void bind(int start, int end, int height) {
            this.start = start;
            int count = end - start;
            while (getChildCount() > count) removeViewAt(getChildCount() - 1);
            double rowWeight = 0;
            for (int i = start; i < end; i++) rowWeight += weights[i];
            double consumed = 0;
            int left = 0;
            for (int i = 0; i < count; i++) {
                if (i == getChildCount()) addView(new TileView());
                consumed += weights[start + i];
                int right = i == count - 1 ? mapWidth : (int) Math.round(mapWidth * consumed / rowWeight);
                ((TileView) getChildAt(i)).bind(tiles.get(start + i), left, 0, right - left, height);
                left = right;
            }
            if (getLayoutParams() == null || getLayoutParams().height != height)
                setLayoutParams(new android.widget.AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, height));
        }

        void updateSelection() {
            for (int i = 0; i < getChildCount(); i++) ((TileView) getChildAt(i)).updateSelection();
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            for (int i = 0; i < getChildCount(); i++) {
                TileView tile = (TileView) getChildAt(i);
                tile.measure(MeasureSpec.makeMeasureSpec(tile.blockWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(tile.blockHeight, MeasureSpec.EXACTLY));
            }
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            for (int i = 0; i < getChildCount(); i++) {
                TileView tile = (TileView) getChildAt(i);
                tile.layout(tile.blockLeft, tile.blockTop, tile.blockLeft + tile.blockWidth, tile.blockTop + tile.blockHeight);
            }
        }
    }

    private final class TileView extends TextView {
        private final GradientDrawable block = new GradientDrawable();
        private final android.graphics.drawable.Drawable pickedMark = FileSelectionMark.create(getContext(),theme);
        private android.graphics.drawable.Drawable fileMark, appOwnerMark;
        private String ownerDescription;
        private boolean showTileMarks;
        private final Paint nestedFill = new Paint();
        private final android.text.TextPaint nestedText = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final List<Nested> regions = new ArrayList<>();
        private FileEntry pendingChild;
        private float downX, downY;
        private StorageMapData.Scale drawnScale;
        private StorageMapData.Tile tile;
        private String name, size;
        private int captionWidth = -1, blockLeft, blockTop, blockWidth, blockHeight;

        TileView() {
            super(StorageMapView.this.getContext(), null, 0);
            setGravity(Gravity.TOP | Gravity.START);
            setPadding(dp(7), dp(5), dp(7), 0);
            setTextSize(12);
            setIncludeFontPadding(false);
            setMaxLines(2);
            setEllipsize(TextUtils.TruncateAt.END);
            setTypeface(tileTypeface);
            setTextColor(theme.ink);
            nestedText.setColor(theme.ink);
            nestedText.setTypeface(tileTypeface);
            nestedText.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10,
                    getResources().getDisplayMetrics()));
            setFocusable(true);
            setBackground(block);
            setOnClickListener(v -> {
                FileEntry child = pendingChild;
                if (child == null || !openChild.test(tile.children, child)) open.accept(tile.entry);
            });
            setOnLongClickListener(v -> {
                FileEntry entry=tile.entry;
                if (hold.test(v,entry) && touching) { inspecting = true; inspected = entry; }
                return true;
            });
        }

        void bind(StorageMapData.Tile tile, int left, int top, int width, int height) {
            boolean geometryChanged = this.tile != tile || width != blockWidth || height != blockHeight
                    || drawnScale != scopeScale;
            blockLeft = left;
            blockTop = top;
            blockWidth = width;
            blockHeight = height;
            if (this.tile != tile) {
                pendingChild = null;
                captionWidth = -1;
                name = clean(tile.entry.name) + (tile.entry.directory ? "  ›" : "");
                size = tile.bytes < 0 ? "Unknown size" : formatSize.apply(tile.bytes);
                fileMark = null;
                String detail = tile.entry.directory && tile.fileCount > 0
                        ? ", " + tile.fileCount + (tile.indexedChildren ? " indexed" : "")
                                + (tile.fileCount == 1 ? " file inside" : " files inside") : "";
                ownerDescription=tile.entry.name + ", " + typeLabel(tile.entry) + ", " + size + detail;
                setContentDescription(ownerDescription);
            }
            this.tile = tile;
            if (geometryChanged) {
                drawnScale = scopeScale;
                block.setColor(fillColor(tile.entry.directory ? StorageMapData.UNKNOWN : scopeScale.band(tile.bytes)));
                regions.clear();
                showTileMarks = width >= dp(72) && height >= captionHeight() + dp(TILE_MARK_STRIP_DP);
                if (showTileMarks && fileMark == null)
                    fileMark = getContext().getDrawable(UiTheme.fileIcon(getContext(),tile.entry)).mutate();
                int leftInset = dp(5), topInset = folderHeaderHeight();
                if (width >= dp(58) && height >= topInset + dp(24)) {
                    StorageMapData.regions(tile, width - 2 * leftInset, height - topInset - dp(5),
                            (file, l, t, r, b) -> {
                                if(r-l>=2 && b-t>=2) regions.add(new Nested(file,leftInset+l,topInset+t,leftInset+r,topInset+b));
                            });
                }
            }
            int available = Math.max(0, width - getCompoundPaddingLeft() - getCompoundPaddingRight());
            if (available != captionWidth) {
                captionWidth = available;
                setText(TextUtils.ellipsize(name, getPaint(), available, TextUtils.TruncateAt.END) + "\n"
                        + TextUtils.ellipsize(size, getPaint(), available, TextUtils.TruncateAt.END));
            }
            if(appOwnerBadges!=null)appOwnerBadges.bind(this,showTileMarks?tile.entry:null,value->{
                appOwnerMark=value==null?null:new AppOwnerBadges.Artwork(theme,value.bitmap);
                setContentDescription(AppOwnerBadges.describe(ownerDescription,value));
                invalidate();
            });
            updateSelection();
        }

        private int captionHeight() { return Math.max(dp(40), getLineHeight() * 2 + dp(9)); }
        private int folderHeaderHeight() { return captionHeight() + (showTileMarks ? dp(TILE_MARK_STRIP_DP) : 0); }

        @Override public boolean performClick() {
            try { return super.performClick(); }
            finally { pendingChild = null; }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX=event.getX(); downY=event.getY(); pendingChild=null;
                    break;
                case MotionEvent.ACTION_CANCEL:
                    pendingChild=null;
                    break;
                case MotionEvent.ACTION_UP:
                    pendingChild=null;
                    for (Nested region : regions) {
                        if (region.file != null && region.bounds.width() >= dp(48)
                                && region.bounds.height() >= dp(48) && region.bounds.contains(downX,downY)
                                && region.bounds.contains(event.getX(),event.getY())) {
                            pendingChild=region.file;break;
                        }
                    }
                    break;
                default:
                    break;
            }
            boolean handled=super.onTouchEvent(event);
            // TextView may post the click; clean up after its posted click if the gesture was a hold.
            if(event.getActionMasked()==MotionEvent.ACTION_UP)post(()->pendingChild=null);
            return handled;
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            // The lower strip belongs to the tile header alone; neither mark covers a caption or nested child.
            int iconLeft = dp(7), iconTop = captionHeight() - dp(2);
            if (showTileMarks && fileMark != null) {
                fileMark.setBounds(iconLeft,iconTop,iconLeft+dp(14),iconTop+dp(14));
                fileMark.draw(canvas);
                if (isSelected()) {
                    int badgeLeft = iconLeft+dp(15), badgeTop = iconTop-dp(2);
                    pickedMark.setBounds(badgeLeft,badgeTop,badgeLeft+dp(10),badgeTop+dp(10));
                    pickedMark.draw(canvas);
                }
                if(appOwnerMark!=null){
                    int ownerLeft=iconLeft+dp(15),ownerTop=iconTop+dp(8);
                    appOwnerMark.setBounds(ownerLeft,ownerTop,ownerLeft+dp(10),ownerTop+dp(10));
                    appOwnerMark.draw(canvas);
                }
            }
            for (Nested region : regions) {
                RectF b = region.bounds;
                nestedFill.setColor(region.color);
                canvas.drawRect(b.left + 1,b.top + 1,b.right - 1,b.bottom - 1,nestedFill);
                if(region.file!=null&&highlightedSizeBand>=0&&scopeScale.band(region.file.size)==highlightedSizeBand
                        &&b.width()>dp(5)&&b.height()>dp(5)){
                    nestedFill.setColor(theme.ink);
                    nestedFill.setStyle(Paint.Style.STROKE);
                    nestedFill.setStrokeWidth(dp(2));
                    canvas.drawRect(b.left+dp(2),b.top+dp(2),b.right-dp(2),b.bottom-dp(2),nestedFill);
                    nestedFill.setStyle(Paint.Style.FILL);
                }
                if(region.name == null)continue;
                int save = canvas.save();
                canvas.clipRect(b.left + 2,b.top + 2,b.right - 2,b.bottom - 2);
                canvas.drawText(region.name,region.textX,region.firstBaseline,nestedText);
                if(region.detail != null)canvas.drawText(region.detail,region.textX,region.secondBaseline,nestedText);
                canvas.restoreToCount(save);
            }
        }

        private final class Nested {
            final FileEntry file;
            final RectF bounds;
            final String name, detail;
            final int color;
            final float textX, firstBaseline, secondBaseline;
            Nested(FileEntry file,float l,float t,float r,float b) {
                this.file = file;
                bounds = new RectF(l,t,r,b);
                color = file == null ? theme.surface : fillColor(scopeScale.band(file.size));
                float available = bounds.width() - dp(10), line = nestedText.getFontSpacing();
                textX = l + dp(5);
                firstBaseline = t + dp(3) - nestedText.ascent();
                secondBaseline = firstBaseline + line;
                boolean readable = available >= dp(36) && bounds.height() >= line + dp(7);
                name = readable ? nestedCaption(file == null ? "Other files" : clean(file.name), available) : null;
                detail = readable && file != null && bounds.height() >= 2 * line + dp(8)
                        ? nestedCaption(formatSize.apply(file.size) + " · " + typeLabel(file), available) : null;
            }
        }

        private String nestedCaption(String value, float width) {
            return TextUtils.ellipsize(value,nestedText,width,TextUtils.TruncateAt.END).toString();
        }

        void updateSelection() {
            boolean marked = selected.test(tile.entry);
            boolean hovered = inspected != null && inspected.path().equals(tile.entry.path());
            boolean highlighted=highlightedSizeBand>=0&&!tile.entry.directory&&scopeScale.band(tile.bytes)==highlightedSizeBand;
            block.setStroke(marked||hovered||highlighted?selectedStroke:normalStroke,
                    marked||hovered?theme.accent:highlighted?theme.ink:theme.surface);
            setSelected(marked);
            invalidate();
        }
    }

    private static String clean(String name) {
        return name.replace('\n',' ').replace('\r',' ').replace('\u2028',' ').replace('\u2029',' ');
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
