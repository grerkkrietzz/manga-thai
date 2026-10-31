package com.mangathai.mobile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/** Lettering taken from real pages, as OCR would read it. */
public class TextToolsTest {
    private static Set<String> words;

    @BeforeClass
    public static void loadWords() throws Exception {
        Path path = Paths.get("src/main/assets/words.txt");
        if (!Files.exists(path)) path = Paths.get("app/src/main/assets/words.txt");
        words = new HashSet<>(Files.readAllLines(path, StandardCharsets.UTF_8));
    }

    private static TextTools.Prepared prep(String ocr) {
        return TextTools.prepare(TextTools.clean(ocr), words::contains);
    }

    @Test public void emphasisQuotesGoButApostrophesStay() {
        TextTools.Prepared p = prep("WHERE THERE ARE PLACES THE 'SUN'S' LIGHT HITS, THERE MUST ALSO BE 'SHADOWS'...");
        assertEquals("Where there are places the sun's light hits, there must also be shadows", p.text);
        assertEquals("...", p.suffix);
        assertTrue(p.continues);
        assertEquals("He's the one who said no.", prep("HE'S THE ONE WHO SAID 'NO'.").text);
    }

    @Test public void pauseInsideSentenceBecomesComma() {
        TextTools.Prepared p = prep("THIS 'LUCY' THAT THE CORPSE CHOSE IS... TRULY...");
        assertEquals("This lucy that the corpse chose is, truly", p.text);
        assertTrue(p.continues);
    }

    @Test public void finishedSentenceDoesNotContinue() {
        TextTools.Prepared p = prep("MY 'GODDESS' EXISTING IN THIS REALITY.");
        assertEquals("My goddess existing in this reality.", p.text);
        assertFalse(p.continues);
        assertFalse(prep("YOU THINK SO, JOHNNY?").continues);
    }

    @Test public void slangAndDroppedG() {
        assertEquals("This blizzard's hitting us the right way, pocoloco, baby!",
                prep("THIS BLIZZARD'S HITTIN' US THE RIGHT WAY, POCOLOCO, BABY~!").text);
        assertEquals("Don't get your panties in a twist, yo, yo!", prep("DON'T GET YO' PANTIES IN A TWIST, YO, YO!").text);
        assertEquals("Keep on following the wind!", prep("KEEP ON FOLLOWIN' THE WIND!").text);
        assertEquals("Come on, let me go!", prep("C'MON, LEMME GO!").text);
    }

    @Test public void trailingDotsWithQuestionMark() {
        TextTools.Prepared p = prep("SO YOU'VE CAUGHT UP WITH ME...?");
        assertEquals("So you've caught up with me?", p.text);
        assertEquals("...", p.suffix);
        assertEquals("ตามมาทันแล้วสินะ?...", p.wrap("ตามมาทันแล้วสินะ?"));
    }

    @Test public void stutterAndShouting() {
        assertEquals("No way!", prep("N-NO WAY!!").text);
        TextTools.Prepared p = prep("...AND THEN?!");
        assertEquals("And then?", p.text);
        assertEquals("...", p.prefix);
        assertEquals("Disease-free.", prep("DISEASE-FREE.").text);
    }

    @Test public void dashEndsLikeDots() {
        TextTools.Prepared p = prep("IT WAS NECESSARY FOR A FEW HOURS--");
        assertEquals("It was necessary for a few hours", p.text);
        assertTrue(p.continues);
    }
}
