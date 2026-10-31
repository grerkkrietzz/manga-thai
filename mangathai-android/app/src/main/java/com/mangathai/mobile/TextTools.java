package com.mangathai.mobile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
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

    // ---------- preparing text for a sentence translator ----------

    /** Text ready for the translator, plus the "..." to put back around the Thai. */
    public static final class Prepared {
        public final String text;
        public final String prefix;
        public final String suffix;
        /** The sentence seems to carry on in the next balloon ("...", "--", "," or no end mark). */
        public final boolean continues;

        Prepared(String text, String prefix, String suffix, boolean continues) {
            this.text = text; this.prefix = prefix; this.suffix = suffix; this.continues = continues;
        }

        /** Thai for this balloon alone, with its leading / trailing "..." restored. */
        public String wrap(String thai) {
            String t = thai.trim();
            if (t.isEmpty()) return t;
            return prefix + t + (suffix.isEmpty() || t.endsWith("…") || t.endsWith("...") ? "" : suffix);
        }
    }

    private static final String[][] SLANG = {
            {"yo'", "your"}, {"'em", "them"}, {"gonna", "going to"}, {"wanna", "want to"},
            {"gotta", "got to"}, {"ain't", "is not"}, {"y'know", "you know"}, {"lemme", "let me"},
            {"gimme", "give me"}, {"dunno", "don't know"}, {"c'mon", "come on"}, {"'cause", "because"},
            {"outta", "out of"}, {"kinda", "kind of"}, {"sorta", "sort of"}, {"ya", "you"},
            {"'til", "until"}, {"lotta", "lot of"},
    };
    private static final Pattern G_DROP = Pattern.compile("(?<![A-Za-z'])([A-Za-z]{2,})in'(?![A-Za-z])");
    private static final Pattern EMPHASIS = Pattern.compile("(?<![A-Za-z])'((?:[^']|'(?=[A-Za-z]))+?)'(?![A-Za-z])");
    private static final Pattern STUTTER = Pattern.compile("(?i)(?<![A-Za-z])([a-z]{1,2})-(?=\\1)");

    /**
     * Rewrites one balloon (already {@link #clean}ed) so a sentence translator
     * reads it as plain English: no emphasis quotes, no dropped g's or slang,
     * no stutters, and no "..." that would cut the sentence in two.
     *
     * @param isWord tells whether a lower-case word is real English
     */
    public static Prepared prepare(String text, Predicate<String> isWord) {
        String t = text.trim();
        // Slang first: its apostrophes would otherwise be taken for quote marks.
        for (String[] pair : SLANG) {
            Pattern p = Pattern.compile("(?i)(?<![A-Za-z'])" + Pattern.quote(pair[0]) + "(?![A-Za-z'])");
            Matcher m = p.matcher(t);
            StringBuffer out = new StringBuffer();
            while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement(sameCase(m.group(), pair[1])));
            m.appendTail(out);
            t = out.toString();
        }
        Matcher g = G_DROP.matcher(t);
        StringBuffer dropped = new StringBuffer();
        while (g.find()) {
            String full = g.group(1) + "ing";
            String replacement = isWord.test(full.toLowerCase(Locale.ROOT)) ? full : g.group();
            g.appendReplacement(dropped, Matcher.quoteReplacement(replacement));
        }
        g.appendTail(dropped);
        t = dropped.toString();

        t = EMPHASIS.matcher(t).replaceAll("$1");
        t = t.replace("\"", "");
        t = STUTTER.matcher(t).replaceAll("");
        t = t.replace("~", "").replaceAll("!{2,}", "!").replaceAll("\\?[?!]+|![?!]+", "?");

        // Unify ellipses, then keep the ones at either end out of the translator.
        t = t.replaceAll("\\.\\s*\\.(\\s*\\.)+|…+", "…").replaceAll("\\s*(--+|—)\\s*$", "…").trim();
        String prefix = "", suffix = "";
        if (t.startsWith("…")) { prefix = "..."; t = t.substring(1).trim(); }
        if (t.endsWith("…")) { suffix = "..."; t = t.substring(0, t.length() - 1).trim(); }
        // "...?" / "...!" at the end: keep the mark for the translator, the dots outside.
        Matcher end = Pattern.compile("…\\s*([?!])$").matcher(t);
        if (end.find()) { suffix = "..."; t = t.substring(0, end.start()).trim() + end.group(1); }
        // A "..." inside a sentence is a pause, not a full stop.
        // clean() capitalised the word after it as a new sentence: undo that (not for "I").
        Matcher pause = Pattern.compile("\\s*…\\s*([A-Za-z0-9])(?=([A-Za-z']*))").matcher(t);
        StringBuffer joined = new StringBuffer();
        while (pause.find()) {
            String word = pause.group(1) + pause.group(2);
            boolean keep = word.equals("I") || word.startsWith("I'");
            String first = keep ? pause.group(1) : pause.group(1).toLowerCase(Locale.ROOT);
            pause.appendReplacement(joined, Matcher.quoteReplacement(", " + first));
        }
        pause.appendTail(joined);
        t = joined.toString().replace("…", "");
        t = t.replaceAll("\\s+", " ").replaceAll("\\s+([,.!?])", "$1").replaceAll(",\\s*,", ",")
                .replaceAll("^[,\\s]+|[,\\s]+$", "").replaceAll(",([.!?])$", "$1").trim();
        t = capitalise(t);

        boolean continues = !suffix.isEmpty() || text.trim().matches("(?s).*(--+|—|,)['\"]?$")
                || !t.matches("(?s).*[.!?]['\"]?$");
        return new Prepared(t, prefix, suffix, continues);
    }

    private static String sameCase(String original, String replacement) {
        for (int i = 0; i < original.length(); i++) {
            char c = original.charAt(i);
            if (Character.isLetter(c)) {
                return Character.isUpperCase(c)
                        ? Character.toUpperCase(replacement.charAt(0)) + replacement.substring(1) : replacement;
            }
        }
        return replacement;
    }

    private static String capitalise(String t) {
        for (int i = 0; i < t.length(); i++) {
            if (Character.isLetter(t.charAt(i))) {
                return t.substring(0, i) + Character.toUpperCase(t.charAt(i)) + t.substring(i + 1);
            }
        }
        return t;
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
