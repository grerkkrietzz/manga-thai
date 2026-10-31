package com.mangathai.mobile;

/**
 * Finds how much empty balloon / caption paper surrounds a block of lettering,
 * so the Thai text can use the whole balloon without spilling over its border.
 * Pure Java (ARGB int pixels) so it can be tested on a desktop JVM.
 */
public final class BalloonFit {
    private BalloonFit() {}

    /** Share of a probed edge line that must look like paper to keep growing. */
    static final float PAPER_SHARE = 0.92f;
    /** Largest per-channel difference from the paper colour still counted as paper. */
    static final int TOLERANCE = 46;

    /**
     * @param pixels ARGB pixels of the page, row-major
     * @param text   lettering rectangle {x, y, w, h}
     * @param paper  paper colour behind the lettering (RGB)
     * @return the room for the translation {x, y, w, h}; never smaller than {@code text}
     */
    public static float[] room(int[] pixels, int width, int height, float[] text, int paper) {
        int left = clamp((int) Math.floor(text[0]), 0, width - 1);
        int top = clamp((int) Math.floor(text[1]), 0, height - 1);
        int right = clamp((int) Math.ceil(text[0] + text[2]) - 1, left, width - 1);
        int bottom = clamp((int) Math.ceil(text[1] + text[3]) - 1, top, height - 1);
        int textW = right - left + 1, textH = bottom - top + 1;
        // Never wander further than the lettering's own size on any side.
        int maxX = Math.max(6, Math.round(textW * 0.9f)), maxY = Math.max(6, Math.round(textH * 0.9f));
        int l = left, t = top, r = right, b = bottom;
        boolean openL = true, openT = true, openR = true, openB = true;
        while (openL || openT || openR || openB) {
            if (openL) {
                if (l > 0 && left - l < maxX && paperShare(pixels, width, l - 1, t, l - 1, b, paper) >= PAPER_SHARE) l--;
                else openL = false;
            }
            if (openR) {
                if (r < width - 1 && r - right < maxX && paperShare(pixels, width, r + 1, t, r + 1, b, paper) >= PAPER_SHARE) r++;
                else openR = false;
            }
            if (openT) {
                if (t > 0 && top - t < maxY && paperShare(pixels, width, l, t - 1, r, t - 1, paper) >= PAPER_SHARE) t--;
                else openT = false;
            }
            if (openB) {
                if (b < height - 1 && b - bottom < maxY && paperShare(pixels, width, l, b + 1, r, b + 1, paper) >= PAPER_SHARE) b++;
                else openB = false;
            }
        }
        // Keep a small margin from the border line that stopped the growth.
        float marginX = Math.min(l < left ? left - l : 0, Math.max(2f, (r - l + 1) * 0.05f));
        float marginX2 = Math.min(r > right ? r - right : 0, Math.max(2f, (r - l + 1) * 0.05f));
        float marginY = Math.min(t < top ? top - t : 0, Math.max(2f, (b - t + 1) * 0.05f));
        float marginY2 = Math.min(b > bottom ? b - bottom : 0, Math.max(2f, (b - t + 1) * 0.05f));
        float x0 = l + marginX, y0 = t + marginY, x1 = r + 1 - marginX2, y1 = b + 1 - marginY2;
        x0 = Math.min(x0, text[0]); y0 = Math.min(y0, text[1]);
        x1 = Math.max(x1, text[0] + text[2]); y1 = Math.max(y1, text[1] + text[3]);
        return new float[]{x0, y0, x1 - x0, y1 - y0};
    }

    static float paperShare(int[] pixels, int width, int x0, int y0, int x1, int y1, int paper) {
        int pr = (paper >> 16) & 255, pg = (paper >> 8) & 255, pb = paper & 255;
        int total = 0, matches = 0;
        for (int y = y0; y <= y1; y++) {
            int row = y * width;
            for (int x = x0; x <= x1; x++) {
                int c = pixels[row + x];
                total++;
                if (Math.abs(((c >> 16) & 255) - pr) <= TOLERANCE && Math.abs(((c >> 8) & 255) - pg) <= TOLERANCE
                        && Math.abs((c & 255) - pb) <= TOLERANCE) matches++;
            }
        }
        return total == 0 ? 0 : matches / (float) total;
    }

    /** Paper colour: median of the lighter part of the block (or darker part on dark paper). */
    public static int paper(int[] pixels, int width, int height, float[] r) {
        int x0 = clamp((int) r[0], 0, width - 1), y0 = clamp((int) r[1], 0, height - 1);
        int x1 = clamp((int) (r[0] + r[2]), x0 + 1, width), y1 = clamp((int) (r[1] + r[3]), y0 + 1, height);
        int step = Math.max(1, (int) Math.sqrt((x1 - x0) * (double) (y1 - y0) / 2500.0));
        java.util.List<int[]> samples = new java.util.ArrayList<>();
        for (int y = y0; y < y1; y += step) {
            for (int x = x0; x < x1; x += step) {
                int c = pixels[y * width + x];
                int red = (c >> 16) & 255, green = (c >> 8) & 255, blue = c & 255;
                samples.add(new int[]{(int) (0.299 * red + 0.587 * green + 0.114 * blue), red, green, blue});
            }
        }
        if (samples.isEmpty()) return 0xffffff;
        samples.sort((a, b) -> Integer.compare(a[0], b[0]));
        int n = samples.size();
        boolean light = samples.get(n / 2)[0] > 110;
        java.util.List<int[]> chosen = light ? samples.subList(n * 2 / 5, n) : samples.subList(0, Math.max(1, n / 5));
        int[] red = new int[chosen.size()], green = new int[chosen.size()], blue = new int[chosen.size()];
        for (int i = 0; i < chosen.size(); i++) { red[i] = chosen.get(i)[1]; green[i] = chosen.get(i)[2]; blue[i] = chosen.get(i)[3]; }
        java.util.Arrays.sort(red); java.util.Arrays.sort(green); java.util.Arrays.sort(blue);
        return (red[red.length / 2] << 16) | (green[green.length / 2] << 8) | blue[blue.length / 2];
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
