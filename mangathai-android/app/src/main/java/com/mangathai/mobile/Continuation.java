package com.mangathai.mobile;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Joins balloons that carry one sentence ("THIS LUCY THAT THE CORPSE CHOSE
 * IS... TRULY..." + "MY GODDESS...") so the translator sees the whole
 * sentence, then shares the Thai back out. Pure Java for desktop testing.
 */
public final class Continuation {
    private Continuation() {}

    public static final int MAX_CHAIN = 3;

    /** A balloon's lettering box and whether its sentence carries on. */
    public static final class Box {
        final float x, y, w, h, letter;
        final boolean continues;

        /** @param letter typical letter height inside the balloon */
        public Box(float x, float y, float w, float h, float letter, boolean continues) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.letter = letter; this.continues = continues;
        }
    }

    /**
     * Groups balloons into chains, each in reading order. Only a balloon right
     * below an unfinished one is joined to it: side-by-side balloons are left
     * alone because left-to-right and right-to-left pages cannot be told apart.
     * Every balloon is in exactly one chain; most chains have one balloon.
     */
    public static List<List<Integer>> chains(List<Box> boxes) {
        int n = boxes.size();
        int[] next = new int[n];
        boolean[] taken = new boolean[n];
        java.util.Arrays.fill(next, -1);
        for (int i = 0; i < n; i++) {
            Box a = boxes.get(i);
            if (!a.continues) continue;
            int best = -1;
            float bestGap = Float.MAX_VALUE;
            for (int j = 0; j < n; j++) {
                if (j == i || taken[j]) continue;
                Box b = boxes.get(j);
                // Must start just below: a few lines' height, not the next panel down.
                float gap = b.y - (a.y + a.h);
                if (gap < -0.25f * a.h || gap > 5f * Math.max(a.letter, b.letter)) continue;
                // Staggered balloons are common, so touching columns are enough.
                float overlap = Math.min(a.x + a.w, b.x + b.w) - Math.max(a.x, b.x);
                if (overlap < -0.15f * Math.min(a.w, b.w)) continue;
                if (gap < bestGap) { best = j; bestGap = gap; }
            }
            if (best >= 0) { next[i] = best; taken[best] = true; }
        }
        List<List<Integer>> out = new ArrayList<>();
        boolean[] used = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (used[i] || taken[i]) continue;      // chain heads only
            List<Integer> chain = new ArrayList<>();
            for (int k = i; k >= 0 && !used[k]; k = next[k]) {
                if (chain.size() == MAX_CHAIN) break;
                chain.add(k);
                used[k] = true;
            }
            out.add(chain);
        }
        for (int i = 0; i < n; i++) if (!used[i]) { List<Integer> one = new ArrayList<>(); one.add(i); out.add(one); }
        return out;
    }

    /**
     * Cuts a Thai translation into pieces whose lengths follow the English
     * pieces, at Thai word boundaries (Thai has no spaces between words).
     *
     * @return the pieces, or null when no sensible cut exists
     */
    public static String[] split(String thai, int[] weights) {
        int parts = weights.length;
        String text = thai.trim();
        if (parts == 1) return new String[]{text};
        List<Integer> cuts = new ArrayList<>();
        BreakIterator words = BreakIterator.getWordInstance(Locale.forLanguageTag("th"));
        words.setText(text);
        for (int b = words.first(); b != BreakIterator.DONE; b = words.next()) {
            if (b > 0 && b < text.length()) cuts.add(b);
        }
        if (cuts.size() < parts - 1) return null;
        int total = 0;
        for (int w : weights) total += Math.max(1, w);
        String[] out = new String[parts];
        int start = 0, sum = 0, lastCut = 0;
        for (int p = 0; p < parts - 1; p++) {
            sum += Math.max(1, weights[p]);
            int target = Math.round(text.length() * sum / (float) total);
            int best = -1;
            for (int c : cuts) {
                if (c <= lastCut) continue;
                if (best < 0 || Math.abs(c - target) < Math.abs(best - target)) best = c;
            }
            if (best < 0) return null;
            out[p] = text.substring(start, best).trim();
            start = best;
            lastCut = best;
        }
        out[parts - 1] = text.substring(start).trim();
        for (String piece : out) {
            int letters = 0;
            for (int i = 0; i < piece.length(); i++) if (Character.isLetter(piece.charAt(i))) letters++;
            if (letters < 2) return null;   // a near-empty balloon reads worse than separate translations
        }
        return out;
    }
}
