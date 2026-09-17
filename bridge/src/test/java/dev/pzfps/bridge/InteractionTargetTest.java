package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import zombie.iso.LosUtil;

final class InteractionTargetTest {
    @Test
    void admitsOnlyTheOccluderForClosedDoorAndWindowSightlines() {
        assertTrue(InteractionTarget.allowsSightline(
                LosUtil.TestResults.ClearThroughClosedDoor, true, false));
        assertFalse(InteractionTarget.allowsSightline(
                LosUtil.TestResults.ClearThroughClosedDoor, false, false));
        assertTrue(InteractionTarget.allowsSightline(
                LosUtil.TestResults.ClearThroughWindow, false, true));
        assertFalse(InteractionTarget.allowsSightline(
                LosUtil.TestResults.ClearThroughWindow, false, false));
        assertFalse(InteractionTarget.allowsSightline(
                LosUtil.TestResults.Blocked, true, true));
        assertTrue(InteractionTarget.allowsSightline(
                LosUtil.TestResults.ClearThroughOpenDoor, false, false));
    }

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
    void contextCandidatesIncludeOrdinaryObjectsInNearToFarOrder() {
        WorldState.TileObject ordinary = object(4, false, false, false);
        WorldState.TileObject fartherContainer = object(5, false, false, true);
        WorldState.Chunk chunk = new WorldState.Chunk(
                0,
                0,
                1,
                1,
                List.of(square(2, 0, ordinary), square(3, 0, fartherContainer)));

        List<InteractionTarget.Reference> results = InteractionTarget.contextCandidates(
                player(0.5f, 0.5f, 1.0f, 0.0f), List.of(chunk), 4.0f);

        assertEquals(2, results.size());
        assertEquals(4, results.get(0).objectIndex());
        assertEquals(5, results.get(1).objectIndex());
    }

    @Test
    void floorCandidateRequiresLookingAtTheFloorPlane() {
        WorldState.TileObject floor = new WorldState.TileObject(
                6,
                "zombie.iso.IsoObject",
                "normal",
                "floors_interior_tilesandwood_01_0",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                WorldState.WorldItem.none());
        WorldState.Chunk chunk = new WorldState.Chunk(
                0, 0, 1, 1, List.of(square(2, 0, floor)));

        assertTrue(InteractionTarget.contextCandidates(
                        player(0.5f, 0.5f, 1.0f, 0.0f), List.of(chunk), 4.0f)
                .isEmpty());
        assertEquals(
                6,
                InteractionTarget.contextCandidates(
                                player(
                                        0.5f,
                                        0.5f,
                                        1.0f,
                                        0.0f,
                                        (float) Math.toRadians(-45.0)),
                                List.of(chunk),
                                4.0f)
                        .getFirst()
                        .objectIndex());
    }

    @Test
    void worldItemCandidateUsesItsPreciseGroundPlacement() {
        WorldState.WorldItem item = new WorldState.WorldItem(
                true,
                42,
                "Base.Hammer",
                "Hammer",
                "",
                "",
                "",
                2.5f,
                0.5f,
                0.0f,
                0.0f,
                0.0f,
                0.0f,
                1.0f,
                false);
        WorldState.TileObject worldItem = new WorldState.TileObject(
                7,
                "zombie.iso.objects.IsoWorldInventoryObject",
                "normal",
                "",
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                item);
        WorldState.Chunk chunk = new WorldState.Chunk(
                0, 0, 1, 1, List.of(square(2, 0, worldItem)));

        assertTrue(InteractionTarget.contextCandidates(
                        player(0.5f, 0.5f, 1.0f, 0.0f), List.of(chunk), 4.0f)
                .isEmpty());
        assertEquals(
                42,
                InteractionTarget.contextCandidates(
                                player(
                                        0.5f,
                                        0.5f,
                                        1.0f,
                                        0.0f,
                                        (float) Math.toRadians(-30.0)),
                                List.of(chunk),
                                4.0f)
                        .getFirst()
                        .worldItemId());
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

    @Test
    void pitchCanRejectAContainerOutsideTheThreeDimensionalReticle() {
        WorldState.TileObject container = object(2, false, false, true);
        WorldState.Chunk chunk = new WorldState.Chunk(
                0, 0, 1, 1, List.of(square(2, 0, container)));

        assertTrue(InteractionTarget.nearestInteractive(
                        player(0.5f, 0.5f, 1.0f, 0.0f, (float) Math.toRadians(80.0)),
                        List.of(chunk),
                        3.0f)
                .isEmpty());
    }

    @Test
    void rayBoxDistanceHonorsPerspectiveReach() {
        float[] bounds = {2, 0, 0, 3, 3, 1};
        assertEquals(
                1.5f,
                InteractionTarget.rayBoxDistance(0.5f, 1.6f, 0.5f, 1, 0, 0, bounds, 3),
                0.0001f);
        assertTrue(Float.isInfinite(
                InteractionTarget.rayBoxDistance(0.5f, 1.6f, 0.5f, 1, 0, 0, bounds, 1)));
    }

    private static WorldState.Player player(float x, float y, float forwardX, float forwardY) {
        return player(x, y, forwardX, forwardY, 0.0f);
    }

    private static WorldState.Player player(
            float x, float y, float forwardX, float forwardY, float pitch) {
        return new WorldState.Player(
                1, 1, 1, 0, x, y, 0, forwardX, forwardY,
                pitch, "Idle", false, false, false, 1.62f);
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
