package com.mangathai.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * One call to the Gemini API per page: finds every piece of lettering,
 * reads it and translates it into Thai. Pure Java (no Android classes).
 */
public final class GeminiClient {
    public static final String DEFAULT_MODEL = "gemini-flash-latest";
    static String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/";

    /** One piece of lettering. Box is normalised 0..1000: ymin, xmin, ymax, xmax. */
    public static final class Item {
        public final int ymin, xmin, ymax, xmax;
        public final String source, thai;

        Item(int ymin, int xmin, int ymax, int xmax, String source, String thai) {
            this.ymin = ymin; this.xmin = xmin; this.ymax = ymax; this.xmax = xmax;
            this.source = source; this.thai = thai;
        }
    }

    /** Message is already Thai, ready to show to the reader. */
    public static final class GeminiException extends IOException {
        public final int code;
        public GeminiException(int code, String message) { super(message); this.code = code; }
    }

    private GeminiClient() {}

    static String prompt(String glossary) {
        StringBuilder p = new StringBuilder();
        p.append("You are a manga/comic typesetter translating English into natural Thai.\n")
         .append("Find every piece of readable lettering on this page: speech balloons, thought balloons, ")
         .append("narration boxes and sound effects that carry meaning. Ignore watermarks, page numbers and site logos.\n")
         .append("For each one return:\n")
         .append("- box_2d: [ymin, xmin, ymax, xmax] normalised to 0-1000, tightly around the LETTERS ")
         .append("(not the whole balloon outline).\n")
         .append("- source: the English text, with hyphenated line breaks joined.\n")
         .append("- thai: a natural, conversational Thai translation that suits the speaker and scene. ")
         .append("Keep it short enough to fit the same space. Keep names consistent across the page.\n")
         .append("List items in reading order (right-to-left rows only if the page is clearly read that way). ")
         .append("If there is no lettering return an empty array.");
        if (glossary != null && !glossary.trim().isEmpty()) {
            p.append("\nUse these fixed translations for names and terms (English = Thai):\n").append(glossary.trim());
        }
        return p.toString();
    }

    static JSONObject schema() throws JSONException {
        JSONObject box = new JSONObject().put("type", "ARRAY").put("items", new JSONObject().put("type", "INTEGER"));
        JSONObject props = new JSONObject()
                .put("box_2d", box)
                .put("source", new JSONObject().put("type", "STRING"))
                .put("thai", new JSONObject().put("type", "STRING"));
        JSONObject item = new JSONObject().put("type", "OBJECT").put("properties", props)
                .put("required", new JSONArray().put("box_2d").put("source").put("thai"));
        return new JSONObject().put("type", "ARRAY").put("items", item);
    }

    /** @param jpegBase64 the page as base64 JPEG (no line wraps) */
    public static List<Item> translatePage(String apiKey, String model, String jpegBase64, String glossary)
            throws IOException, JSONException {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new GeminiException(0, "ยังไม่ได้ใส่ Gemini API key");
        String name = model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim();
        JSONObject body = new JSONObject()
                .put("contents", new JSONArray().put(new JSONObject().put("parts", new JSONArray()
                        .put(new JSONObject().put("text", prompt(glossary)))
                        .put(new JSONObject().put("inline_data", new JSONObject()
                                .put("mime_type", "image/jpeg").put("data", jpegBase64))))))
                .put("generationConfig", new JSONObject()
                        .put("temperature", 0.2)
                        .put("responseMimeType", "application/json")
                        .put("responseSchema", schema()));
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

        GeminiException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            if (attempt > 0) {
                try { Thread.sleep(2000L << (attempt - 1)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            }
            try {
                return parse(post(apiKey.trim(), name, payload));
            } catch (GeminiException e) {
                last = e;
                if (e.code != 429 && e.code != 500 && e.code != 503) throw e;   // retry only busy/overloaded
            }
        }
        throw last != null ? last : new GeminiException(0, "เรียก Gemini ไม่สำเร็จ");
    }

    private static String post(String key, String model, byte[] payload) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(baseUrl + model + ":generateContent").openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(120000);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("x-goog-api-key", key);
        try {
            try (OutputStream out = c.getOutputStream()) { out.write(payload); }
            int code = c.getResponseCode();
            InputStream stream = code / 100 == 2 ? c.getInputStream() : c.getErrorStream();
            String text = stream == null ? "" : readAll(stream);
            if (code / 100 != 2) throw new GeminiException(code, describe(code, text));
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String describe(int code, String body) {
        String detail = "";
        try { detail = new JSONObject(body).getJSONObject("error").optString("message", ""); } catch (Exception ignored) { }
        if (code == 429) return "Gemini ถูกเรียกถี่เกินหรือโควตาหมด (429) ลองใหม่ภายหลังหรือเปิด billing";
        if (code == 400 && detail.toLowerCase().contains("api key")) return "Gemini API key ไม่ถูกต้อง";
        if (code == 401 || code == 403) return "Gemini ไม่อนุญาต (" + code + ") ตรวจ API key และสิทธิ์ของโมเดล";
        if (code == 404) return "ไม่พบโมเดล Gemini ที่ตั้งไว้ (404) ลองเปลี่ยนชื่อโมเดลในตั้งค่า";
        return "Gemini ตอบผิดพลาด (" + code + ")" + (detail.isEmpty() ? "" : ": " + detail);
    }

    static List<Item> parse(String response) throws GeminiException, JSONException {
        JSONObject root = new JSONObject(response);
        JSONArray candidates = root.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) {
            String reason = root.optJSONObject("promptFeedback") != null
                    ? root.getJSONObject("promptFeedback").optString("blockReason", "") : "";
            throw new GeminiException(0, "Gemini ไม่ตอบกลับ" + (reason.isEmpty() ? "" : " (ถูกบล็อก: " + reason + ")"));
        }
        JSONArray parts = candidates.getJSONObject(0).optJSONObject("content") == null ? null
                : candidates.getJSONObject(0).getJSONObject("content").optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        if (parts != null) for (int i = 0; i < parts.length(); i++) text.append(parts.getJSONObject(i).optString("text", ""));
        String json = text.toString().trim();
        if (json.startsWith("```")) {                       // tolerate a fenced answer
            json = json.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        if (json.isEmpty()) throw new GeminiException(0, "Gemini ตอบว่างเปล่า");
        JSONArray array = new JSONArray(json);
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i);
            JSONArray b = o == null ? null : o.optJSONArray("box_2d");
            if (b == null || b.length() < 4) continue;
            int ymin = clamp(b.optInt(0)), xmin = clamp(b.optInt(1)), ymax = clamp(b.optInt(2)), xmax = clamp(b.optInt(3));
            if (ymax <= ymin || xmax <= xmin) continue;
            String thai = o.optString("thai", "").trim();
            if (thai.isEmpty()) continue;
            items.add(new Item(ymin, xmin, ymax, xmax, o.optString("source", "").trim(), thai));
        }
        return items;
    }

    private static int clamp(int v) { return Math.max(0, Math.min(1000, v)); }

    private static String readAll(InputStream in) throws IOException {
        try (InputStream s = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16384];
            int n;
            while ((n = s.read(buffer)) > 0) out.write(buffer, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
