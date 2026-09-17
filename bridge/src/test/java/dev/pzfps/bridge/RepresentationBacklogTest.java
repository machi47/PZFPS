package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RepresentationBacklogTest {
    @Test
    void ranksOnlyVisibleUnsupportedSpriteIdentitiesDeterministically() {
        WorldMeshBuilder.MeshData first = mesh(
                1,
                Map.of("chair_0", 2, "lamp_0", 1),
                Map.of("chair_0", 2));
        WorldMeshBuilder.MeshData second = mesh(
                2,
                Map.of("lamp_0", 3, "crate_0", 4),
                Map.of("crate_0", 4));

        List<InProcessWorldRenderer.SpriteCount> result =
                InProcessWorldRenderer.topUnsupportedSprites(List.of(first, second), 2);

        assertEquals(
                List.of(
                        new InProcessWorldRenderer.SpriteCount("crate_0", 4),
                        new InProcessWorldRenderer.SpriteCount("lamp_0", 4)),
                result);
        assertEquals(
                List.of(
                        new InProcessWorldRenderer.SpriteCount("crate_0", 4),
                        new InProcessWorldRenderer.SpriteCount("chair_0", 2)),
                InProcessWorldRenderer.topUnsupportedCollisionSprites(
                        List.of(first, second), 2));
        assertThrows(
                IllegalArgumentException.class,
                () -> InProcessWorldRenderer.topUnsupportedSprites(List.of(first), 0));
    }

    private static WorldMeshBuilder.MeshData mesh(
            long key,
            Map<String, Integer> unsupported,
            Map<String, Integer> collision) {
        return new WorldMeshBuilder.MeshData(
                key,
                key,
                new float[0],
                List.of(),
                0,
                WorldMeshBuilder.Coverage.none(),
                unsupported,
                collision,
                0,
                0,
                0,
                0,
                0,
                0);
    }
}
