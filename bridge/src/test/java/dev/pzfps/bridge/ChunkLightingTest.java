package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class ChunkLightingTest {
    @TempDir Path temporary;

    @Test void gridPreservesRawRgbWithoutVisibilityMaskOrExposureFloor() {
        byte[] values = new byte[ChunkLighting.BYTES];
        ChunkLighting.put(values, 7, 6, -2, 1, 90, 200);
        ChunkLighting sample = new ChunkLighting(values, 42);
        values[ChunkLighting.index(7, 6, -2) * 4] = 55;
        ByteBuffer copy = ByteBuffer.allocate(ChunkLighting.BYTES);
        sample.writeTo(copy);
        int offset = ChunkLighting.index(7, 6, -2) * 4;
        assertEquals(1, Byte.toUnsignedInt(copy.get(offset)));
        assertEquals(90, Byte.toUnsignedInt(copy.get(offset + 1)));
        assertEquals(200, Byte.toUnsignedInt(copy.get(offset + 2)));
        assertEquals(255, Byte.toUnsignedInt(copy.get(offset + 3)));
        assertEquals(0, copy.get(3)); // absent square remains invalid, not a dark sample
        assertEquals(42, sample.capturedNanos);
        assertEquals(4095, ChunkLighting.index(7, 7, 31));
        assertEquals(0, ChunkLighting.index(0, 0, -32));
        assertThrows(IllegalArgumentException.class, () -> ChunkLighting.index(0, 0, 32));
    }

    @Test void unchangedBytesAvoidUploadsButTimestampStillAdvances() {
        byte[] a = new byte[ChunkLighting.BYTES];
        ChunkLighting old = new ChunkLighting(a, 10);
        ChunkLighting next = new ChunkLighting(a, 20);
        assertTrue(next.sameContent(old));
        ChunkLighting.put(a, 1, 2, 0, 1, 2, 3);
        assertFalse(new ChunkLighting(a, 30).sameContent(old));
        ChunkLighting.Store store = new ChunkLighting.Store(2);
        store.put(1, old);
        var retainedSnapshot = store.snapshot();
        store.put(1, next);
        assertSame(next, store.snapshot().get(1L));
        assertSame(old, retainedSnapshot.get(1L));
        store.put(2, old);
        store.put(3, old);
        assertEquals(2, store.snapshot().size());
        assertFalse(store.snapshot().containsKey(1L));
        store.remove(3);
        assertEquals(1, store.snapshot().size());
    }

    private WorldState.Chunk chunk(int r, int g, int b, int visibility) {
        var wall = new WorldState.TileObject(0, "zombie.iso.IsoObject", "wall", "walls_fixture_01_0",
                false, false, false, true, true, false, false);
        var square = new WorldState.Square(2, 3, 1, 0, visibility, r, g, b,
                true, false, true, false, false, false, List.of(wall));
        return new WorldState.Chunk(100, 200, 1, 99, List.of(square));
    }

    @Test void changingLightOrVisibilityDoesNotChangeGeometryOrBaseMaterials() throws Exception {
        Path path = temporary.resolve("registry.json");
        Files.writeString(path, "{\"schema_version\":1,\"source_sha256\":\"fixture\",\"tiles\":{}}");
        var builder = new WorldMeshBuilder(TileGeometryRegistry.load(path));
        var bright = chunk(255, 240, 210, 7);
        var dark = chunk(1, 2, 3, 0);
        var first = builder.build(bright);
        var second = builder.build(dark);
        assertArrayEquals(first.vertices(), second.vertices());
        assertArrayEquals(first.texturedBatches().getFirst().vertices(), second.texturedBatches().getFirst().vertices());
        assertArrayEquals(first.materialBatches().getFirst().vertices(), second.materialBatches().getFirst().vertices());
        assertFalse(ChunkLighting.fromChunk(bright).sameContent(ChunkLighting.fromChunk(dark)));
        float index = ChunkLighting.index(2, 3, 1);
        // The wall top/ceiling belong to their source square, not the square above.
        for (int i = 9; i < first.vertices().length; i += WorldMeshBuilder.FLOATS_PER_VERTEX)
            assertEquals(index, first.vertices()[i]);
        for (var batch : first.texturedBatches())
            for (int i = 12; i < batch.vertices().length; i += WorldMeshBuilder.TEXTURED_FLOATS_PER_VERTEX)
                assertEquals(index, batch.vertices()[i]);
        for (var batch : first.materialBatches())
            for (int i = 9; i < batch.vertices().length; i += WorldMeshBuilder.FLOATS_PER_VERTEX)
                assertEquals(index, batch.vertices()[i]);
    }
}
