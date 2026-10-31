package com.mangathai.mobile;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** Positions measured on a real page (screen pixels). */
public class ContinuationTest {
    // "THIS 'LUCY' THAT THE CORPSE CHOSE IS... TRULY..."      (unfinished)
    private static final Continuation.Box LUCY = new Continuation.Box(818, 385, 137, 120, 18, true);
    // "MY 'GODDESS' EXISTING IN THIS REALITY." — smaller, just below and to the left
    private static final Continuation.Box GODDESS = new Continuation.Box(712, 578, 118, 28, 9, false);
    // "WHERE THERE ARE PLACES ... MISFORTUNE..." (unfinished, but nothing follows it in this panel)
    private static final Continuation.Box WHERE = new Continuation.Box(760, 980, 190, 245, 18, true);
    // "FROM GOD'S PERSPECTIVE ... TO 'ZERO'!" — beside WHERE, not below it
    private static final Continuation.Box GOD = new Continuation.Box(448, 1100, 157, 135, 18, false);
    // "THIS PHENOMENON..." caption, in the next panel down
    private static final Continuation.Box PHENOMENON = new Continuation.Box(750, 1365, 195, 155, 18, true);

    @Test public void joinsBalloonThatFinishesTheSentence() {
        List<List<Integer>> chains = Continuation.chains(Arrays.asList(LUCY, GODDESS, WHERE, GOD, PHENOMENON));
        assertTrue(chains.contains(Arrays.asList(0, 1)));
    }

    @Test public void doesNotJoinAcrossPanelsOrSideBySide() {
        List<List<Integer>> chains = Continuation.chains(Arrays.asList(LUCY, GODDESS, WHERE, GOD, PHENOMENON));
        assertTrue(chains.contains(Arrays.asList(2)));
        assertTrue(chains.contains(Arrays.asList(3)));
        assertTrue(chains.contains(Arrays.asList(4)));
        assertEquals(4, chains.size());
    }

    @Test public void everyBalloonAppearsOnce() {
        Continuation.Box a = new Continuation.Box(0, 0, 100, 40, 10, true);
        Continuation.Box b = new Continuation.Box(0, 50, 100, 40, 10, true);
        Continuation.Box c = new Continuation.Box(0, 100, 100, 40, 10, true);
        Continuation.Box d = new Continuation.Box(0, 150, 100, 40, 10, false);
        List<List<Integer>> chains = Continuation.chains(Arrays.asList(a, b, c, d));
        assertEquals(Arrays.asList(Arrays.asList(0, 1, 2), Arrays.asList(3)), chains);   // at most 3 per chain
    }

    @Test public void splitsThaiAtWordBoundaries() {
        String thai = "ลูซี่ที่ศพเลือกนี้ช่างเป็นเทพธิดาของฉันที่มีอยู่จริงในโลกนี้";
        String[] parts = Continuation.split(thai, new int[]{40, 36});
        assertNotNull(parts);
        assertEquals(2, parts.length);
        assertEquals(thai, parts[0] + parts[1]);
        assertTrue(parts[0].length() > 10 && parts[1].length() > 10);
        assertArrayEquals(new String[]{"สวัสดี"}, Continuation.split("สวัสดี", new int[]{5}));
        assertNull(Continuation.split("ใช่", new int[]{10, 10}));   // too short to share
    }
}
