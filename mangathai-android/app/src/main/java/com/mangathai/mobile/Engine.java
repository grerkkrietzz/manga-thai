package com.mangathai.mobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** OCR + English to Thai translation, entirely on the phone. */
public final class Engine {
    public interface Status { void show(String text); }

    private static final String CACHE_VERSION = "m3";

    public static final String MODE_BASIC = "basic";
    public static final String MODE_DETECTOR = "detector";
    public static final String MODE_GEMINI = "gemini";

    private static final String DETECTOR_URL =
            "https://github.com/zyddnys/manga-image-translator/releases/download/beta-0.3/comictextdetector.pt.onnx";
    private static final String DETECTOR_SHA256 = "1a86ace74961413cbd650002e7bb4dcec4980ffa21b2f19b86933372071d718f";
    private static final long DETECTOR_BYTES = 94_669_756L;

    private final File pageDir;
    private final File textCacheFile;
    private final File rulesFile;
    private final TextRecognizer recognizer;
    private final Translator translator;
    private final JSONObject textCache;
    private TextTools.Rules rules;
    private final WordFixer fixer;
    private volatile boolean modelReady;
    private volatile boolean online;
    private volatile String mode = MODE_BASIC;
    private volatile String geminiKey = "";
    private volatile String geminiModel = GeminiClient.DEFAULT_MODEL;
    private final File detectorFile;
    private ComicDetector detector;
    private long detectorRetryAt;
    private boolean textCacheDirty;

    public Engine(Context context) {
        File base = context.getFilesDir();
        pageDir = new File(base, "pages");
        //noinspection ResultOfMethodCallIgnored
        pageDir.mkdirs();
        textCacheFile = new File(base, "translations.json");
        rulesFile = new File(base, "rules.txt");
        File models = new File(base, "models");
        //noinspection ResultOfMethodCallIgnored
        models.mkdirs();
        detectorFile = new File(models, "comictextdetector.onnx");
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        translator = Translation.getClient(new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.THAI)
                .build());
        JSONObject cache;
        try { cache = new JSONObject(read(textCacheFile)); } catch (Exception e) { cache = new JSONObject(); }
        textCache = cache;
        rules = TextTools.Rules.parse(readRules());
        fixer = new WordFixer(loadWords(context));
    }

    private static Set<String> loadWords(Context context) {
        Set<String> words = new HashSet<>(90000);
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                context.getAssets().open("words.txt"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) if (!line.isEmpty()) words.add(line);
        } catch (IOException ignored) { /* fixer then leaves text unchanged */ }
        return words;
    }

    /** Online mode: Google Translate (better wording), ML Kit as fallback. */
    public void setOnline(boolean value) { online = value; }
    public boolean isOnline() { return online; }

    /** basic = ML Kit only, detector = on-phone manga text detector, gemini = Gemini API. */
    public void setMode(String value) { mode = MODE_DETECTOR.equals(value) || MODE_GEMINI.equals(value) ? value : MODE_BASIC; }
    public String getMode() { return mode; }
    public void setGemini(String key, String model) {
        geminiKey = key == null ? "" : key.trim();
        geminiModel = model == null || model.trim().isEmpty() ? GeminiClient.DEFAULT_MODEL : model.trim();
    }
    public boolean hasGeminiKey() { return !geminiKey.isEmpty(); }

    // ---------- rules ----------
    public synchronized String readRules() {
        try { return read(rulesFile); } catch (IOException e) { return ""; }
    }

    public synchronized void saveRules(String text) throws IOException {
        write(rulesFile, text);
        rules = TextTools.Rules.parse(text);
        clearPages(); // pages store final text, rebuild them with the new rules
    }

    public synchronized void clearAll() {
        clearPages();
        Iterator<String> keys = textCache.keys();
        List<String> names = new ArrayList<>();
        while (keys.hasNext()) names.add(keys.next());
        for (String key : names) textCache.remove(key);
        textCacheDirty = true;
        flush();
    }

    private void clearPages() {
        File[] files = pageDir.listFiles();
        if (files != null) for (File f : files) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    // ---------- model ----------
    public void ensureModel(Status status) throws Exception {
        if (modelReady) return;
        status.show("ดาวน์โหลดโมเดลแปลภาษาไทยครั้งแรก (~30 MB) ต้องต่ออินเทอร์เน็ต…");
        Tasks.await(translator.downloadModelIfNeeded(new DownloadConditions.Builder().build()), 10, TimeUnit.MINUTES);
        modelReady = true;
        status.show("โมเดลพร้อมแล้ว ต่อจากนี้แปลแบบออฟไลน์ได้");
    }

    /** Downloads whatever the current mode needs before a page is processed. */
    public void prepare(Status status) throws Exception {
        if (MODE_GEMINI.equals(mode)) return;          // nothing to download; ML Kit is only a fallback
        ensureModel(status);
        if (MODE_DETECTOR.equals(mode) && detector == null && System.currentTimeMillis() >= detectorRetryAt) {
            try {
                ensureDetector(status);
                detector = new ComicDetector(detectorFile);
            } catch (Throwable error) {
                detectorRetryAt = System.currentTimeMillis() + 60_000L;   // do not retry on every page
                status.show("!ใช้ตัวตรวจจับมังงะไม่ได้ (" + describe(error) + ") ใช้โหมดปกติแทนชั่วคราว");
            }
        }
    }

    private void ensureDetector(Status status) throws Exception {
        if (detectorFile.length() == DETECTOR_BYTES) return;
        status.show("ดาวน์โหลดตัวตรวจจับข้อความมังงะครั้งแรก (~90 MB) ต้องต่ออินเทอร์เน็ต…");
        File temporary = new File(detectorFile.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(DETECTOR_URL).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(60000);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("ดาวน์โหลดไม่ได้ " + c.getResponseCode());
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                long total = 0, shown = 0;
                int n;
                while ((n = in.read(buffer)) > 0) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    total += n;
                    if (total - shown > 8_000_000L) {
                        shown = total;
                        status.show("ดาวน์โหลดตัวตรวจจับ " + (total / 1_000_000L) + "/" + (DETECTOR_BYTES / 1_000_000L) + " MB");
                    }
                }
            }
        } finally {
            c.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte d : digest.digest()) hex.append(String.format("%02x", d));
        if (!hex.toString().equals(DETECTOR_SHA256)) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("ไฟล์โมเดลไม่ตรงกับที่คาดไว้");
        }
        if (!temporary.renameTo(detectorFile)) throw new IOException("บันทึกโมเดลไม่ได้");
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }

    // ---------- page ----------
    /** Returns the JSON result for one page image (cached by image content). */
    public String process(byte[] data, Status status) throws Exception {
        String key = sha256(data);
        String current = mode;
        String suffix = MODE_GEMINI.equals(current) ? "-gm"
                : (MODE_DETECTOR.equals(current) && detector != null ? "-d" : "") + (online ? "-g" : "");
        File cached = new File(pageDir, key + suffix + ".json");
        if (cached.exists()) {
            try {
                String text = read(cached);
                if (new JSONObject(text).optString("cache").equals(CACHE_VERSION)) return text;
            } catch (Exception ignored) { /* rebuild */ }
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) throw new IOException("อ่านไฟล์ภาพไม่ได้");
        try {
            if (MODE_GEMINI.equals(current)) {
                try {
                    String result = processGemini(bitmap, key);
                    write(cached, result);
                    return result;
                } catch (GeminiClient.GeminiException error) {
                    if (error.code == 0 && !hasGeminiKey()) throw error;   // nothing to fall back from: tell the reader
                    status.show("!" + error.getMessage() + " — ใช้ตัวแปลในเครื่องแทนสำหรับหน้านี้");
                } catch (IOException | JSONException error) {
                    status.show("!เรียก Gemini ไม่ได้ (" + describe(error) + ") — ใช้ตัวแปลในเครื่องแทนสำหรับหน้านี้");
                }
                ensureModel(status);
                return processBitmap(bitmap, key, status);       // not cached under the Gemini name
            }
            String result = processBitmap(bitmap, key, status);
            write(cached, result);
            return result;
        } finally {
            bitmap.recycle();
        }
    }

    private String processBitmap(Bitmap bitmap, String key, Status status) throws Exception {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        // Manga lettering is small; ML Kit reads best at ~16-24 px letters.
        float scale = Math.max(1f, Math.min(2.5f, 1600f / Math.max(1, width)));
        Bitmap ocrBitmap = bitmap;
        if (scale > 1.01f) {
            ocrBitmap = Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
        }
        Text text;
        try {
            text = Tasks.await(recognizer.process(InputImage.fromBitmap(ocrBitmap, 0)), 60, TimeUnit.SECONDS);
        } finally {
            if (ocrBitmap != bitmap) ocrBitmap.recycle();
        }

        List<Grouper.Line> lines = new ArrayList<>();
        int blockIndex = 0;
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                Rect r = line.getBoundingBox();
                if (r == null) continue;
                lines.add(new Grouper.Line(r.left / scale, r.top / scale, r.width() / scale, r.height() / scale,
                        line.getText(), blockIndex));
            }
            blockIndex++;
        }

        List<List<Grouper.Line>> groups = MODE_DETECTOR.equals(mode) && detector != null
                ? detectedGroups(bitmap, lines) : Grouper.group(lines);
        JSONArray bubbles = new JSONArray();
        int index = 0;
        for (List<Grouper.Line> group : groups) {
            index++;
            String source = TextTools.clean(fixer.fix(bestReading(bitmap, group)));
            if (!TextTools.worthTranslating(source)) continue;
            status.show("แปลช่อง " + index + "/" + groups.size());
            String thai = translate(source);
            if (thai.trim().isEmpty()) continue;
            float[] rect = Grouper.bounds(group);
            String[] colors = colors(bitmap, rect);
            JSONObject item = new JSONObject();
            item.put("cover", array(rect));
            item.put("box", array(textBox(rect, width, height)));
            item.put("thai", thai);
            item.put("source", source);
            item.put("font", Math.round(Grouper.medianHeight(group) * 0.9f));
            item.put("background", colors[0]);
            item.put("color", colors[1]);
            bubbles.put(item);
        }
        flush();
        JSONObject result = new JSONObject();
        result.put("cache", CACHE_VERSION);
        result.put("sha256", key);
        result.put("width", width);
        result.put("height", height);
        result.put("bubbles", bubbles);
        return result.toString();
    }

    /**
     * Uses the manga text detector to decide what belongs together: every OCR
     * line goes to the detected block it sits in. Lines outside all blocks
     * still go through the normal grouping, so nothing the old way found is lost.
     */
    private List<List<Grouper.Line>> detectedGroups(Bitmap bitmap, List<Grouper.Line> lines) {
        List<float[]> boxes;
        try {
            boxes = detector.detect(bitmap);
        } catch (Throwable error) {
            return Grouper.group(lines);
        }
        if (boxes.isEmpty()) return Grouper.group(lines);
        List<List<Grouper.Line>> buckets = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) buckets.add(new ArrayList<>());
        List<Grouper.Line> leftover = new ArrayList<>();
        for (Grouper.Line line : lines) {
            float cx = line.x + line.w / 2f, cy = line.y + line.h / 2f;
            int owner = -1;
            float ownerArea = Float.MAX_VALUE;
            for (int i = 0; i < boxes.size(); i++) {
                float[] b = boxes.get(i);
                float padX = (b[2] - b[0]) * 0.03f, padY = (b[3] - b[1]) * 0.03f;
                if (cx < b[0] - padX || cx > b[2] + padX || cy < b[1] - padY || cy > b[3] + padY) continue;
                float area = (b[2] - b[0]) * (b[3] - b[1]);
                if (area < ownerArea) { owner = i; ownerArea = area; }   // innermost block wins
            }
            if (owner >= 0) buckets.get(owner).add(line); else leftover.add(line);
        }
        List<List<Grouper.Line>> groups = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            List<Grouper.Line> bucket = buckets.get(i);
            if (bucket.isEmpty()) {
                // The detector sees text ML Kit missed: read this block from a close crop instead.
                float[] b = boxes.get(i);
                float w = b[2] - b[0], h = b[3] - b[1];
                bucket.add(new Grouper.Line(b[0], b[1], w, h, "", -1 - i));
            }
            groups.add(bucket);
        }
        groups.addAll(Grouper.group(leftover));
        return groups;
    }

    // ---------- Gemini ----------
    private String processGemini(Bitmap bitmap, String key) throws Exception {
        int width = bitmap.getWidth(), height = bitmap.getHeight();
        float shrink = Math.min(1f, 2000f / Math.max(width, height));
        Bitmap send = shrink < 1f
                ? Bitmap.createScaledBitmap(bitmap, Math.round(width * shrink), Math.round(height * shrink), true)
                : bitmap;
        String jpeg;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            send.compress(Bitmap.CompressFormat.JPEG, 88, out);
            jpeg = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } finally {
            if (send != bitmap) send.recycle();
        }
        TextTools.Rules current;
        synchronized (this) { current = rules; }
        List<GeminiClient.Item> items = GeminiClient.translatePage(geminiKey, geminiModel, jpeg, current.glossary());
        JSONArray bubbles = new JSONArray();
        for (GeminiClient.Item item : items) {
            float x0 = item.xmin / 1000f * width, y0 = item.ymin / 1000f * height;
            float x1 = item.xmax / 1000f * width, y1 = item.ymax / 1000f * height;
            float[] rect = {x0, y0, x1 - x0, y1 - y0};
            if (rect[2] < 4 || rect[3] < 4) continue;
            String thai = current.applyAfter(item.thai).trim();
            if (thai.isEmpty()) continue;
            String[] colors = colors(bitmap, rect);
            // Roughly the size at which the Thai fills the box; the page script shrinks it to fit.
            float em = (float) Math.sqrt(rect[2] * rect[3] / (Math.max(4, thai.length()) * 0.8f));
            JSONObject bubble = new JSONObject();
            bubble.put("cover", array(rect));
            bubble.put("box", array(textBox(rect, width, height)));
            bubble.put("thai", thai);
            bubble.put("source", item.source);
            bubble.put("font", Math.round(Math.max(8f, Math.min(em, rect[3] * 0.6f))));
            bubble.put("background", colors[0]);
            bubble.put("color", colors[1]);
            bubbles.put(bubble);
        }
        JSONObject result = new JSONObject();
        result.put("cache", CACHE_VERSION);
        result.put("sha256", key);
        result.put("width", width);
        result.put("height", height);
        result.put("bubbles", bubbles);
        return result.toString();
    }

    private String translate(String source) throws Exception {
        TextTools.Rules current;
        synchronized (this) { current = rules; }
        String prepared = current.applyBefore(source);
        String raw = null;
        if (online) {
            String key = "g:" + prepared;
            synchronized (this) { raw = textCache.optString(key, null); }
            if (raw == null) {
                try {
                    raw = translateOnline(prepared);
                    if (!raw.trim().isEmpty()) synchronized (this) { textCache.put(key, raw); textCacheDirty = true; }
                } catch (Exception offline) {
                    raw = null;  // no internet or blocked: fall back to the phone's model
                }
            }
        }
        if (raw == null || raw.trim().isEmpty()) {
            synchronized (this) { raw = textCache.optString(prepared, null); }
            if (raw == null) {
                raw = Tasks.await(translator.translate(prepared), 60, TimeUnit.SECONDS);
                synchronized (this) {
                    textCache.put(prepared, raw);
                    textCacheDirty = true;
                }
            }
        }
        return current.applyAfter(raw).trim();
    }

    /** Free Google Translate endpoint. Sends only the text, never the image. */
    private static String translateOnline(String text) throws IOException, JSONException {
        HttpURLConnection c = (HttpURLConnection) new URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=en&tl=th&dt=t").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8");
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");
        try {
            try (OutputStream out = c.getOutputStream()) {
                out.write(("q=" + URLEncoder.encode(text, "UTF-8")).getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) throw new IOException("translate " + c.getResponseCode());
            String body;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = in.read(chunk)) > 0) buffer.write(chunk, 0, n);
                body = new String(buffer.toByteArray(), StandardCharsets.UTF_8);
            }
            JSONArray segments = new JSONArray(body).getJSONArray(0);
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < segments.length(); i++) {
                JSONArray segment = segments.optJSONArray(i);
                if (segment != null) out.append(segment.optString(0, ""));
            }
            return out.toString();
        } finally {
            c.disconnect();
        }
    }

    /**
     * Reads one balloon again from a close, enlarged crop (plain and with
     * boosted contrast, inverted for light-on-dark text) and keeps whichever
     * reading has the most real English words.
     */
    private String bestReading(Bitmap bitmap, List<Grouper.Line> group) {
        String best = Grouper.joinText(group);
        float bestScore = fixer.score(best);
        float h = Grouper.medianHeight(group);
        float[] r = Grouper.bounds(group);
        float pad = Math.max(6f, h * 0.6f);
        int x0 = clamp((int) (r[0] - pad), 0, bitmap.getWidth() - 1);
        int y0 = clamp((int) (r[1] - pad), 0, bitmap.getHeight() - 1);
        int x1 = clamp((int) Math.ceil(r[0] + r[2] + pad), x0 + 1, bitmap.getWidth());
        int y1 = clamp((int) Math.ceil(r[1] + r[3] + pad), y0 + 1, bitmap.getHeight());
        float scale = Math.max(1f, Math.min(4f, 36f / Math.max(4f, h)));   // letters ~36 px tall
        long area = (long) ((x1 - x0) * scale) * (long) ((y1 - y0) * scale);
        if (area > 6_000_000L) scale *= (float) Math.sqrt(6_000_000.0 / area);
        int width = Math.max(1, Math.round((x1 - x0) * scale)), height = Math.max(1, Math.round((y1 - y0) * scale));
        Bitmap crop = Bitmap.createBitmap(bitmap, x0, y0, x1 - x0, y1 - y0);
        Bitmap big = Bitmap.createScaledBitmap(crop, width, height, true);
        if (crop != big && crop != bitmap) crop.recycle();
        // Only keep lines inside this balloon's own text area (not a neighbour's).
        float left = (r[0] - x0 - h * 0.3f) * scale, top = (r[1] - y0 - h * 0.3f) * scale;
        float right = (r[0] + r[2] - x0 + h * 0.3f) * scale, bottom = (r[1] + r[3] - y0 + h * 0.3f) * scale;
        try {
            for (int variant = 0; variant < 3; variant++) {
                // 0: enlarged as is, 1: contrast-stretched grey, 2: black/white
                // (outlined letters floating on artwork read best as plain black on white).
                Bitmap image = variant == 0 ? big : variant == 1 ? enhanced(big) : binarized(big);
                try {
                    Text text = Tasks.await(recognizer.process(InputImage.fromBitmap(image, 0)), 30, TimeUnit.SECONDS);
                    List<Grouper.Line> lines = new ArrayList<>();
                    for (Text.TextBlock block : text.getTextBlocks()) {
                        for (Text.Line line : block.getLines()) {
                            Rect b = line.getBoundingBox();
                            if (b == null) continue;
                            float cx = (b.left + b.right) / 2f, cy = (b.top + b.bottom) / 2f;
                            if (cx < left || cx > right || cy < top || cy > bottom) continue;
                            lines.add(new Grouper.Line(b.left, b.top, b.width(), b.height(), line.getText(), 0));
                        }
                    }
                    if (lines.isEmpty()) continue;
                    String reading = Grouper.joinText(lines);
                    float score = fixer.score(reading);
                    if (score > bestScore) { best = reading; bestScore = score; }
                } catch (Exception ignored) {
                    // keep the first reading
                } finally {
                    if (image != big) image.recycle();
                }
            }
        } finally {
            big.recycle();
        }
        return best;
    }

    /** Grey, contrast-stretched copy; inverted when the balloon is dark. */
    private static Bitmap enhanced(Bitmap source) {
        int w = source.getWidth(), h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int lum = (int) (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255));
            pixels[i] = lum;
            histogram[lum]++;
        }
        int low = percentile(histogram, pixels.length, 0.02f), high = percentile(histogram, pixels.length, 0.98f);
        int median = percentile(histogram, pixels.length, 0.5f);
        boolean invert = median < 110;
        float range = Math.max(1, high - low);
        for (int i = 0; i < pixels.length; i++) {
            int v = clamp(Math.round((pixels[i] - low) * 255f / range), 0, 255);
            if (invert) v = 255 - v;
            pixels[i] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    /** Otsu threshold: letters become black on white, whatever their colour or outline. */
    private static Bitmap binarized(Bitmap source) {
        int w = source.getWidth(), h = source.getHeight();
        int[] pixels = new int[w * h];
        source.getPixels(pixels, 0, w, 0, 0, w, h);
        int[] histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            int c = pixels[i];
            int lum = (int) (0.299 * ((c >> 16) & 255) + 0.587 * ((c >> 8) & 255) + 0.114 * (c & 255));
            pixels[i] = lum;
            histogram[lum]++;
        }
        long sumAll = 0;
        for (int v = 0; v < 256; v++) sumAll += (long) v * histogram[v];
        long sumBelow = 0;
        int below = 0, threshold = 128;
        double bestVariance = -1;
        for (int t = 0; t < 256; t++) {
            below += histogram[t];
            if (below == 0) continue;
            int above = pixels.length - below;
            if (above == 0) break;
            sumBelow += (long) t * histogram[t];
            double meanBelow = sumBelow / (double) below, meanAbove = (sumAll - sumBelow) / (double) above;
            double variance = (double) below * above * (meanBelow - meanAbove) * (meanBelow - meanAbove);
            if (variance > bestVariance) { bestVariance = variance; threshold = t; }
        }
        int dark = 0;
        for (int v = 0; v <= threshold; v++) dark += histogram[v];
        boolean lettersAreDark = dark < pixels.length / 2;   // letters are the minority
        for (int i = 0; i < pixels.length; i++) {
            boolean isDark = pixels[i] <= threshold;
            int v = (isDark == lettersAreDark) ? 0 : 255;
            pixels[i] = 0xff000000 | (v << 16) | (v << 8) | v;
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }

    private static int percentile(int[] histogram, int total, float fraction) {
        int target = Math.round(total * fraction), sum = 0;
        for (int v = 0; v < 256; v++) { sum += histogram[v]; if (sum >= target) return v; }
        return 255;
    }

    private synchronized void flush() {
        if (!textCacheDirty) return;
        try { write(textCacheFile, textCache.toString()); textCacheDirty = false; } catch (IOException ignored) { }
    }

    /** Thai is longer than English: give it a little more room around the English text. */
    private static float[] textBox(float[] r, int width, int height) {
        float growX = r[2] * 0.12f, growY = r[3] * 0.10f;
        float left = Math.max(0, r[0] - growX), top = Math.max(0, r[1] - growY);
        float right = Math.min(width, r[0] + r[2] + growX), bottom = Math.min(height, r[1] + r[3] + growY);
        return new float[]{left, top, right - left, bottom - top};
    }

    /** Paper colour behind the lettering and a readable text colour. */
    private static String[] colors(Bitmap bitmap, float[] r) {
        int x0 = clamp((int) r[0], 0, bitmap.getWidth() - 1), y0 = clamp((int) r[1], 0, bitmap.getHeight() - 1);
        int x1 = clamp((int) (r[0] + r[2]), x0 + 1, bitmap.getWidth()), y1 = clamp((int) (r[1] + r[3]), y0 + 1, bitmap.getHeight());
        int step = Math.max(1, (int) Math.sqrt((x1 - x0) * (double) (y1 - y0) / 2500.0));
        List<int[]> samples = new ArrayList<>();
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = bitmap.getPixel(x, y);
                int red = (c >> 16) & 255, green = (c >> 8) & 255, blue = c & 255;
                samples.add(new int[]{(int) (0.299 * red + 0.587 * green + 0.114 * blue), red, green, blue});
            }
        }
        if (samples.isEmpty()) return new String[]{"#ffffff", "#1f1830"};
        samples.sort((a, b) -> Integer.compare(a[0], b[0]));
        int n = samples.size();
        boolean lightPaper = samples.get(n / 2)[0] > 110;
        List<int[]> chosen = lightPaper ? samples.subList(n * 2 / 5, n) : samples.subList(0, Math.max(1, n / 5));
        int[] red = new int[chosen.size()], green = new int[chosen.size()], blue = new int[chosen.size()];
        for (int i = 0; i < chosen.size(); i++) { red[i] = chosen.get(i)[1]; green[i] = chosen.get(i)[2]; blue[i] = chosen.get(i)[3]; }
        int rr = median(red), gg = median(green), bb = median(blue);
        double luminance = 0.299 * rr + 0.587 * gg + 0.114 * bb;
        return new String[]{String.format("#%02x%02x%02x", rr, gg, bb), luminance > 140 ? "#1f1830" : "#ffffff"};
    }

    public void close() {
        flush();
        recognizer.close();
        translator.close();
        if (detector != null) detector.close();
    }

    // ---------- small helpers ----------
    private static int median(int[] values) { Arrays.sort(values); return values[values.length / 2]; }
    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static JSONArray array(float[] values) throws JSONException {
        JSONArray a = new JSONArray();
        for (float v : values) a.put(Math.round(v * 10f) / 10.0);
        return a;
    }

    static String sha256(byte[] data) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder b = new StringBuilder();
        for (byte d : digest) b.append(String.format("%02x", d));
        return b.toString();
    }

    static String read(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void write(File file, String text) throws IOException {
        File temporary = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!temporary.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            if (!temporary.renameTo(file)) throw new IOException("บันทึกไฟล์ไม่ได้: " + file.getName());
        }
    }
}
