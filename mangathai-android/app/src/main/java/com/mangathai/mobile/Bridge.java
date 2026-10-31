package com.mangathai.mobile;

import android.content.SharedPreferences;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JavaScript interface used by overlay.js. Work runs on one background
 * thread in priority order: the page on screen (0) before nearby pages (1)
 * before whole-chapter prefetch (2).
 */
public final class Bridge {
    public interface Output {
        void result(String requestId, boolean ok, String payload);
        void status(String text);
    }

    private static final int MAX_BYTES = 30 * 1024 * 1024;

    private static final class Job implements Comparable<Job> {
        final String id;
        final byte[] data;
        final String url;
        final String referer;
        final int priority;
        final long order;

        Job(String id, byte[] data, String url, String referer, int priority, long order) {
            this.id = id; this.data = data; this.url = url; this.referer = referer;
            this.priority = priority; this.order = order;
        }

        @Override
        public int compareTo(Job other) {
            if (priority != other.priority) return Integer.compare(priority, other.priority);
            return Long.compare(order, other.order);
        }
    }

    private final Engine engine;
    private final Output output;
    private final PriorityBlockingQueue<Job> queue = new PriorityBlockingQueue<>();
    private final AtomicLong counter = new AtomicLong();
    private final Thread worker;
    private volatile boolean running = true;

    private final SharedPreferences prefs;

    public Bridge(Engine engine, Output output, SharedPreferences prefs) {
        this.engine = engine;
        this.output = output;
        this.prefs = prefs;
        worker = new Thread(this::loop, "mangathai-worker");
        worker.setDaemon(true);
        worker.start();
    }

    @JavascriptInterface
    public void translate(String requestId, String base64, int priority) {
        byte[] data;
        try {
            data = Base64.decode(base64, Base64.DEFAULT);
        } catch (IllegalArgumentException e) {
            output.result(requestId, false, "ข้อมูลภาพไม่ถูกต้อง");
            return;
        }
        queue.add(new Job(requestId, data, null, null, clamp(priority), counter.incrementAndGet()));
    }

    @JavascriptInterface
    public void translateUrl(String requestId, String url, int priority, String referer) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
            output.result(requestId, false, "ดึงภาพนี้ไม่ได้");
            return;
        }
        queue.add(new Job(requestId, null, url, referer, clamp(priority), counter.incrementAndGet()));
    }

    /** Reader's text size for the translation (1 = fill the balloon). */
    @JavascriptInterface
    public float fontScale() {
        return prefs.getFloat("fontScale", 1f);
    }

    @JavascriptInterface
    public void setFontScale(float value) {
        prefs.edit().putFloat("fontScale", Math.max(0.5f, Math.min(2f, value))).apply();
    }

    @JavascriptInterface
    public int pending() {
        return queue.size();
    }

    private void loop() {
        while (running) {
            Job job;
            try {
                job = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            try {
                byte[] data = job.data != null ? job.data : download(job.url, job.referer);
                engine.prepare(output::status);
                // Plain progress is too chatty; only notices that start with "!" reach the reader.
                String result = engine.process(data, text -> { if (text.startsWith("!")) output.status(text.substring(1)); });
                output.result(job.id, true, result);
            } catch (Throwable error) {
                String message = error.getMessage();
                if (message == null || message.isEmpty()) message = error.getClass().getSimpleName();
                if (error instanceof java.util.concurrent.ExecutionException && error.getCause() != null
                        && error.getCause().getMessage() != null) {
                    message = error.getCause().getMessage();
                }
                output.result(job.id, false, message);
            }
        }
    }

    private static byte[] download(String address, String referer) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) MangaThai/1.0");
        if (referer != null && referer.startsWith("http")) connection.setRequestProperty("Referer", referer);
        try {
            int code = connection.getResponseCode();
            if (code / 100 != 2) throw new IOException("ดึงภาพไม่ได้ " + code);
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[65536];
                int n, total = 0;
                while ((n = in.read(buffer)) > 0) {
                    total += n;
                    if (total > MAX_BYTES) throw new IOException("ภาพใหญ่เกินไป");
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }

    private static int clamp(int priority) { return Math.max(0, Math.min(9, priority)); }

    public void close() {
        running = false;
        worker.interrupt();
        queue.clear();
    }
}
