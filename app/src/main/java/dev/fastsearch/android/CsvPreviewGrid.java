package dev.fastsearch.android;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/** Cell coordinates stay aligned; the full reader recycles visible rows only. */
final class CsvPreviewGrid extends HorizontalScrollView {
    static View create(Context context,UiTheme theme,CsvPreview.Result model,boolean compact){
        if(model.rows.isEmpty()){
            TextView empty=new TextView(context);
            empty.setText(model.limited?"No complete rows in the preview.":"This file is empty.");
            empty.setTextColor(theme.muted);empty.setGravity(Gravity.CENTER);empty.setMinHeight(dp(context,72));
            return empty;
        }
        return new CsvPreviewGrid(context,theme,model,compact);
    }

    private final UiTheme theme;
    private final CsvPreview.Result model;
    private final boolean compact;
    private final int[] widths;
    CsvPreviewGrid(Context context,UiTheme theme,CsvPreview.Result model,boolean compact){
        super(context);this.theme=theme;this.model=model;this.compact=compact;
        setHorizontalScrollBarEnabled(true);setScrollbarFadingEnabled(false);setFillViewport(true);
        setContentDescription("Read-only "+model.format.label+" table. Scroll horizontally to see more columns.");
        widths=new int[model.columns+1];widths[0]=dp(context,42);
        int fullWidth=widths[0];
        for(int col=0;col<model.columns;col++){
            int longest=0;
            for(List<String> row:model.rows)if(row.size()>col)
                longest=Math.max(longest,Math.min(26,row.get(col).length()));
            widths[col+1]=dp(context,Math.max(compact?78:92,Math.min(compact?138:208,30+longest*7)));
            fullWidth+=widths[col+1];
        }
        LinearLayout rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        rows.setMinimumWidth(fullWidth);
        GridRow header=new GridRow();header.fill(-1);
        rows.addView(header,new LinearLayout.LayoutParams(fullWidth,-2));
        if(compact){
            for(int row=0;row<model.rows.size();row++){
                GridRow display=new GridRow();display.fill(row);rows.addView(display,new LinearLayout.LayoutParams(fullWidth,-2));
            }
        }else{
            ListView body=new ListView(context);
            body.setDivider(null);
            body.setAdapter(new BaseAdapter(){
                @Override public int getCount(){return model.rows.size();}
                @Override public Object getItem(int position){return model.rows.get(position);}
                @Override public long getItemId(int position){return position;}
                @Override public boolean isEnabled(int position){return false;}
                @Override public View getView(int position,View recycled,ViewGroup parent){
                    GridRow row=recycled instanceof GridRow?(GridRow)recycled:new GridRow();
                    row.fill(position);return row;
                }
            });
            rows.addView(body,new LinearLayout.LayoutParams(fullWidth,0,1));
        }
        addView(rows,new FrameLayout.LayoutParams(fullWidth,compact?-2:-1));
    }

    private final class GridRow extends LinearLayout {
        GridRow(){
            super(CsvPreviewGrid.this.getContext());setOrientation(HORIZONTAL);setGravity(Gravity.TOP);
            for(int col=0;col<widths.length;col++){
                TextView cell=new TextView(getContext());
                cell.setTextSize(compact?12:13);cell.setTextColor(col==0?theme.muted:theme.ink);
                cell.setGravity(Gravity.TOP|Gravity.START);cell.setTypeface(Typeface.MONOSPACE);
                cell.setMaxLines(compact?2:4);cell.setEllipsize(TextUtils.TruncateAt.END);
                cell.setPadding(dp(getContext(),8),dp(getContext(),8),dp(getContext(),8),dp(getContext(),8));
                if(col>0)FileViewerActivity.simpleTextLayout(cell);
                addView(cell,new LayoutParams(widths[col],-2));
            }
        }
        void fill(int index){
            final boolean heading=index<0;
            setBackgroundColor(heading?theme.selected:index%2==0?theme.surface:theme.chrome);
            List<String> row=heading?Collections.emptyList():model.rows.get(index);
            for(int col=0;col<getChildCount();col++){
                TextView label=(TextView)getChildAt(col);
                String value=col==0?heading?"#":String.valueOf(index+1):heading?String.valueOf((char)('A'+col-1)):
                        col<=row.size()?row.get(col-1):"";
                label.setText(value);
                // Never use Linkify: formulas and URI-looking values are plain, inert cells.
                label.setContentDescription(col==0&&!heading?"Row "+(index+1):
                        col>0&&heading?"Column "+value:null);
            }
        }
    }
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
