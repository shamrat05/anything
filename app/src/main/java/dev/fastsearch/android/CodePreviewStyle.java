package dev.fastsearch.android;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.TabStopSpan;

final class CodePreviewStyle {
    private static final int[] LIGHT = {0xFF5731A1, 0xFF98422B, 0xFF506573, 0xFF006270, 0xFF265699, 0xFF247140};
    private static final int[] DARK = {0xFFCDB0FF, 0xFFFFAF92, 0xFF9CABB6, 0xFF87DCE2, 0xFF99C5FF, 0xFF92DAAB};

    static CharSequence style(CodeSyntax.Model model, boolean dark, int gutterColor, int margin, int inset, int tab) {
        if (model.display.isEmpty()) return "This file is empty.";
        SpannableString result = new SpannableString(model.display);
        int[] colors = dark ? DARK : LIGHT;
        for (int i = 0; i < model.spans.length; i += 3) {
            result.setSpan(new ForegroundColorSpan(colors[model.spans[i + 2]]),
                    model.spans[i], model.spans[i + 1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        for (int i = 0; i < model.lineStarts.length; i++) {
            int end = i + 1 < model.lineStarts.length ? model.lineStarts[i + 1] : result.length();
            result.setSpan(new LineNumber(i + 1, gutterColor, margin, inset),
                    model.lineStarts[i], end, Spanned.SPAN_PARAGRAPH);
        }
        // Use a modest 4-character tab stop for source without inserting or deleting anything.
        for (int i = 1; i <= 8; i++) {
            result.setSpan(new TabStopSpan.Standard(tab * i), 0, result.length(), Spanned.SPAN_PARAGRAPH);
        }
        return result;
    }

    static final class LineNumber implements LeadingMarginSpan {
        private final String label;
        private final int color, margin, inset;
        LineNumber(int line, int color, int margin, int inset) {
            label = Integer.toString(line); this.color = color; this.margin = margin; this.inset = inset;
        }
        @Override public int getLeadingMargin(boolean first) { return margin; }
        @Override public void drawLeadingMargin(Canvas canvas, Paint paint, int x, int direction,
                int top, int baseline, int bottom, CharSequence text, int start, int end, boolean first, Layout layout) {
            if (!first) return;
            int oldColor = paint.getColor();
            float oldSize = paint.getTextSize();
            Paint.Align oldAlign = paint.getTextAlign();
            paint.setColor(color);
            paint.setTextSize(oldSize * .8f);
            paint.setTextAlign(direction == 1 ? Paint.Align.RIGHT : Paint.Align.LEFT);
            canvas.drawText(label, x + direction * (margin - inset), baseline, paint);
            paint.setColor(oldColor); paint.setTextSize(oldSize); paint.setTextAlign(oldAlign);
        }
    }

    private CodePreviewStyle() { }
}
