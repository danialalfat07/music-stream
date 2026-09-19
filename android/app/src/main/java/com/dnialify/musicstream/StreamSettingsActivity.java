package com.dnialify.musicstream;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Phase 8 - native-only stream settings (diagnostic entry, NOT a feature UI).
 *
 * Stream: iFrame (default, existing flow untouched) | VisionOS (proven pipeline).
 * Audio Cache ON/OFF + Max Cached Songs are configuration/state only for Phase 9
 * (no files, no LRU, no eviction, no download manager here).
 * Visibility: Audio Cache row only when VisionOS; Max row only when VisionOS + Cache ON.
 * "Test Play" runs the proven VisionOS resolve for the fixed test video and hands the
 * direct URL to the existing WebView HTML5 audio element. No queue integration (Phase 9).
 */
public class StreamSettingsActivity extends Activity {
    private static final String TAG_DIAG = "DnialifyDiag";
    private static final String TEST_VIDEO_ID = "M7lc1UVf-VE";

    private RadioGroup modeGroup;
    private RadioButton radioIframe;
    private RadioButton radioVisionos;
    private CheckBox cacheBox;
    private TextView maxLabel;
    private NumberPicker maxPicker;
    private TextView logView;
    private Button testButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Log.d(TAG_DIAG, "StreamSettingsActivity onCreate");
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Stream Settings (native, Phase 8 diag)");
        title.setTextSize(18);
        root.addView(title);

        TextView modeLabel = new TextView(this);
        modeLabel.setText("Stream:");
        root.addView(modeLabel);

        modeGroup = new RadioGroup(this);
        modeGroup.setOrientation(RadioGroup.VERTICAL);
        radioIframe = new RadioButton(this);
        radioIframe.setText("iFrame (existing flow)");
        radioIframe.setId(View.generateViewId());
        radioVisionos = new RadioButton(this);
        radioVisionos.setText("VisionOS (direct googlevideo)");
        radioVisionos.setId(View.generateViewId());
        modeGroup.addView(radioIframe);
        modeGroup.addView(radioVisionos);
        root.addView(modeGroup);

        cacheBox = new CheckBox(this);
        cacheBox.setText("Audio Cache (Phase 9 config only)");
        root.addView(cacheBox);

        maxLabel = new TextView(this);
        maxLabel.setText("Max Cached Songs (Phase 9 config only):");
        root.addView(maxLabel);

        maxPicker = new NumberPicker(this);
        maxPicker.setMinValue(1);
        maxPicker.setMaxValue(200);
        root.addView(maxPicker);

        testButton = new Button(this);
        testButton.setText("Test Play " + TEST_VIDEO_ID);
        root.addView(testButton);

        Button closeButton = new Button(this);
        closeButton.setText("Close");
        root.addView(closeButton);

        logView = new TextView(this);
        logView.setTextSize(12);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(logView);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180));
        root.addView(scroll, slp);

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);

        refreshFromPrefs();
        modeGroup.setOnCheckedChangeListener((g, id) -> {
            StreamSettings.setStreamMode(this,
                    id == radioVisionos.getId() ? StreamSettings.MODE_VISIONOS : StreamSettings.MODE_IFRAME);
            Log.d(TAG_DIAG, "StreamSettings mode=" + (id == radioVisionos.getId() ? "VISIONOS" : "IFRAME"));
            refreshVisibility();
        });
        cacheBox.setOnCheckedChangeListener((b, on) -> {
            StreamSettings.setAudioCache(this, on);
            Log.d(TAG_DIAG, "StreamSettings audioCache=" + on);
            refreshVisibility();
        });
        maxPicker.setOnValueChangedListener((p, oldV, newV) -> {
            StreamSettings.setMaxCachedSongs(this, newV);
            Log.d(TAG_DIAG, "StreamSettings maxCachedSongs=" + newV);
        });
        testButton.setOnClickListener(v -> runTestPlay());
        closeButton.setOnClickListener(v -> finish());
        refreshVisibility();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void refreshFromPrefs() {
        int mode = StreamSettings.getStreamMode(this);
        if (mode == StreamSettings.MODE_VISIONOS) modeGroup.check(radioVisionos.getId());
        else modeGroup.check(radioIframe.getId());
        cacheBox.setChecked(StreamSettings.isAudioCacheOn(this));
        maxPicker.setValue(StreamSettings.getMaxCachedSongs(this));
    }

    private void refreshVisibility() {
        boolean visionos = modeGroup.getCheckedRadioButtonId() == radioVisionos.getId();
        boolean cacheOn = cacheBox.isChecked();
        cacheBox.setVisibility(visionos ? View.VISIBLE : View.GONE);
        int maxVis = (visionos && cacheOn) ? View.VISIBLE : View.GONE;
        maxLabel.setVisibility(maxVis);
        maxPicker.setVisibility(maxVis);
        testButton.setVisibility(visionos ? View.VISIBLE : View.GONE);
    }

    private void dlog(final String m) {
        Log.d(TAG_DIAG, "StreamSettings " + m);
        runOnUiThread(() -> logView.append(m + "\n"));
    }

    /** Phase 8 proof: resolve test video natively, play direct URL in existing WebView audio. */
    private void runTestPlay() {
        dlog("[1] DIAG START id=" + TEST_VIDEO_ID + " mode=VISIONOS(native)");
        testButton.setEnabled(false);
        new Thread(() -> {
            try {
                VisionOsResolver.Result r;
                try {
                    r = VisionOsResolver.resolve(TEST_VIDEO_ID);
                } catch (VisionOsResolver.ResolverException e) {
                    dlog("[FAIL] reason=" + e.reason + " stage=" + e.stage + " " + e.getMessage()
                            + " action=fallback-iFrame");
                    runOnUiThread(() -> testButton.setEnabled(true));
                    return;
                }
                dlog("[2] RESOLVER PASS itag=" + r.itag + " mime=" + r.mimeType
                        + " retriedVisitor=" + r.retriedVisitor);
                dlog("[3] DIRECT host=" + r.host + " visitorCache=" + (r.visitorFromCache ? "session" : "fresh"));
                dlog("[4] HANDOFF to WebView HTML5 audio (existing element)");
                final String url = r.url.replace("'", "%27");
                MainActivity a = MainActivity.current;
                if (a == null) {
                    dlog("[FAIL] HANDOFF no activity");
                    runOnUiThread(() -> testButton.setEnabled(true));
                    return;
                }
                a.runOnUiThread(() -> {
                    try {
                        android.webkit.WebView wv = null;
                        try {
                            wv = a.getBridge() != null ? a.getBridge().getWebView() : null;
                        } catch (Exception ignored) {}
                        if (wv == null) {
                            dlog("[FAIL] HANDOFF webview null");
                            testButton.setEnabled(true);
                            return;
                        }
                        String js = "(function(){try{"
                                + "var el=document.querySelector('audio');"
                                + "if(!el){return 'NO_AUDIO_ELEMENT';}"
                                + "el.src='" + url + "';"
                                + "var p=el.play();"
                                + "if(p&&p.then){p.then(function(){},function(e){});}"
                                + "return 'SRC_SET';"
                                + "}catch(e){return 'JS_ERR '+e;}})()";
                        wv.evaluateJavascript(js, v -> {
                            dlog("[5] HANDOFF result=" + v + " (watch timeupdate/duration in app UI + logcat)");
                            runOnUiThread(() -> testButton.setEnabled(true));
                        });
                    } catch (Exception e) {
                        dlog("[FAIL] HANDOFF threw " + e);
                        testButton.setEnabled(true);
                    }
                });
            } catch (Exception e) {
                dlog("[FAIL] unexpected " + e);
                runOnUiThread(() -> testButton.setEnabled(true));
            }
        }).start();
    }
}
