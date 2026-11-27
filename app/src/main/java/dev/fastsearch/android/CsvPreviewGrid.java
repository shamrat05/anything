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
import java.util.function.IntUnaryOperator;

/** Cell coordinates stay aligned; the full reader recycles visible rows only. */
final class CsvPreviewGrid extends HorizontalScrollView {
    static View create(Context context,UiTheme theme,CsvPreview.Result model,boolean compact){
        return create(context,theme,model.format.label,model.rows,model.columns,index->index+1,compact,
                model.limited?"No complete rows in the preview.":"This file is empty.");
    }
    static View create(Context context,UiTheme theme,OfficePreview.Worksheet sheet,boolean compact){
        return create(context,theme,"worksheet",sheet.rows,sheet.columns,index->sheet.rowNumbers.get(index),compact,
                sheet.limited?"No cells in this worksheet preview.":"This worksheet is empty.");
    }
    private static View create(Context context,UiTheme theme,String label,List<List<String>> values,int columns,
                               IntUnaryOperator rowNumber,boolean compact,String emptyMessage){
        if(values.isEmpty()){
            TextView empty=new TextView(context);
            empty.setText(emptyMessage);
            empty.setTextColor(theme.muted);empty.setGravity(Gravity.CENTER);empty.setMinHeight(dp(context,72));
            return empty;
        }
        return new CsvPreviewGrid(context,theme,label,values,columns,rowNumber,compact);
    }

    private final UiTheme theme;
    private final List<List<String>> values;
    private final IntUnaryOperator rowNumber;
    private final boolean compact;
    private final int[] widths;
    private CsvPreviewGrid(Context context,UiTheme theme,String label,List<List<String>> values,int columns,
                           IntUnaryOperator rowNumber,boolean compact){
        super(context);this.theme=theme;this.values=values;this.rowNumber=rowNumber;this.compact=compact;
        setHorizontalScrollBarEnabled(true);setScrollbarFadingEnabled(false);setFillViewport(true);
        setContentDescription("Read-only "+label+" table. Scroll horizontally to see more columns.");
        int maxDigits=1;
        for(int i=0;i<values.size();i++)maxDigits=Math.max(maxDigits,String.valueOf(rowNumber.applyAsInt(i)).length());
        widths=new int[columns+1];widths[0]=dp(context,Math.max(42,20+maxDigits*9));
        int fullWidth=widths[0];
        for(int col=0;col<columns;col++){
            int longest=0;
            for(List<String> row:values)if(row.size()>col)
                longest=Math.max(longest,Math.min(26,row.get(col).length()));
            widths[col+1]=dp(context,Math.max(compact?78:92,Math.min(compact?138:208,30+longest*7)));
            fullWidth+=widths[col+1];
        }
        LinearLayout rows=new LinearLayout(context);rows.setOrientation(LinearLayout.VERTICAL);
        rows.setMinimumWidth(fullWidth);
        GridRow header=new GridRow();header.fill(-1);
        rows.addView(header,new LinearLayout.LayoutParams(fullWidth,-2));
        if(compact){
            for(int row=0;row<values.size();row++){
                GridRow display=new GridRow();display.fill(row);rows.addView(display,new LinearLayout.LayoutParams(fullWidth,-2));
            }
        }else{
            ListView body=new ListView(context);
            body.setDivider(null);
            body.setAdapter(new BaseAdapter(){
                @Override public int getCount(){return values.size();}
                @Override public Object getItem(int position){return values.get(position);}
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
            List<String> row=heading?Collections.emptyList():values.get(index);
            int number=heading?0:rowNumber.applyAsInt(index);
            for(int col=0;col<getChildCount();col++){
                TextView label=(TextView)getChildAt(col);
                String value=col==0?heading?"#":String.valueOf(number):heading?String.valueOf((char)('A'+col-1)):
                        col<=row.size()?row.get(col-1):"";
                label.setText(value);
                // Never use Linkify: formulas and URI-looking values are plain, inert cells.
                label.setContentDescription(col==0&&!heading?"Row "+number:
                        col>0&&heading?"Column "+value:null);
            }
        }
    }
    private static int dp(Context context,int value){return Math.round(value*context.getResources().getDisplayMetrics().density);}
}
