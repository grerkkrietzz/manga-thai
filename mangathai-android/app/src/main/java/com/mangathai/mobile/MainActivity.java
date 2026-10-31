package com.mangathai.mobile;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small browser: open a manga site, tap "แปล" to translate every page. */
public final class MainActivity extends Activity {
    private static final String HOME = "https://mangadex.org/";
    private static final Pattern URL = Pattern.compile("https?://\\S+");

    private WebView web;
    private EditText address;
    private Engine engine;
    private Bridge bridge;
    private String overlayScript;
    private boolean translating;
    private SharedPreferences prefs;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("mangathai", MODE_PRIVATE);
        overlayScript = asset("overlay.js");
        engine = new Engine(this);
        engine.setOnline(prefs.getBoolean("online", false));
        engine.setGemini(prefs.getString("geminiKey", ""), prefs.getString("geminiModel", GeminiClient.DEFAULT_MODEL));
        String savedMode = prefs.getString("mode", Engine.MODE_BASIC);
        // Gemini without a key cannot work: start in the normal mode instead.
        engine.setMode(Engine.MODE_GEMINI.equals(savedMode) && !engine.hasGeminiKey() ? Engine.MODE_BASIC : savedMode);

        int bar = Color.parseColor("#161c27");
        getWindow().setStatusBarColor(bar);
        getWindow().setNavigationBarColor(bar);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bar);
        root.setFitsSystemWindows(true);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(8), dp(6), dp(4), dp(6));
        address = new EditText(this);
        address.setSingleLine(true);
        address.setTextColor(Color.parseColor("#e9edf6"));
        address.setHintTextColor(Color.parseColor("#7f8ca3"));
        address.setHint("ใส่ลิงก์หน้าอ่านมังงะ");
        address.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        address.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        address.setImeOptions(EditorInfo.IME_ACTION_GO);
        address.setSelectAllOnFocus(true);
        address.setPadding(dp(12), dp(8), dp(12), dp(8));
        address.setBackground(rounded("#101620", "#344158", 18));
        address.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_GO || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
                open(address.getText().toString());
                return true;
            }
            return false;
        });
        top.addView(address, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button menu = new Button(this);
        menu.setText("⋮");
        menu.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        menu.setTextColor(Color.parseColor("#e9edf6"));
        menu.setBackgroundColor(Color.TRANSPARENT);
        menu.setOnClickListener(this::showMenu);
        top.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout frame = new FrameLayout(this);
        web = new WebView(this);
        frame.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Button translate = new Button(this);
        translate.setText("แปล");
        translate.setAllCaps(false);
        translate.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        translate.setTextColor(Color.parseColor("#102621"));
        translate.setBackground(rounded("#5dd6be", null, 28));
        translate.setElevation(dp(6));
        translate.setOnClickListener(v -> toggleTranslation());
        FrameLayout.LayoutParams fab = new FrameLayout.LayoutParams(dp(76), dp(56), Gravity.BOTTOM | Gravity.END);
        fab.setMargins(0, 0, dp(18), dp(78));
        frame.addView(translate, fab);
        root.addView(frame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        bridge = new Bridge(engine, new Bridge.Output() {
            @Override
            public void result(String requestId, boolean ok, String payload) {
                runOnUiThread(() -> web.evaluateJavascript("window.__mtDone&&window.__mtDone("
                        + JSONObject.quote(requestId) + "," + ok + "," + JSONObject.quote(payload) + ")", null));
            }

            @Override
            public void status(String text) {
                runOnUiThread(() -> web.evaluateJavascript("window.__mtStatus&&window.__mtStatus("
                        + JSONObject.quote(text) + ")", null));
            }
        });
        web.addJavascriptInterface(bridge, "MangaThaiNative");
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                String text = message.message();
                if (text != null && text.startsWith("MangaThai")) {
                    android.util.Log.w("MangaThai", text);
                }
                return super.onConsoleMessage(message);
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!address.hasFocus()) address.setText(url);
                prefs.edit().putString("last", url).apply();
                // Keep translating on the next page once the reader turned it on.
                if (translating) inject(false);
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                if (!address.hasFocus()) address.setText(url);
                prefs.edit().putString("last", url).apply();
            }
        });

        if (state != null) web.restoreState(state);
        if (state == null || web.getUrl() == null) {
            String shared = linkFrom(getIntent());
            open(shared != null ? shared : prefs.getString("last", HOME));
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String shared = linkFrom(intent);
        if (shared != null) open(shared);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        bridge.close();
        engine.close();
        web.destroy();
        super.onDestroy();
    }

    // ---------- actions ----------
    private void toggleTranslation() {
        if (!translating) toast("เริ่มแปล… ดูความคืบหน้าที่แถบด้านล่าง");
        translating = true;
        inject(true);
    }

    /** Runs overlay.js. On a page that already has it, a tap toggles show/hide. */
    private void inject(boolean fromButton) {
        String guard = fromButton ? "" : "if(window.__mangathai)return 'running';";
        String script = "(function(){try{" + guard + "\n" + overlayScript
                + "\nreturn window.__mangathai?'ok':'stopped';}catch(e){return 'error:'+(e&&e.message||e);}})()";
        web.evaluateJavascript(script, value -> {
            String result = value == null ? "" : value.replace("\"", "");
            if (result.startsWith("error:")) {
                toast("แปลไม่ได้: " + result.substring(6));
            } else if (fromButton && result.equals("null")) {
                toast("หน้านี้ยังโหลดไม่เสร็จ ลองกดแปลอีกครั้ง");
            }
        });
    }

    private void open(String text) {
        String url = text == null ? "" : text.trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = url.contains(".") && !url.contains(" ") ? "https://" + url
                    : "https://www.google.com/search?q=" + Uri.encode(url);
        }
        web.loadUrl(url);
        address.clearFocus();
        InputMethodManager keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(address.getWindowToken(), 0);
    }

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "ชื่อตัวละคร / คำแทนที่…");
        popup.getMenu().add(0, 2, 1, "หยุดแปลอัตโนมัติในหน้าถัดไป");
        popup.getMenu().add(0, 3, 2, "ล้างคำแปลที่จำไว้");
        popup.getMenu().add(0, 4, 3, "หน้าแรก MangaDex");
        popup.getMenu().add(0, 5, 4, "โหลดหน้านี้ใหม่");
        popup.getMenu().add(0, 7, 5, "โหมดอ่านข้อความ: " + modeName(engine.getMode()) + "…");
        popup.getMenu().add(0, 8, 6, "ตั้งค่า Gemini (API key)…");
        popup.getMenu().add(0, 6, 7, engine.isOnline()
                ? "✓ แปลคุณภาพสูง (ออนไลน์) — แตะเพื่อปิด"
                : "แปลคุณภาพสูง (ออนไลน์) — แตะเพื่อเปิด");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: editRules(); return true;
                case 2:
                    translating = false;
                    toast("หน้าถัดไปจะไม่แปลเอง กดปุ่มแปลเมื่อต้องการ");
                    return true;
                case 3: confirmClear(); return true;
                case 7: chooseMode(); return true;
                case 8: editGemini(null); return true;
                case 4: open(HOME); return true;
                case 5: web.reload(); return true;
                case 6: {
                    boolean on = !engine.isOnline();
                    engine.setOnline(on);
                    prefs.edit().putBoolean("online", on).apply();
                    toast(on ? "เปิดแปลคุณภาพสูงแล้ว (ส่งเฉพาะข้อความไป Google Translate ไม่ส่งภาพ) ถ้าไม่มีเน็ตจะใช้ตัวแปลในเครื่องแทน"
                             : "ปิดแล้ว แปลในเครื่องอย่างเดียว");
                    web.evaluateJavascript("window.__mangathai&&window.__mangathai.reset()", null);
                    return true;
                }
                default: return false;
            }
        });
        popup.show();
    }

    private static String modeName(String mode) {
        if (Engine.MODE_GEMINI.equals(mode)) return "Gemini";
        if (Engine.MODE_DETECTOR.equals(mode)) return "ตัวตรวจจับมังงะ";
        return "ปกติ";
    }

    private void chooseMode() {
        String[] names = {
                "ปกติ — ML Kit อย่างเดียว ออฟไลน์",
                "ตัวตรวจจับมังงะในเครื่อง — แม่นขึ้น ออฟไลน์ (โหลดโมเดล ~90 MB ครั้งแรก)",
                "Gemini — แม่นที่สุด ต้องมีเน็ตและ API key (ส่งภาพหน้ามังงะไปที่ Google)"};
        String[] modes = {Engine.MODE_BASIC, Engine.MODE_DETECTOR, Engine.MODE_GEMINI};
        int current = Math.max(0, java.util.Arrays.asList(modes).indexOf(engine.getMode()));
        new AlertDialog.Builder(this)
                .setTitle("โหมดอ่านข้อความ")
                .setSingleChoiceItems(names, current, (dialog, which) -> {
                    dialog.dismiss();
                    if (Engine.MODE_GEMINI.equals(modes[which]) && !engine.hasGeminiKey()) {
                        editGemini(Engine.MODE_GEMINI);      // needs a key first
                    } else {
                        applyMode(modes[which]);
                    }
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void applyMode(String mode) {
        engine.setMode(mode);
        prefs.edit().putString("mode", mode).apply();
        toast("โหมด: " + modeName(mode) + " กดแปลอีกครั้งเพื่อใช้กับหน้านี้");
        web.evaluateJavascript("window.__mangathai&&window.__mangathai.reset()", null);
    }

    /** @param thenMode mode to switch to once a key is saved, or null to just save */
    private void editGemini(String thenMode) {
        EditText key = new EditText(this);
        key.setSingleLine(true);
        key.setHint("API key (AIza…)");
        key.setText(prefs.getString("geminiKey", ""));
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText model = new EditText(this);
        model.setSingleLine(true);
        model.setHint("ชื่อโมเดล");
        model.setText(prefs.getString("geminiModel", GeminiClient.DEFAULT_MODEL));
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(key, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrap.addView(model, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("ตั้งค่า Gemini")
                .setMessage("สร้าง key ฟรีได้ที่ aistudio.google.com/apikey (ค่า Gemini Pro ในแอปไม่ครอบคลุม API)\n"
                        + "ภาพหน้ามังงะจะถูกส่งไป Google และ key เก็บในเครื่องนี้เท่านั้น\n"
                        + "ถ้าเรียกไม่ได้หรือโควตาหมด แอปจะใช้ตัวแปลในเครื่องแทนเอง")
                .setView(wrap)
                .setPositiveButton("บันทึก", (d, w) -> {
                    prefs.edit().putString("geminiKey", key.getText().toString().trim())
                            .putString("geminiModel", model.getText().toString().trim()).apply();
                    engine.setGemini(key.getText().toString(), model.getText().toString());
                    if (thenMode != null && engine.hasGeminiKey()) applyMode(thenMode);
                    else if (Engine.MODE_GEMINI.equals(engine.getMode()) && !engine.hasGeminiKey()) applyMode(Engine.MODE_BASIC);
                    else toast("บันทึกแล้ว");
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void editRules() {
        EditText box = new EditText(this);
        box.setText(engine.readRules());
        box.setHint("Gyro=ไจโร\nJohnny=จอนนี่\nCorpse=ศพศักดิ์สิทธิ์\nคุณ=แก");
        box.setMinLines(8);
        box.setGravity(Gravity.TOP | Gravity.START);
        box.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(20), dp(8), dp(20), 0);
        wrap.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("ชื่อตัวละคร / คำแทนที่")
                .setMessage("บรรทัดละคำ ใช้รูปแบบ เดิม=ใหม่\nคำภาษาอังกฤษ (ชื่อ) แทนก่อนแปล คำไทยแทนหลังแปล")
                .setView(wrap)
                .setPositiveButton("บันทึก", (d, w) -> {
                    try {
                        engine.saveRules(box.getText().toString());
                        toast("บันทึกแล้ว กดแปลอีกครั้งเพื่อใช้กับหน้านี้");
                        web.evaluateJavascript("window.__mangathai&&window.__mangathai.reset()", null);
                    } catch (IOException e) {
                        toast("บันทึกไม่ได้: " + e.getMessage());
                    }
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("ล้างคำแปลที่จำไว้?")
                .setMessage("หน้าที่เคยแปลจะต้องแปลใหม่ ชื่อตัวละครและคำแทนที่ยังอยู่")
                .setPositiveButton("ล้าง", (d, w) -> {
                    engine.clearAll();
                    web.evaluateJavascript("window.__mangathai&&window.__mangathai.reset()", null);
                    toast("ล้างแล้ว");
                })
                .setNegativeButton("ยกเลิก", null)
                .show();
    }

    // ---------- helpers ----------
    private static String linkFrom(Intent intent) {
        if (intent == null) return null;
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            return intent.getData().toString();
        }
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null) {
                Matcher m = URL.matcher(text);
                if (m.find()) return m.group();
            }
        }
        return null;
    }

    private String asset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private GradientDrawable rounded(String fill, String stroke, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(fill));
        d.setCornerRadius(dp(radiusDp));
        if (stroke != null) d.setStroke(dp(1), Color.parseColor(stroke));
        return d;
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
