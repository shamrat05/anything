package dev.fastsearch.android;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Color;
import android.os.Build;

/** A quiet desktop-style tint, sourced only from Android's public tonal palette. */
final class AmbientPalette {
    static final String PREFERENCE = "wallpaperTint";
    static final int COOL_BLUE = 0xFF6FA4EA;

    private AmbientPalette() {}

    static int systemTone(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                int seed = context.getColor(android.R.color.system_accent1_500);
                if (Color.alpha(seed) == 255) return seed;
            } catch (Resources.NotFoundException ignored) {
                // Some Android builds do not expose an active tonal overlay.
            }
        }
        return COOL_BLUE;
    }

    static int surface(boolean dark, int seed) {
        return mix(dark ? 0xFF141415 : UiTheme.LIGHT_SURFACE, cooled(seed), dark ? 5 : 6);
    }

    static int chrome(boolean dark, int seed) {
        return mix(dark ? 0xFF202123 : UiTheme.LIGHT_CHROME, cooled(seed), dark ? 9 : 7);
    }

    private static int cooled(int seed) {
        return mix(COOL_BLUE, seed, 55);
    }

    private static int mix(int from, int to, int amount) {
        return Color.rgb(channel(Color.red(from), Color.red(to), amount),
                channel(Color.green(from), Color.green(to), amount),
                channel(Color.blue(from), Color.blue(to), amount));
    }

    private static int channel(int from, int to, int amount) {
        return (from * (100 - amount) + to * amount + 50) / 100;
    }
}
