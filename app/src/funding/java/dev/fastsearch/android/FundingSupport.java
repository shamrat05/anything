package dev.fastsearch.android;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.drawable.PictureDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;
import java.io.IOException;

final class FundingSupport {
    private static final String PATREON = "https://www.patreon.com/cw/Shamrat05/membership";
    private static final String SUPPORT = "https://callcraftstudio.com/anything/support/#usdt-tron";
    private static final String ADDRESS = "TCURP3QhbasypbXJkb1ggTzQBYruYsuibv";

    static Runnable action(Activity activity) { return () -> show(activity); }

    private static void show(Activity activity) {
        AppSheet sheet = new AppSheet(activity, "Support development", true);
        ScrollView scroll = new ScrollView(activity);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(sheet.dp(12), sheet.dp(8), sheet.dp(12), sheet.dp(20));
        scroll.addView(content);
        sheet.content.addView(scroll, new LinearLayout.LayoutParams(-1, -1));

        text(sheet, content, "Find the file you half remember", 21);
        text(sheet, content, "A PDF lands in Downloads. Weeks later, you type invoice and it’s there. "
                + "If Anything saves you time, you can help fund bug fixes and testing on real Android phones. "
                + "Every app feature stays free.", 16);
        text(sheet, content, "Monthly on Patreon", 18);
        ImageView patreon = graphic(activity, "support-on-patreon.svg", "Support on Patreon");
        if (patreon == null) {
            button(sheet, content, "Support on Patreon", () -> open(activity, PATREON));
        } else {
            patreon.setFocusable(true);
            patreon.setOnClickListener(v -> open(activity, PATREON));
            content.addView(patreon, new LinearLayout.LayoutParams(sheet.dp(224), sheet.dp(56)));
        }

        text(sheet, content, "One-time support with USDT", 18);
        text(sheet, content, "Use Tron (TRC20). Send more than 0.01 USDT. Leave the memo blank.", 15);
        TextView address = text(sheet, content, ADDRESS, 14);
        address.setTextIsSelectable(true);
        button(sheet, content, "Copy USDT address", () -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard == null) return;
            clipboard.setPrimaryClip(ClipData.newPlainText("USDT on Tron (TRC20)", ADDRESS));
            Toast.makeText(activity, "USDT address copied", Toast.LENGTH_SHORT).show();
        });
        ImageView qr = graphic(activity, "anything-usdt-tron-qr.svg", "USDT receiving address QR for Tron (TRC20)");
        if (qr != null) {
            LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(sheet.dp(216), sheet.dp(216));
            layout.gravity = Gravity.CENTER_HORIZONTAL;
            layout.topMargin = sheet.dp(12);
            content.addView(qr, layout);
        }
        button(sheet, content, "Open support page", () -> open(activity, SUPPORT));
        sheet.show();
    }

    private static TextView text(AppSheet sheet, LinearLayout content, String label, int size) {
        TextView text = sheet.text(label, size, sheet.theme.ink);
        text.setPadding(0, sheet.dp(8), 0, sheet.dp(8));
        content.addView(text, new LinearLayout.LayoutParams(-1, -2));
        return text;
    }

    private static void button(AppSheet sheet, LinearLayout content, String label, Runnable action) {
        Button button = new Button(content.getContext());
        button.setAllCaps(false);
        button.setText(label);
        button.setMinHeight(sheet.dp(48));
        button.setOnClickListener(v -> action.run());
        content.addView(button, new LinearLayout.LayoutParams(-1, -2));
    }

    private static ImageView graphic(Activity activity, String asset, String description) {
        try {
            ImageView image = new ImageView(activity);
            image.setContentDescription(description);
            image.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            image.setImageDrawable(new PictureDrawable(SVG.getFromAsset(activity.getAssets(), asset).renderToPicture()));
            return image;
        } catch (IOException | SVGParseException invalid) {
            return null;
        }
    }

    private static void open(Activity activity, String url) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException unavailable) {
            Toast.makeText(activity, "No browser found", Toast.LENGTH_SHORT).show();
        }
    }
}
