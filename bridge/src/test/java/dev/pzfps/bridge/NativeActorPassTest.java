package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class NativeActorPassTest {
    @Test
    void retainsAllHeadingsWithinHorizontalRangeForExactRenderFrustum() {
        WorldState.Player player = player();

        assertTrue(NativeActorPass.withinHorizontalRange(player, 20.0f, 20.0f));
        assertTrue(NativeActorPass.withinHorizontalRange(player, 0.0f, 20.0f));
        assertFalse(NativeActorPass.withinHorizontalRange(player, 70.0f, 20.0f));
    }

    @Test
    void prioritizesNearbyThreatsInsteadOfDistantStoreysAtSameMapCoordinate() {
        assertTrue(NativeActorPass.priorityDistanceSquared(player(), 13, 20, 0)
                < NativeActorPass.priorityDistanceSquared(player(), 10, 20, 8));
        assertEquals(36, NativeActorPass.priorityDistanceSquared(player(), 10, 20, 2));
        assertEquals(36, NativeActorPass.priorityDistanceSquared(player(), 10, 20, -2));
    }

    @Test
    void suppressesOnlyEntitiesOwnedByNativeActorQueue() {
        WorldState.Entity actor = entity(17, "zombie");
        WorldState.Entity vehicle = entity(18, "vehicle");

        float[] allBoxes = InProcessWorldRenderer.entityVertices(
                List.of(actor, vehicle), Set.of());
        float[] vehicleOnly = InProcessWorldRenderer.entityVertices(
                List.of(actor, vehicle), Set.of(17));

        assertEquals(2 * 36 * WorldMeshBuilder.FLOATS_PER_VERTEX, allBoxes.length);
        assertEquals(36 * WorldMeshBuilder.FLOATS_PER_VERTEX, vehicleOnly.length);
    }

    private static WorldState.Player player() {
        return new WorldState.Player(
                1, 1, 1, 0, 10, 20, 0, 1, 0,
                0, "Idle", false, false, false, 1.62f);
    }

    private static WorldState.Entity entity(int id, String kind) {
        return new WorldState.Entity(
                id, "uid-" + id, kind, "", 12, 20, 0, 1, 0,
                "", false, false, WorldState.ActorPose.unavailable());
    }
}
