package dev.fastsearch.android;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;

/** Two launcher entry points. The launcher never receives a query, file name or storage snapshot. */
public abstract class AnythingWidgetProvider extends AppWidgetProvider {
    static final int NONE=0, SEARCH=1, FILES=2;
    private static final String ACTION_SEARCH="dev.fastsearch.android.widget.SEARCH";
    private static final String ACTION_FILES="dev.fastsearch.android.widget.FILES";

    public static final class Search extends AnythingWidgetProvider {
        public Search(){super(SEARCH,R.layout.widget_search);}
    }
    public static final class Files extends AnythingWidgetProvider {
        public Files(){super(FILES,R.layout.widget_files);}
    }

    private final int destination, layout;

    private AnythingWidgetProvider(int destination,int layout){
        this.destination=destination;
        this.layout=layout;
    }

    @Override public final void onUpdate(Context context,AppWidgetManager manager,int[] ids){
        RemoteViews tile=new RemoteViews(context.getPackageName(),layout);
        tile.setOnClickPendingIntent(android.R.id.background,shortcut(context,destination));
        manager.updateAppWidget(ids,tile);
    }

    static PendingIntent shortcut(Context context,int destination){
        if(destination!=SEARCH&&destination!=FILES)throw new IllegalArgumentException("Invalid widget destination");
        Intent route=new Intent(context,MainActivity.class)
                .setAction(destination==SEARCH?ACTION_SEARCH:ACTION_FILES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context,destination,route,PendingIntent.FLAG_IMMUTABLE);
    }

    /** The exported activity may open these two existing UI screens, but never consumes external file data. */
    static int destination(Intent intent){
        if(intent==null||intent.getData()!=null||intent.getClipData()!=null)return NONE;
        if(ACTION_SEARCH.equals(intent.getAction()))return SEARCH;
        if(ACTION_FILES.equals(intent.getAction()))return FILES;
        return NONE;
    }
}
