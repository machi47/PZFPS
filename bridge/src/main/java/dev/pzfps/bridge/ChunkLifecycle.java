package dev.pzfps.bridge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stabilizes the rotating/incrementally-populated chunk view exposed by PZ.
 *
 * <p>A chunk disappearing from one capture is not evidence that it was unloaded, and a single
 * changed fingerprint can be an in-progress mutation. Keep the last accepted immutable snapshot
 * until absence or a replacement has been observed consistently.
 */
final class ChunkLifecycle {
    record Presence(Set<Long> added, Set<Long> removed) {}

    private final int missingGraceCaptures;
    private final int changeConfirmationCaptures;
    private final Set<Long> active = new HashSet<>();
    private final Map<Long, Integer> missingCounts = new HashMap<>();
    private final Map<Long, Long> acceptedFingerprints = new HashMap<>();
    private final Map<Long, Long> pendingFingerprints = new HashMap<>();
    private final Map<Long, Integer> pendingCounts = new HashMap<>();

    ChunkLifecycle(int missingGraceCaptures, int changeConfirmationCaptures) {
        if (missingGraceCaptures < 1) {
            throw new IllegalArgumentException("missingGraceCaptures must be positive");
        }
        if (changeConfirmationCaptures < 1) {
            throw new IllegalArgumentException("changeConfirmationCaptures must be positive");
        }
        this.missingGraceCaptures = missingGraceCaptures;
        this.changeConfirmationCaptures = changeConfirmationCaptures;
    }

    Presence observePresence(Set<Long> visible) {
        HashSet<Long> added = new HashSet<>();
        HashSet<Long> removed = new HashSet<>();

        for (Long key : visible) {
            missingCounts.remove(key);
            if (active.add(key)) added.add(key);
        }

        for (Long key : Set.copyOf(active)) {
            if (visible.contains(key)) continue;
            int missing = missingCounts.merge(key, 1, Integer::sum);
            if (missing < missingGraceCaptures) continue;
            active.remove(key);
            missingCounts.remove(key);
            acceptedFingerprints.remove(key);
            pendingFingerprints.remove(key);
            pendingCounts.remove(key);
            removed.add(key);
        }
        return new Presence(Set.copyOf(added), Set.copyOf(removed));
    }

    /** Returns true only when the caller should replace the accepted snapshot for {@code key}. */
    boolean acceptFingerprint(long key, long fingerprint) {
        active.add(key);
        Long accepted = acceptedFingerprints.get(key);
        if (accepted == null) {
            acceptedFingerprints.put(key, fingerprint);
            clearPending(key);
            return true;
        }
        if (accepted.longValue() == fingerprint) {
            clearPending(key);
            return false;
        }

        Long pending = pendingFingerprints.get(key);
        int count;
        if (pending != null && pending.longValue() == fingerprint) {
            count = pendingCounts.merge(key, 1, Integer::sum);
        } else {
            pendingFingerprints.put(key, fingerprint);
            pendingCounts.put(key, 1);
            count = 1;
        }
        if (count < changeConfirmationCaptures) return false;

        acceptedFingerprints.put(key, fingerprint);
        clearPending(key);
        return true;
    }

    int activeCount() {
        return active.size();
    }

    private void clearPending(long key) {
        pendingFingerprints.remove(key);
        pendingCounts.remove(key);
    }
}
