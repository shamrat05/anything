package dev.fastsearch.android;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.ContextThemeWrapper;
import android.widget.PopupMenu;

final class UiTheme {
    final boolean dark;
    final int ink, muted, surface, chrome, line, accent, selected, warning, skeleton, highlight;
    private UiTheme(boolean dark){this.dark=dark;ink=dark?0xFFF2F3F8:0xFF202020;muted=dark?0xFFACB1C1:0xFF60656B;
        surface=dark?0xFF14161D:0xFFFFFFFF;chrome=dark?0xFF20232E:0xFFF0F1FA;line=dark?0xFF333744:0xFFE4E6EF;
        accent=dark?0xFF9DAAFF:0xFF1A2FFB;selected=dark?0xFF2B3359:0xFFEEF0FF;warning=dark?0xFF262B3B:0xFFF0F1FA;
        skeleton=dark?0xFF272B37:0xFFE9EBF2;highlight=dark?0xFF343A49:0xFFF5F6FA;}
    static UiTheme resolve(Context context){int setting=context.getSharedPreferences("ui",0).getInt("theme",0);
        boolean dark=setting==2||(setting==0&&(context.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES);
        return new UiTheme(dark);}
    static UiTheme apply(Activity activity){UiTheme theme=resolve(activity);activity.setTheme(theme.dark?R.style.DarkAppTheme:R.style.AppTheme);return theme;}
    PopupMenu popup(Context context,View anchor){return new PopupMenu(new ContextThemeWrapper(context,dark?R.style.DarkPopupTheme:R.style.LightPopupTheme),anchor);}
    void bars(Activity activity){activity.getWindow().setStatusBarColor(chrome);activity.getWindow().setNavigationBarColor(surface);
        int flags=activity.getWindow().getDecorView().getSystemUiVisibility();
        flags=dark?flags&~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR:flags|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if(Build.VERSION.SDK_INT>=27)flags=dark?flags&~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR:flags|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);}
    static int fileColor(Context context,int type){int color=FileTypes.COLORS[type];if(!resolve(context).dark)return color;
        int red=(color>>16)&255,green=(color>>8)&255,blue=color&255;return 0xFF000000|((red+(255-red)/3)<<16)|((green+(255-green)/3)<<8)|(blue+(255-blue)/3);}
}
