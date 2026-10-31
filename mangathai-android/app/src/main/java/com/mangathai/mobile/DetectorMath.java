package com.mangathai.mobile;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java maths for the comic-text-detector model (1024x1024 input,
 * "blk" output = rows of cx, cy, w, h, objectness, class scores...).
 * No Android classes here so it can be tested on a desktop JVM.
 */
public final class DetectorMath {
    private DetectorMath() {}

    public static final int SIZE = 1024;
    public static final float CONFIDENCE = 0.5f;
    public static final float NMS_IOU = 0.35f;
    /** A text block bigger than this share of the page is a false alarm. */
    public static final float MAX_PAGE_SHARE = 0.4f;

    /** Letterbox parameters: scale and the padding added on the left/top. */
    public static final class Letterbox {
        public final int newWidth, newHeight, padLeft, padTop;
        public final float scale;

        public Letterbox(int width, int height) {
            scale = Math.min(SIZE / (float) width, SIZE / (float) height);
            newWidth = Math.max(1, Math.round(width * scale));
            newHeight = Math.max(1, Math.round(height * scale));
            padLeft = (int) Math.round((SIZE - newWidth) / 2.0 - 0.1);
            padTop = (int) Math.round((SIZE - newHeight) / 2.0 - 0.1);
        }
    }

    /**
     * @param rows   model output, one row per candidate (at least 6 columns)
     * @return boxes {left, top, right, bottom, confidence} in page pixels, best first
     */
    public static List<float[]> decode(float[][] rows, int width, int height) {
        Letterbox lb = new Letterbox(width, height);
        // The model output is in letterboxed pixels; the pad used by the
        // reference pipeline is the exact centre offset, not the rounded one.
        float dw = (SIZE - lb.newWidth) / 2f, dh = (SIZE - lb.newHeight) / 2f;
        List<float[]> candidates = new ArrayList<>();
        for (float[] row : rows) {
            float best = 0;
            for (int c = 5; c < row.length; c++) best = Math.max(best, row[c]);
            float conf = row[4] * best;
            if (conf < CONFIDENCE) continue;
            float x0 = (row[0] - row[2] / 2f - dw) / lb.scale, y0 = (row[1] - row[3] / 2f - dh) / lb.scale;
            float x1 = (row[0] + row[2] / 2f - dw) / lb.scale, y1 = (row[1] + row[3] / 2f - dh) / lb.scale;
            x0 = Math.max(0, x0); y0 = Math.max(0, y0);
            x1 = Math.min(width, x1); y1 = Math.min(height, y1);
            if (x1 - x0 < 4 || y1 - y0 < 4) continue;
            if ((x1 - x0) * (y1 - y0) > MAX_PAGE_SHARE * width * (double) height) continue;
            candidates.add(new float[]{x0, y0, x1, y1, conf});
        }
        candidates.sort((a, b) -> Float.compare(b[4], a[4]));
        List<float[]> kept = new ArrayList<>();
        for (float[] box : candidates) {
            boolean overlaps = false;
            for (float[] k : kept) if (iou(box, k) > NMS_IOU) { overlaps = true; break; }
            if (!overlaps) kept.add(box);
        }
        return kept;
    }

    static float iou(float[] a, float[] b) {
        float w = Math.min(a[2], b[2]) - Math.max(a[0], b[0]);
        float h = Math.min(a[3], b[3]) - Math.max(a[1], b[1]);
        if (w <= 0 || h <= 0) return 0;
        float inter = w * h;
        float areaA = (a[2] - a[0]) * (a[3] - a[1]), areaB = (b[2] - b[0]) * (b[3] - b[1]);
        return inter / (areaA + areaB - inter);
    }
}
