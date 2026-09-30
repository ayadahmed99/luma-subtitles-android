package com.eyad.luma;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class CaptionFlowQueueTest {
    @Test public void partialsReplaceOneAnother() {
        CaptionFlowQueue queue = new CaptionFlowQueue(4);
        queue.offer("turn the", false);
        queue.offer("turn the music", false);

        CaptionFlowQueue.Update update = queue.poll();
        assertNotNull(update);
        assertFalse(update.isFinal);
        assertEquals("turn the music", update.text);
        assertFalse(queue.hasPending());
    }

    @Test public void finalsAreDeliveredBeforeTheNewestPartial() {
        CaptionFlowQueue queue = new CaptionFlowQueue(4);
        queue.offer("this older partial", false);
        queue.offer("first complete phrase", true);
        queue.offer("second complete phrase", true);
        queue.offer("new live partial", false);

        CaptionFlowQueue.Update first = queue.poll();
        CaptionFlowQueue.Update second = queue.poll();
        CaptionFlowQueue.Update partial = queue.poll();
        assertNotNull(first);
        assertNotNull(second);
        assertNotNull(partial);
        assertEquals("first complete phrase", first.text);
        assertEquals("second complete phrase", second.text);
        assertEquals("new live partial", partial.text);
        assertFalse(partial.isFinal);
    }

    @Test public void fullFinalQueueCoalescesWithoutDroppingOrReorderingText() {
        CaptionFlowQueue queue = new CaptionFlowQueue(4);
        for (int i = 1; i <= 5; i++) queue.offer("phrase-" + i, true);

        assertEquals(4, queue.pendingFinalCount());
        StringBuilder delivered = new StringBuilder();
        CaptionFlowQueue.Update update;
        while ((update = queue.poll()) != null) {
            assertTrue(update.isFinal);
            if (delivered.length() > 0) delivered.append(' ');
            delivered.append(update.text);
        }
        assertEquals("phrase-1 phrase-2 phrase-3 phrase-4 phrase-5", delivered.toString());
    }

    @Test public void failedFinalIsRequeuedAheadOfNewerFinals() {
        CaptionFlowQueue queue = new CaptionFlowQueue(3);
        queue.offer("first phrase", true);
        CaptionFlowQueue.Update failed = queue.poll();
        queue.offer("second phrase", true);
        queue.offer("third phrase", true);
        queue.requeueFirst(failed);

        assertEquals(3, queue.pendingFinalCount());
        CaptionFlowQueue.Update retry = queue.poll();
        assertEquals("first phrase", retry.text);
        assertEquals(failed.sequence, retry.sequence);
        assertEquals(1, retry.attempts);
        assertEquals("second phrase", queue.poll().text);
        assertEquals("third phrase", queue.poll().text);
    }

    @Test public void failedFinalRetryStillPreservesEveryPhraseWhenQueueIsFull() {
        CaptionFlowQueue queue = new CaptionFlowQueue(3);
        queue.offer("phrase-1", true);
        CaptionFlowQueue.Update inFlight = queue.poll();
        queue.offer("phrase-2", true);
        queue.offer("phrase-3", true);
        queue.offer("phrase-4", true);
        queue.requeueFirst(inFlight);

        assertEquals(3, queue.pendingFinalCount());
        StringBuilder delivered = new StringBuilder();
        CaptionFlowQueue.Update update;
        while ((update = queue.poll()) != null) {
            if (delivered.length() > 0) delivered.append(' ');
            delivered.append(update.text);
        }
        assertEquals("phrase-1 phrase-2 phrase-3 phrase-4", delivered.toString());
    }
}
