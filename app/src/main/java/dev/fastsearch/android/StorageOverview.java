package dev.fastsearch.android;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.os.StatFs;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Normal Android mounted volumes; these are capacity totals, not a scan of a filesystem root. */
final class StorageOverview {
    static final class Volume {
        final String label, path, capacityPath;
        Volume(String label,String path,String capacityPath){
            this.label=label;this.path=path;this.capacityPath=capacityPath;
        }
    }

    private final AppSheet sheet;
    private final List<Volume> volumes;
    private final List<TextView> labels = new ArrayList<>();
    private final List<ProgressBar> bars = new ArrayList<>();

    private StorageOverview(Activity activity,List<Volume> volumes,Consumer<String> open){
        this.volumes=volumes;
        sheet=new AppSheet(activity,"Storage overview");
        TextView description=sheet.text("Space on each volume. Open a volume to browse its folders.",13,sheet.theme.muted);
        description.setPadding(0,0,0,sheet.dp(14));
        sheet.content.addView(description);
        for(Volume volume:volumes){
            LinearLayout card=new LinearLayout(activity);card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(sheet.dp(14),0,sheet.dp(14),sheet.dp(12));
            card.setBackground(new RippleDrawable(ColorStateList.valueOf(sheet.theme.line),
                    AppSheet.shape(sheet.theme.surface,sheet.dp(14)),AppSheet.shape(Color.WHITE,sheet.dp(14))));
            card.setFocusable(true);
            card.setOnClickListener(view->{sheet.dismiss();open.accept(volume.path);});
            TextView name=sheet.text(volume.label,16,sheet.theme.ink);
            name.setTypeface(activity.getResources().getFont(R.font.inter_semibold));
            android.graphics.drawable.Drawable arrow=activity.getDrawable(UiTheme.fileIcon(activity,2)).mutate();
            arrow.setBounds(0,0,sheet.dp(18),sheet.dp(18));
            name.setCompoundDrawablesRelative(null,null,arrow,null);
            card.addView(name,new LinearLayout.LayoutParams(-1,sheet.dp(48)));
            TextView value=sheet.text("Checking space…",13,sheet.theme.muted);
            value.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            card.addView(value,new LinearLayout.LayoutParams(-1,-2));
            labels.add(value);
            ProgressBar bar=new ProgressBar(activity,null,android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);bar.setIndeterminate(false);bar.setVisibility(View.INVISIBLE);
            bar.setProgressTintList(ColorStateList.valueOf(sheet.theme.accent));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(sheet.theme.line));
            bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams track=new LinearLayout.LayoutParams(-1,sheet.dp(5));track.topMargin=sheet.dp(8);
            card.addView(bar,track);bars.add(bar);
            LinearLayout.LayoutParams margin=new LinearLayout.LayoutParams(-1,-2);margin.bottomMargin=sheet.dp(8);
            sheet.content.addView(card,margin);
        }
    }

    static AppSheet open(Activity owner,List<Volume> volumes,ExecutorService worker,
                         Function<Long,String> format,Consumer<String> open,Consumer<AppSheet> dismissed){
        StorageOverview overview=new StorageOverview(owner,volumes,open);
        AppSheet sheet=overview.sheet;
        sheet.show();
        Future<?> request = overview.measure(owner,worker,format);
        sheet.setOnDismissListener(dialog->{
            if(request!=null)request.cancel(true);
            dismissed.accept(sheet);
        });
        return sheet;
    }

    private Future<?> measure(Activity owner,ExecutorService worker,Function<Long,String> format){
        try{return worker.submit(()->{
            for(int i=0;i<volumes.size()&&!Thread.currentThread().isInterrupted();i++){
                String description="Space information unavailable";int fill=-1;
                try{
                    StatFs stats=new StatFs(volumes.get(i).capacityPath);
                    long total=stats.getTotalBytes();
                    if(total>0){
                        long free=Math.max(0,Math.min(total,stats.getFreeBytes()));
                        long available=Math.max(0,Math.min(free,stats.getAvailableBytes()));
                        long used=total-free;
                        fill=(int)Math.round(1000.0*used/total);
                        description=format.apply(used)+" used  ·  "+format.apply(available)+" available";
                        if(free>available)description+="\n"+format.apply(free-available)+" reserved by the system";
                    }
                }catch(RuntimeException unavailable){/* Mounted volumes may be removed before StatFs runs. */}
                String value=description;int progress=fill, position=i;
                owner.runOnUiThread(()->{if(sheet.isShowing()&&!owner.isDestroyed())update(position,value,progress);});
            }
        });}catch(RejectedExecutionException busy){
            for(int i=0;i<volumes.size();i++)update(i,"Space information unavailable",-1);
            return null;
        }
    }

    private void update(int position,String description,int fill){
        labels.get(position).setText(description);
        bars.get(position).setVisibility(fill<0?View.INVISIBLE:View.VISIBLE);
        if(fill>=0)bars.get(position).setProgress(fill);
    }
}
