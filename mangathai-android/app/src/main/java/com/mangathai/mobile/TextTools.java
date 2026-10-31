package com.mangathai.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Text clean-up and user word rules. Pure Java for desktop testing. */
public final class TextTools {
    private TextTools() {}

    /** Cleans manga OCR text so the translator sees normal English sentences. */
    public static String clean(String raw) {
        String t = raw.replace('\n', ' ').replace('|', 'I');
        t = t.replace('\u2019', '\'').replace('\u2018', '\'').replace('\u201c', '"').replace('\u201d', '"');
        t = t.replaceAll("([A-Za-z])-\\s+([A-Za-z])", "$1$2");
        // Balloon tails and border strokes read as slashes: "STAND / ABILITY".
        t = t.replaceAll("(?<=\\s|^)[/\\\\]+(?=\\s|$)", " ").replaceAll("(?<=[A-Za-z])/(?=\\s|$)", "");
        t = t.replaceAll("\\s+", " ").trim();
        t = t.replaceAll("\\s+([,.!?\u2026])", "$1");
        t = t.replaceAll("([.!?\u2026])(?=[A-Za-z])", "$1 ");
        int letters = 0, upper = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) upper++; }
        }
        // Manga lettering is all caps; sentence case translates much better.
        if (letters > 0 && upper / (float) letters > 0.7f) {
            t = t.toLowerCase(Locale.ROOT);
            StringBuilder b = new StringBuilder(t);
            boolean start = true;
            for (int i = 0; i < b.length(); i++) {
                char c = b.charAt(i);
                if (start && Character.isLetter(c)) { b.setCharAt(i, Character.toUpperCase(c)); start = false; }
                else if (c == '.' || c == '!' || c == '?' || c == '\u2026') start = true;
                else if (Character.isLetterOrDigit(c)) start = false;
            }
            t = b.toString().replaceAll("\\bi\\b", "I").replaceAll("\\bi'", "I'");
        }
        return t;
    }

    /** True when OCR text is worth translating (letters, not decoration). */
    public static boolean worthTranslating(String text) {
        int letters = 0;
        for (int i = 0; i < text.length(); i++) if (Character.isLetter(text.charAt(i))) letters++;
        return letters >= 2;
    }

    /**
     * User rules, one per line: "source=target" (also "source -> target").
     * Rules whose source has Latin letters fix names before translation
     * (Gyro=ไจโร). Thai rules replace words after translation (คุณ=แก).
     */
    public static final class Rules {
        final List<String[]> before = new ArrayList<>();
        final List<String[]> after = new ArrayList<>();

        public static Rules parse(String text) {
            Rules rules = new Rules();
            if (text == null) return rules;
            for (String line : text.split("\\r?\\n")) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] parts = line.contains("->") ? line.split("->", 2) : line.split("=", 2);
                if (parts.length != 2) continue;
                String from = parts[0].trim(), to = parts[1].trim();
                if (from.isEmpty()) continue;
                if (from.matches(".*[A-Za-z].*")) rules.before.add(new String[]{from, to});
                else rules.after.add(new String[]{from, to});
            }
            return rules;
        }

        /** The name rules as "English = Thai" lines, for an AI translator's prompt. */
        public String glossary() {
            StringBuilder b = new StringBuilder();
            for (String[] rule : before) b.append(rule[0]).append(" = ").append(rule[1]).append('\n');
            return b.toString();
        }

        public String applyBefore(String english) {
            String out = english;
            for (String[] rule : before) {
                Pattern p = Pattern.compile("(?<![A-Za-z])" + Pattern.quote(rule[0]) + "(?![A-Za-z])",
                        Pattern.CASE_INSENSITIVE);
                out = p.matcher(out).replaceAll(Matcher.quoteReplacement(rule[1]));
            }
            return out;
        }

        public String applyAfter(String thai) {
            String out = thai;
            for (String[] rule : after) out = out.replace(rule[0], rule[1]);
            return out;
        }
    }
}
