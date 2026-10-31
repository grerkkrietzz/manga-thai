package com.mangathai.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns OCR lines into speech-balloon groups. Pure Java (no Android types)
 * so the rules can be tested on a desktop JVM.
 *
 * ML Kit already returns text blocks that roughly match balloons, but it
 * merges overlapping/linked balloons and sometimes splits one balloon. These
 * rules follow the MangaThai desktop grouping:
 *  - split a block into columns that do not overlap horizontally
 *  - split where the line rhythm breaks and the text centre shifts
 *    (overlapping balloons), or where a blank band of several lines appears
 *    (linked balloons)
 *  - merge blocks that continue at normal leading, same size, same centre
 */
public final class Grouper {
    private Grouper() {}

    public static final class Line {
        public final float x, y, w, h;
        public final String text;
        public final int block;

        public Line(float x, float y, float w, float h, String text, int block) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.text = text; this.block = block;
        }

        float cx() { return x + w / 2f; }
        float cy() { return y + h / 2f; }
        float right() { return x + w; }
        float bottom() { return y + h; }
    }

    public static List<List<Line>> group(List<Line> lines) {
        Map<Integer, List<Line>> blocks = new LinkedHashMap<>();
        for (Line line : lines) {
            if (line.w <= 0 || line.h <= 0 || line.text == null || line.text.trim().isEmpty()) continue;
            List<Line> list = blocks.get(line.block);
            if (list == null) { list = new ArrayList<>(); blocks.put(line.block, list); }
            list.add(line);
        }
        List<List<Line>> groups = new ArrayList<>();
        for (List<Line> block : blocks.values()) {
            for (List<Line> column : columns(block)) groups.addAll(split(column));
        }
        groups = merge(groups);
        sortReadingOrder(groups);
        return groups;
    }

    /** Lines of one block that never overlap horizontally belong to different balloons. */
    static List<List<Line>> columns(List<Line> block) {
        int n = block.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < i; j++) {
                Line a = block.get(i), b = block.get(j);
                float overlap = Math.min(a.right(), b.right()) - Math.max(a.x, b.x);
                if (overlap > Math.min(a.w, b.w) * 0.2f) union(parent, i, j);
            }
        }
        Map<Integer, List<Line>> result = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            List<Line> list = result.get(root);
            if (list == null) { list = new ArrayList<>(); result.put(root, list); }
            list.add(block.get(i));
        }
        return new ArrayList<>(result.values());
    }

    static List<List<Line>> split(List<Line> column) {
        List<Line> lines = new ArrayList<>(column);
        lines.sort(Comparator.comparingDouble(l -> l.y));
        List<List<Line>> out = new ArrayList<>();
        List<Line> current = new ArrayList<>();
        current.add(lines.get(0));
        int n = lines.size();
        float[] steps = new float[Math.max(0, n - 1)];
        for (int i = 0; i + 1 < n; i++) steps[i] = lines.get(i + 1).y - lines.get(i).y;
        for (int i = 1; i < n; i++) {
            Line a = lines.get(i - 1), b = lines.get(i);
            float height = Math.min(a.h, b.h);
            float gap = b.y - a.bottom();
            float pitch = medianExcluding(steps, i - 1, height * 1.3f);
            boolean rhythmBreak = (b.y - a.y) >= pitch * 1.6f && gap >= height * 0.8f;
            boolean wide = gap >= height * 1.7f;
            boolean blank = gap >= Math.max(height * 2.5f, pitch * 2.5f);
            float center = center(current), width = width(current);
            boolean shifted = Math.abs(center - b.cx()) > Math.max(height * 1.2f, Math.max(width, b.w) * 0.32f);
            if (blank || ((wide || rhythmBreak) && shifted)) {
                out.add(current);
                current = new ArrayList<>();
            }
            current.add(b);
        }
        out.add(current);
        return out;
    }

    static List<List<Line>> merge(List<List<Line>> groups) {
        List<List<Line>> list = new ArrayList<>();
        for (List<Line> g : groups) {
            List<Line> copy = new ArrayList<>(g);
            copy.sort(Comparator.comparingDouble(l -> l.y));
            list.add(copy);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            outer:
            for (int i = 0; i < list.size(); i++) {
                for (int j = 0; j < list.size(); j++) {
                    if (i == j) continue;
                    Line a = list.get(i).get(list.get(i).size() - 1);
                    Line b = list.get(j).get(0);
                    if (b.y < a.y) continue;
                    float small = Math.min(a.h, b.h);
                    float gap = b.y - a.bottom();
                    float overlap = Math.min(a.right(), b.right()) - Math.max(a.x, b.x);
                    boolean centered = Math.abs(a.cx() - b.cx()) <= Math.max(a.w, b.w) * 0.3f;
                    if (gap >= -0.3f * small && gap <= 0.45f * small && Math.max(a.h, b.h) <= small * 1.6f
                            && overlap >= Math.min(a.w, b.w) * 0.5f && centered) {
                        list.get(i).addAll(list.get(j));
                        list.remove(j);
                        changed = true;
                        break outer;
                    }
                }
            }
        }
        return list;
    }

    static void sortReadingOrder(List<List<Line>> groups) {
        float[] heights = new float[0];
        List<Float> all = new ArrayList<>();
        for (List<Line> g : groups) for (Line l : g) all.add(l.h);
        heights = new float[all.size()];
        for (int i = 0; i < heights.length; i++) heights[i] = all.get(i);
        float band = Math.max(8f, median(heights, 16f) * 3f);
        groups.sort((p, q) -> {
            int rowP = (int) (top(p) / band), rowQ = (int) (top(q) / band);
            if (rowP != rowQ) return Integer.compare(rowP, rowQ);
            return Float.compare(left(p), left(q));
        });
    }

    /** Joins visual lines into one sentence, mending words broken with a hyphen. */
    public static String joinText(List<Line> group) {
        List<Line> lines = new ArrayList<>(group);
        lines.sort((a, b) -> {
            if (Math.abs(a.cy() - b.cy()) < Math.min(a.h, b.h) * 0.55f) return Float.compare(a.x, b.x);
            return Float.compare(a.y, b.y);
        });
        StringBuilder out = new StringBuilder();
        for (Line line : lines) {
            String text = line.text.replaceAll("\\s+", " ").trim();
            if (text.isEmpty()) continue;
            int len = out.length();
            if (len > 1 && out.charAt(len - 1) == '-' && Character.isLetter(out.charAt(len - 2))
                    && Character.isLetter(text.charAt(0))) {
                out.setLength(len - 1);
                out.append(text);
            } else {
                if (len > 0) out.append(' ');
                out.append(text);
            }
        }
        return out.toString();
    }

    public static float[] bounds(List<Line> group) {
        float x = Float.MAX_VALUE, y = Float.MAX_VALUE, r = -Float.MAX_VALUE, b = -Float.MAX_VALUE;
        for (Line l : group) {
            x = Math.min(x, l.x); y = Math.min(y, l.y);
            r = Math.max(r, l.right()); b = Math.max(b, l.bottom());
        }
        return new float[]{x, y, r - x, b - y};
    }

    public static float medianHeight(List<Line> group) {
        float[] h = new float[group.size()];
        for (int i = 0; i < h.length; i++) h[i] = group.get(i).h;
        return median(h, 14f);
    }

    // ---------- helpers ----------
    private static float top(List<Line> g) { float v = Float.MAX_VALUE; for (Line l : g) v = Math.min(v, l.y); return v; }
    private static float left(List<Line> g) { float v = Float.MAX_VALUE; for (Line l : g) v = Math.min(v, l.x); return v; }

    private static float center(List<Line> g) {
        float[] c = new float[g.size()];
        for (int i = 0; i < c.length; i++) c[i] = g.get(i).cx();
        return median(c, 0f);
    }

    private static float width(List<Line> g) {
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (Line l : g) { lo = Math.min(lo, l.x); hi = Math.max(hi, l.right()); }
        return hi - lo;
    }

    static float median(float[] values, float fallback) {
        if (values.length == 0) return fallback;
        float[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int m = copy.length / 2;
        return copy.length % 2 == 1 ? copy[m] : (copy[m - 1] + copy[m]) / 2f;
    }

    private static float medianExcluding(float[] values, int skip, float fallback) {
        if (values.length <= 1) return fallback;
        float[] rest = new float[values.length - 1];
        for (int i = 0, k = 0; i < values.length; i++) if (i != skip) rest[k++] = values[i];
        return median(rest, fallback);
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static void union(int[] parent, int a, int b) { parent[find(parent, a)] = find(parent, b); }
}
