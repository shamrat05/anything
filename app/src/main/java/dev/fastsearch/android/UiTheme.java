package dev.fastsearch.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.ContextThemeWrapper;
import android.widget.PopupMenu;

final class UiTheme {
    static final int DEFAULT_MODE=2;
    final boolean dark;
    final int ink, muted, surface, chrome, line, accent, selected, warning, skeleton, highlight;
    static final int AMOLED_BLACK=0xFF000000;
    static final int LIGHT_SURFACE=0xFFF2F6FD, LIGHT_CHROME=0xFFE6EEFB;
    private UiTheme(boolean dark,boolean ambient,int tone){this.dark=dark;ink=dark?0xFFF0F2F5:0xFF14243B;muted=dark?0xFFB3B6BD:0xFF4C607C;
        // Dark canvas and chrome are OLED-off black; wallpaper tint remains a Light-theme option.
        surface=dark?AMOLED_BLACK:ambient?AmbientPalette.surface(false,tone):LIGHT_SURFACE;
        chrome=dark?AMOLED_BLACK:ambient?AmbientPalette.chrome(false,tone):LIGHT_CHROME;line=dark?0xFF343840:0xFFADC2E3;
        accent=dark?0xFF64A9F6:0xFF155CCE;selected=dark?0xFF0D1A27:0xFFDBE9FE;warning=dark?0xFF262B3B:0xFFE9EDFB;
        skeleton=dark?0xFF272B37:0xFFDDE7F6;highlight=dark?0xFF343A49:0xFFE7EFFC;}
    int selectionLine(){return dark?0xFF567DA5:accent;}
    android.graphics.drawable.GradientDrawable primaryAction(float radius) {
        android.graphics.drawable.GradientDrawable fill=dark
                ? AppSheet.shape(accent,radius)
                : new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF344DC1,0xFF155CCE,0xFF14649E});
        fill.setCornerRadius(radius);
        return fill;
    }
    private static boolean dark(Context context,int setting){
        return setting==2||(setting==0&&(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
    }
    private static boolean dark(Context context){
        return dark(context,context.getSharedPreferences("ui",0).getInt("theme",DEFAULT_MODE));
    }
    static UiTheme resolve(Context context){
        android.content.SharedPreferences prefs=context.getSharedPreferences("ui",0);
        boolean ambient=prefs.getBoolean(AmbientPalette.PREFERENCE,true);
        return new UiTheme(dark(context,prefs.getInt("theme",DEFAULT_MODE)),ambient,
                ambient?AmbientPalette.systemTone(context):AmbientPalette.COOL_BLUE);
    }
    static UiTheme apply(Activity activity){UiTheme theme=resolve(activity);activity.setTheme(theme.dark?R.style.DarkAppTheme:R.style.AppTheme);return theme;}
    PopupMenu popup(Context context,View anchor){return new PopupMenu(new ContextThemeWrapper(context,dark?R.style.DarkPopupTheme:R.style.LightPopupTheme),anchor);}
    void bars(Activity activity){activity.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface));activity.getWindow().setStatusBarColor(chrome);activity.getWindow().setNavigationBarColor(surface);
        int flags=activity.getWindow().getDecorView().getSystemUiVisibility();
        flags=dark?flags&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:flags|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=27)flags=dark?flags&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:flags|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);}
    static int fileIcon(Context context,int type){return fileIcon(context,type,"");}
    static int fileIcon(Context context,int type,String extension){
        return type!=2?FileTypes.icon(type,extension):dark(context)?R.drawable.ic_folder_brand_dark:R.drawable.ic_folder_brand;
    }
    static int fileIcon(Context context,dev.fastsearch.core.FileEntry entry){
        int type=FileTypes.category(entry);
        return fileIcon(context,type,(type==4||type==5)?FileSubtypes.extension(entry):"");
    }
    static android.content.res.ColorStateList fileTint(Context context,int type){
        // File vectors have authored colors. Only the universal filter glyph is tinted.
        return type==0?android.content.res.ColorStateList.valueOf(fileColor(context,type)):null;
    }
    static int fileColor(Context context,int type){int color=FileTypes.COLORS[type];if(!dark(context))return color;
        int red=(color>>16)&255,green=(color>>8)&255,blue=color&255;return 0xFF000000|((red+(255-red)/3)<<16)|((green+(255-green)/3)<<8)|(blue+(255-blue)/3);}
}
