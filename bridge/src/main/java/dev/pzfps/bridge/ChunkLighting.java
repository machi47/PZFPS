package dev.pzfps.bridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Immutable, geometry-independent raw B42 square light. No visibility/darkMulti multiplier. */
final class ChunkLighting {
    static final int WIDTH = 8;
    static final int MIN_Z = -32;
    static final int LEVELS = 64;
    static final int HEIGHT = WIDTH * LEVELS;
    static final int BYTES = WIDTH * HEIGHT * 4;
    private final byte[] rgba;
    final long capturedNanos;
    final int contentHash;

    ChunkLighting(byte[] rgba, long capturedNanos) {
        if (rgba.length != BYTES) throw new IllegalArgumentException("Incorrect light grid size");
        this.rgba = rgba.clone();
        this.capturedNanos = capturedNanos;
        this.contentHash = Arrays.hashCode(this.rgba);
    }

    static int index(int x, int y, int z) {
        if (x < 0 || x >= WIDTH || y < 0 || y >= WIDTH || z < MIN_Z || z >= MIN_Z + LEVELS)
            throw new IllegalArgumentException("Square outside B42 chunk lighting extent");
        return x + WIDTH * (y + WIDTH * (z - MIN_Z));
    }

    static void put(byte[] target, int x, int y, int z, int r, int g, int b) {
        int offset = index(x, y, z) * 4;
        target[offset] = (byte) Math.clamp(r, 0, 255);
        target[offset + 1] = (byte) Math.clamp(g, 0, 255);
        target[offset + 2] = (byte) Math.clamp(b, 0, 255);
        target[offset + 3] = (byte) 255; // validity, not source opacity
    }

    static ChunkLighting fromChunk(WorldState.Chunk chunk) {
        byte[] values = new byte[BYTES];
        for (WorldState.Square s : chunk.squares())
            put(values, s.localX(), s.localY(), s.z(), s.lightR(), s.lightG(), s.lightB());
        return new ChunkLighting(values, System.nanoTime());
    }

    boolean sameContent(ChunkLighting other) {
        return this == other || (other != null && contentHash == other.contentHash && Arrays.equals(rgba, other.rgba));
    }

    void writeTo(ByteBuffer target) { target.put(rgba); }

    /** Coalescing latest-value mailbox; unloaded chunks are explicitly removed. */
    static final class Store {
        private final int capacity;
        private final LinkedHashMap<Long, ChunkLighting> latest = new LinkedHashMap<>();
        Store(int capacity) {
            if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
            this.capacity = capacity;
        }
        synchronized void put(long key, ChunkLighting value) {
            latest.remove(key);
            latest.put(key, value);
            while (latest.size() > capacity) latest.remove(latest.keySet().iterator().next());
        }
        synchronized void remove(long key) { latest.remove(key); }
        synchronized Map<Long, ChunkLighting> snapshot() { return Map.copyOf(latest); }
    }
}
