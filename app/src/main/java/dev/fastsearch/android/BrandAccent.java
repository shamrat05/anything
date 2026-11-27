package dev.fastsearch.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/** The cool section of Call Craft Studio's mark, reserved for quiet navigation detail. */
final class BrandAccent {
    private static final int INDIGO = 0xFF1D1266;
    private static final int BLUE = 0xFF0082FF;
    private static final int CYAN = 0xFF65D9F1;

    private BrandAccent() {}

    static View rule(Context context) {
        View line = new View(context);
        GradientDrawable brush = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{INDIGO, BLUE, CYAN});
        brush.setCornerRadius(context.getResources().getDisplayMetrics().density);
        line.setBackground(brush);
        line.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return line;
    }

    static void reveal(View line) {
        line.animate().cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            line.setAlpha(1f);
            line.setScaleX(1f);
            return;
        }
        line.setAlpha(.6f);
        line.setScaleX(.88f);
        line.animate().alpha(1f).scaleX(1f).setInterpolator(new DecelerateInterpolator())
                .setDuration(150).start();
    }
}
