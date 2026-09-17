package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeWorldItemPassTest {
    @Test
    void extractsAbsoluteIdentityCheckedReferences() {
        WorldState.WorldItem item = new WorldState.WorldItem(
                true, 73, "Base.Hammer", "Hammer", "HammerWorld", "", "",
                123.25f, 456.75f, 2.01f, 0, 0, 0, 1, false);
        WorldState.TileObject object = new WorldState.TileObject(
                4, "zombie.iso.objects.IsoWorldInventoryObject", "MAX", "Item_Hammer",
                false, false, false, false, false, false, false, false, item);
        WorldState.Square square = new WorldState.Square(
                3, 6, 2, -1, 7, 255, 255, 255,
                true, true, false, false, false, false, List.of(object));
        WorldState.Chunk chunk = new WorldState.Chunk(12, 45, 1, 9, List.of(square));

        NativeWorldItemPass.Reference result =
                NativeWorldItemPass.references(chunk).getFirst();

        int chunkSize = zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        assertEquals(12 * chunkSize + 3, result.squareX());
        assertEquals(45 * chunkSize + 6, result.squareY());
        assertEquals(73, result.itemId());
        assertEquals(123.25f, result.worldX());
    }

    @Test
    void cullsByPerspectiveHeadingAndDistance() {
        WorldState.Player player = new WorldState.Player(
                1, 1, 1, 0, 10, 20, 0, 1, 0,
                0, "Idle", false, false, false, 1.62f);
        assertTrue(NativeWorldItemPass.visible(player, reference(20, 20)));
        assertFalse(NativeWorldItemPass.visible(player, reference(0, 20)));
        assertFalse(NativeWorldItemPass.visible(player, reference(70, 20)));
    }

    private static NativeWorldItemPass.Reference reference(float x, float y) {
        return new NativeWorldItemPass.Reference(0, 0, 0, 0, 1, x, y, 0);
    }
}
