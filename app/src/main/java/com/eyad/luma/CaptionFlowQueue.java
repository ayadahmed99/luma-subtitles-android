package com.eyad.luma;

import java.util.ArrayDeque;

/**
 * Keeps caption delivery bounded: partials are replaceable snapshots, while final
 * segments are retained in order. When the final queue is full, its two oldest
 * entries are coalesced rather than discarded.
 */
final class CaptionFlowQueue {
    static final int DEFAULT_MAX_FINAL_SEGMENTS = 32;

    static final class Update {
        final String text;
        final boolean isFinal;
        final long sequence;
        final int attempts;

        Update(String text, boolean isFinal, long sequence, int attempts) {
            this.text = text;
            this.isFinal = isFinal;
            this.sequence = sequence;
            this.attempts = attempts;
        }
    }

    private final ArrayDeque<Update> finals = new ArrayDeque<>();
    private final int maxFinalSegments;
    private String latestPartial;
    private long nextSequence;

    CaptionFlowQueue() {
        this(DEFAULT_MAX_FINAL_SEGMENTS);
    }

    CaptionFlowQueue(int maxFinalSegments) {
        this.maxFinalSegments = Math.max(2, maxFinalSegments);
    }

    /** Adds an update and returns its monotonically increasing sequence, or -1 for empty text. */
    synchronized long offer(String text, boolean isFinal) {
        if (text == null || text.trim().isEmpty()) return -1L;
        String clean = text.trim();
        long sequence = ++nextSequence;
        Update update = new Update(clean, isFinal, sequence, 0);
        if (!isFinal) {
            latestPartial = clean;
            return sequence;
        }
        latestPartial = null;
        if (finals.size() >= maxFinalSegments) coalesceOldestFinals();
        finals.addLast(update);
        return sequence;
    }

    synchronized Update poll() {
        if (!finals.isEmpty()) return finals.removeFirst();
        if (latestPartial == null) return null;
        Update update = new Update(latestPartial, false, nextSequence, 0);
        latestPartial = null;
        return update;
    }

    /** Returns a failed final to the head without exceeding the configured queue size. */
    synchronized void requeueFirst(Update update) {
        if (update == null || !update.isFinal) return;
        if (finals.size() >= maxFinalSegments) coalesceOldestFinals();
        finals.addFirst(new Update(update.text, true, update.sequence, update.attempts + 1));
    }

    synchronized boolean hasFinals() {
        return !finals.isEmpty();
    }

    synchronized boolean hasPending() {
        return !finals.isEmpty() || latestPartial != null;
    }

    synchronized int pendingFinalCount() {
        return finals.size();
    }

    synchronized String latestPartialText() {
        return latestPartial;
    }

    synchronized void clear() {
        finals.clear();
        latestPartial = null;
    }

    private void coalesceOldestFinals() {
        Update first = finals.removeFirst();
        Update second = finals.removeFirst();
        finals.addFirst(new Update(first.text + " " + second.text, true,
                second.sequence, Math.max(first.attempts, second.attempts)));
    }
}
