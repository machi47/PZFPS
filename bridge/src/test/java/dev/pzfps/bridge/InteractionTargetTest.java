package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class InteractionTargetTest {
    @Test
    void selectsNearestInteractiveObjectInsidePerspectiveReticle() {
        WorldState.TileObject behind = object(0, true, false, false);
        WorldState.TileObject aheadContainer = object(1, false, false, true);
        WorldState.TileObject fartherDoor = object(2, true, false, false);
        WorldState.Chunk chunk = new WorldState.Chunk(
                0,
                0,
                1,
                1,
                List.of(
                        square(0, 0, behind),
                        square(2, 0, aheadContainer),
                        square(3, 0, fartherDoor)));
        WorldState.Player player = player(1.5f, 0.5f, 1.0f, 0.0f);

        InteractionTarget.Reference result = InteractionTarget.nearestInteractive(
                        player, List.of(chunk), 3.0f)
                .orElseThrow();

        assertEquals(2, result.squareX());
        assertEquals(1, result.objectIndex());
    }

    @Test
    void ignoresDecorAndObjectsOutsideReticleWidth() {
        WorldState.TileObject decor = object(0, false, false, false);
        WorldState.TileObject sideDoor = object(1, true, false, false);
        WorldState.Chunk chunk = new WorldState.Chunk(
                0,
                0,
                1,
                1,
                List.of(square(2, 0, decor), square(2, 2, sideDoor)));

        assertTrue(InteractionTarget.nearestInteractive(
                        player(1.5f, 0.5f, 1.0f, 0.0f), List.of(chunk), 3.0f)
                .isEmpty());
    }

    @Test
    void usesAuthoritativeDoorEdgeInsteadOfTileCenter() {
        WorldState.TileObject northDoor = new WorldState.TileObject(
                3,
                "zombie.iso.objects.IsoDoor",
                "doorN",
                "fixtures_doors_01_0",
                true,
                false,
                true,
                true,
                false,
                false,
                false,
                false,
                WorldState.WorldItem.none());
        WorldState.Chunk chunk = new WorldState.Chunk(
                0, 0, 1, 1, List.of(square(2, 1, northDoor)));

        InteractionTarget.Reference result = InteractionTarget.nearestInteractive(
                        player(2.5f, 0.2f, 0.0f, 1.0f), List.of(chunk), 2.0f)
                .orElseThrow();

        assertEquals(2, result.squareX());
        assertEquals(1, result.squareY());
    }

    private static WorldState.Player player(float x, float y, float forwardX, float forwardY) {
        return new WorldState.Player(
                1, 1, 1, 0, x, y, 0, forwardX, forwardY,
                0, "Idle", false, false, false, 1.62f);
    }

    private static WorldState.Square square(int x, int y, WorldState.TileObject object) {
        return new WorldState.Square(
                x, y, 0, -1, 7, 255, 255, 255,
                true, true, false, false, false, false, List.of(object));
    }

    private static WorldState.TileObject object(
            int index, boolean door, boolean window, boolean container) {
        return new WorldState.TileObject(
                index,
                "zombie.iso.IsoObject",
                "fixture",
                "fixture_0",
                door,
                window,
                false,
                false,
                false,
                false,
                false,
                container,
                WorldState.WorldItem.none());
    }
}
