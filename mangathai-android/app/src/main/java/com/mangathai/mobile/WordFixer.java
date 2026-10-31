package com.mangathai.mobile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs typical OCR mistakes in comic lettering with an English word list:
 * words run together (TOGET -> TO GET), missing apostrophes (DONT -> DON'T),
 * look-alike letters (SITLATION -> SITUATION, 0 -> O), words broken apart
 * (UNBELIEV ABLE) and "!" read as a letter (BRUSHI -> BRUSH!).
 * Unknown words with no confident fix (names, sounds) are left unchanged.
 * Pure Java so it can be tested on a desktop JVM.
 */
public final class WordFixer {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9|$']+");

    private static final Map<String, String> APOSTROPHE = new HashMap<>();
    static {
        String[] pairs = {
            "dont", "don't", "cant", "can't", "isnt", "isn't", "arent", "aren't", "wasnt", "wasn't",
            "werent", "weren't", "doesnt", "doesn't", "didnt", "didn't", "havent", "haven't",
            "hasnt", "hasn't", "hadnt", "hadn't", "wouldnt", "wouldn't", "couldnt", "couldn't",
            "shouldnt", "shouldn't", "mustnt", "mustn't", "aint", "ain't", "im", "i'm", "ive", "i've",
            "youre", "you're", "youve", "you've", "youll", "you'll", "youd", "you'd", "theyre", "they're",
            "theyve", "they've", "theyll", "they'll", "thats", "that's", "whats", "what's", "hes", "he's",
            "shes", "she's", "weve", "we've", "theres", "there's", "heres", "here's", "wheres", "where's",
            "whos", "who's", "itll", "it'll", "itd", "it'd", "thatll", "that'll", "ya'll", "y'all",
        };
        for (int i = 0; i < pairs.length; i += 2) APOSTROPHE.put(pairs[i], pairs[i + 1]);
    }

    /** Letters that comic-font OCR confuses, both directions. */
    private static final String[][] CONFUSIONS = {
        {"l", "u"}, {"u", "l"}, {"i", "l"}, {"l", "i"}, {"o", "d"}, {"d", "o"}, {"o", "q"}, {"q", "o"},
        {"c", "g"}, {"g", "c"}, {"c", "e"}, {"e", "c"}, {"h", "n"}, {"n", "h"}, {"n", "m"}, {"m", "n"},
        {"v", "y"}, {"y", "v"}, {"u", "v"}, {"v", "u"}, {"b", "h"}, {"h", "b"}, {"e", "f"}, {"f", "e"},
        {"o", "u"}, {"u", "o"}, {"a", "o"}, {"o", "a"}, {"rn", "m"}, {"m", "rn"}, {"cl", "d"}, {"vv", "w"},
        {"i", "t"}, {"t", "i"}, {"j", "i"}, {"r", "n"}, {"p", "b"}, {"s", "e"}, {"e", "s"}, {"k", "x"},
        {"p", "f"}, {"f", "p"}, {"w", "m"}, {"m", "w"}, {"f", "t"}, {"t", "f"}, {"r", "p"}, {"p", "r"},
    };

    private final Set<String> words;

    public WordFixer(Set<String> words) {
        this.words = words;
    }

    /** Fixes every token of a raw OCR string (case is preserved per token). */
    public String fix(String text) {
        List<String> parts = new ArrayList<>();   // alternating separators and tokens
        List<Boolean> isToken = new ArrayList<>();
        Matcher m = TOKEN.matcher(text);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) { parts.add(text.substring(last, m.start())); isToken.add(false); }
            parts.add(m.group()); isToken.add(true);
            last = m.end();
        }
        if (last < text.length()) { parts.add(text.substring(last)); isToken.add(false); }

        // Join words broken in two: "UNBELIEV ABLE".
        for (int i = 0; i + 2 < parts.size(); i++) {
            if (!isToken.get(i) || !isToken.get(i + 2) || !parts.get(i + 1).trim().isEmpty()) continue;
            String a = parts.get(i), b = parts.get(i + 2);
            if (!letters(a) || !letters(b)) continue;
            boolean aWord = isWord(lower(a)), bWord = isWord(lower(b));
            // Both pieces unknown, or one unknown piece that only makes sense with its neighbour
            // (APIER WARDS -> AFTERWARDS). Two real words are never joined.
            if (aWord && bWord) continue;
            if ((aWord || bWord) && (a.length() + b.length()) < 8) continue;
            String joined = lower(a + b);
            String repairedJoin = isWord(joined) ? joined
                    : (joined.length() >= 7 ? singleSubstitution(joined) : null);
            if (repairedJoin == null && joined.length() >= 8) repairedJoin = twoSubstitutions(joined);
            // A known word next to an unknown one only joins if the unknown one cannot be fixed alone.
            if (repairedJoin != null && (aWord || bWord)) {
                String alone = repair(lower(aWord ? b : a));
                if (alone != null && !alone.contains(" ")) repairedJoin = null;  // a real one-word fix wins
            }
            if (repairedJoin != null) {
                if (!repairedJoin.equals(joined)) { a = restoreCase(a + b, repairedJoin); b = ""; }
                parts.set(i, a + b);
                parts.remove(i + 1); isToken.remove(i + 1);   // the space
                parts.remove(i + 1); isToken.remove(i + 1);   // the second piece
                i--;   // look at the joined word again: it may join the next piece too
            }
        }

        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String p = parts.get(i);
            out.append(isToken.get(i) ? fixToken(p) : p);
        }
        return out.toString();
    }

    /** Share of alphabetic tokens that are known words: a quality score for OCR text. */
    public float wordRatio(String text) {
        Matcher m = TOKEN.matcher(text);
        int total = 0, known = 0;
        while (m.find()) {
            String t = lower(m.group()).replaceAll("^'+|'+$", "");
            if (t.length() < 2 || !t.matches(".*[a-z].*")) continue;
            total++;
            if (isWord(t)) known++;
        }
        return total == 0 ? 0f : known / (float) total;
    }

    /** Quality score for comparing two OCR readings: known words count, unknown ones cost. */
    public float score(String text) {
        Matcher m = TOKEN.matcher(fix(text));
        float value = 0;
        while (m.find()) {
            String t = lower(m.group()).replaceAll("^'+|'+$", "");
            if (!t.matches(".*[a-z].*")) continue;
            value += isWord(t) ? 1f : -0.5f;
        }
        return value;
    }

    String fixToken(String token) {
        String low = lower(token);
        String core = low.replaceAll("^'+|'+$", "");
        if (core.matches("[0o]{2,3}") && !core.equals("oo")) return "...";
        if (core.isEmpty() || isWord(core) || !core.matches(".*[a-z].*")) return token;
        String fixed = repair(core);
        if (fixed == null) return token;
        String lead = low.startsWith("'") && !fixed.startsWith("'") ? "'" : "";
        String trail = low.endsWith("'") && core.length() < low.length() - lead.length() ? "'" : "";
        return restoreCase(token, lead + fixed + trail);
    }

    private String repair(String w) {
        // 0. A number glued to a word: 155POINTS -> 155 POINTS, 2HOURS -> 2 HOURS.
        Matcher glued = Pattern.compile("^([0-9]+)([a-z']{2,})$|^([a-z']{2,})([0-9]+)$").matcher(w);
        if (glued.matches()) {
            String number = glued.group(1) != null ? glued.group(1) : glued.group(4);
            String word = glued.group(1) != null ? glued.group(2) : glued.group(3);
            if (isWord(word) || word.equals("st") || word.equals("nd") || word.equals("rd") || word.equals("th")) {
                if (word.matches("st|nd|rd|th")) return null;   // 3RD stays as it is
                return glued.group(1) != null ? number + " " + word : word + " " + number;
            }
        }
        if (w.matches(".*[0-9].*") && !w.matches(".*[a-z][0-9|$]+[a-z].*") && !w.matches("[0-9|$][a-z]{2,}|[a-z]{2,}[0-9|$]")) {
            return null;  // real numbers are not letters in disguise
        }
        // 1. Digits and symbols that stand for letters: 0->o, 1->i, 5->s, |->i.
        if (w.matches(".*[0-9|$].*") && w.replaceAll("[^a-z]", "").length() >= w.length() / 2) {
            String letters = w.replace('0', 'o').replace('1', 'i').replace('5', 's').replace('8', 'b')
                    .replace('|', 'i').replace('$', 's');
            if (isWord(letters)) return letters;
            String alt = letters.replace('i', 'l');
            if (isWord(alt)) return alt;
            w = letters;
        }
        // 2. Missing apostrophe: DONT -> DON'T.
        String apostrophe = APOSTROPHE.get(w);
        if (apostrophe != null) return apostrophe;
        // 3a. "..." read as letters: MANSIONBOF -> MANSION...!, PRESIDENT00 handled as a token.
        if (w.length() >= 6) {
            for (int cut = w.length() - 1; cut >= Math.max(4, w.length() - 4); cut--) {
                String tail = w.substring(cut);
                if (!tail.matches("[bo0f.!il|]+")) break;
                String head = w.substring(0, cut);
                String fixedHead = isWord(head) ? head : singleSubstitution(head);
                if (fixedHead != null && fixedHead.length() >= 4) {
                    return fixedHead + (tail.matches(".*[f!il|]$") ? "...!" : "...");
                }
            }
        }
        // 3. "!" read as a final letter: BRUSHI -> BRUSH!
        if (w.length() >= 4 && (w.endsWith("i") || w.endsWith("l"))) {
            String base = w.substring(0, w.length() - 1);
            if (isWord(base) && base.length() >= 3) return base + "!";
        }
        // 4. Words run together: TOGET -> TO GET, YOUISNT -> YOU ISN'T.
        String split = segment(w);
        // 5. One look-alike letter wrong: SITLATION -> SITUATION (two in long words).
        String substituted = w.length() >= 4 ? singleSubstitution(w) : null;
        if (substituted == null && split == null && w.length() >= 8) substituted = twoSubstitutions(w);
        // A split into two plain words beats a guessed letter; otherwise trust the letter fix.
        if (split != null && (substituted == null || split.split(" ").length == 2)) return split;
        if (substituted != null) return substituted;
        // 6. One stray extra letter in a long word.
        if (w.length() >= 6) {
            for (int i = 0; i < w.length(); i++) {
                if (!Character.isLetter(w.charAt(i))) continue;
                String candidate = w.substring(0, i) + w.substring(i + 1);
                if (candidate.length() >= 5 && isWord(candidate)) return candidate;
            }
        }
        return null;
    }

    String singleSubstitution(String w) {
        for (String[] pair : CONFUSIONS) {
            int from = 0;
            while ((from = w.indexOf(pair[0], from)) >= 0) {
                String candidate = w.substring(0, from) + pair[1] + w.substring(from + pair[0].length());
                if (isWord(candidate)) return candidate;
                from++;
            }
        }
        return null;
    }

    /** Two look-alike letters wrong, only for long words where chance matches are rare. */
    String twoSubstitutions(String w) {
        for (String[] pair : CONFUSIONS) {
            int from = 0;
            while ((from = w.indexOf(pair[0], from)) >= 0) {
                String once = w.substring(0, from) + pair[1] + w.substring(from + pair[0].length());
                String twice = singleSubstitution(once);
                if (twice != null && !twice.equals(w)) return twice;
                from++;
            }
        }
        return null;
    }

    /** Splits into 2-4 known words; single letters only "a" and "i". Fewest pieces wins. */
    String segment(String w) {
        String s = w;
        if (s.length() < 4 || s.length() > 30 || !s.matches("[a-z][a-z']*[a-z]")) return null;
        int n = s.length();
        int[] best = new int[n + 1];
        int[] square = new int[n + 1];   // tie-break: prefer longer pieces ("lost in", not "los tin")
        int[] prev = new int[n + 1];
        java.util.Arrays.fill(best, Integer.MAX_VALUE);
        best[0] = 0;
        for (int end = 1; end <= n; end++) {
            for (int start = Math.max(0, end - 20); start < end; start++) {
                if (best[start] == Integer.MAX_VALUE) continue;
                String piece = s.substring(start, end);
                if (piece.startsWith("'") || !piece(piece)) continue;
                int pieces = best[start] + 1, sq = square[start] + piece.length() * piece.length();
                if (pieces < best[end] || (pieces == best[end] && sq > square[end])) {
                    best[end] = pieces; square[end] = sq; prev[end] = start;
                }
            }
        }
        if (best[n] == Integer.MAX_VALUE || best[n] < 2 || best[n] > 4) return null;
        List<String> pieces = new ArrayList<>();
        for (int end = n; end > 0; end = prev[end]) pieces.add(0, s.substring(prev[end], end));
        StringBuilder out = new StringBuilder();
        for (String p : pieces) {
            if (out.length() > 0) out.append(' ');
            String fixed = APOSTROPHE.get(p);
            out.append(fixed != null ? fixed : p);
        }
        return out.toString();
    }

    private boolean piece(String p) {
        if (p.length() == 1) return p.equals("a") || p.equals("i");
        if (APOSTROPHE.containsKey(p)) return true;
        if (p.length() == 2) return TWO_LETTER.contains(p);
        if (p.length() == 3) return THREE_LETTER.contains(p);
        return isWord(p);
    }

    /** Common two-letter words only: rare dictionary ones cause bad splits. */
    private static final Set<String> TWO_LETTER = new java.util.HashSet<>(java.util.Arrays.asList(
            "am", "an", "as", "at", "be", "by", "do", "go", "he", "if", "in", "is", "it", "me", "my",
            "no", "of", "oh", "ok", "on", "or", "so", "to", "up", "us", "we"));

    /** Common three-letter words: rare ones (lat, ion, sip) cause bad splits. */
    private static final Set<String> THREE_LETTER = new java.util.HashSet<>(java.util.Arrays.asList(
            "the", "and", "for", "are", "but", "not", "you", "all", "any", "can", "had", "her", "was",
            "one", "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old",
            "see", "two", "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too", "use",
            "dad", "mom", "yes", "yet", "why", "off", "own", "run", "big", "end", "far", "few", "got",
            "may", "men", "eat", "ask", "try", "bad", "lot", "car", "die", "dog", "god", "kid", "kill",
            "war", "win", "set", "sun", "top", "hit", "cut", "sit", "lie", "job", "law", "air", "art",
            "bed", "box", "eye", "gun", "hot", "key", "leg", "arm", "map", "red", "sea", "sky", "tea",
            "ten", "six", "act", "add", "age", "ago", "bit", "buy", "fly", "fun", "guy", "low", "pay",
            "pay", "per", "sad", "son", "via", "won", "yep", "hey", "wow", "huh", "ugh", "aim", "lost"));

    public boolean isWord(String w) {
        if (w.isEmpty()) return false;
        if (words.contains(w)) return true;
        if (w.equals("i") || w.equals("a")) return true;
        // Contractions: he's, we'll, they're, I'd, shouldn't.
        int quote = w.indexOf('\'');
        if (quote > 0) {
            String base = w.substring(0, quote), tail = w.substring(quote);
            if (tail.equals("n't")) return words.contains(base) || base.equals("ca") || base.equals("wo") || base.equals("ai");
            if (base.endsWith("n") && tail.equals("'t")) return words.contains(base.substring(0, base.length() - 1));
            if (tail.equals("'s") || tail.equals("'ll") || tail.equals("'re") || tail.equals("'ve")
                    || tail.equals("'d") || tail.equals("'m") || tail.equals("'")) return isWord(base);
            return false;
        }
        // The bundled list already holds every inflected form (quest -> requests),
        // so no suffix guessing here: guessing made OCR junk like APIER look real.
        return false;
    }

    private static boolean letters(String s) { return s.matches("[A-Za-z]+"); }
    private static String lower(String s) { return s.toLowerCase(Locale.ROOT); }

    private static String restoreCase(String original, String fixed) {
        int upper = 0, letters = 0;
        for (char c : original.toCharArray()) if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) upper++; }
        if (letters > 0 && upper * 2 > letters) return fixed.toUpperCase(Locale.ROOT);
        if (!original.isEmpty() && Character.isUpperCase(original.charAt(0)) && !fixed.isEmpty())
            return Character.toUpperCase(fixed.charAt(0)) + fixed.substring(1);
        return fixed;
    }
}
