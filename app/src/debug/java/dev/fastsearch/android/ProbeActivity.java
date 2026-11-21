package dev.fastsearch.android;

/** Keeps isolated instrumentation foreground on devices that freeze cached app processes. */
public final class ProbeActivity extends android.app.Activity {
    @Override public void onCreate(android.os.Bundle state){super.onCreate(state);android.widget.TextView label=new android.widget.TextView(this);label.setText("Testing");label.setGravity(android.view.Gravity.CENTER);setContentView(label);}
}
