package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class FenceAssemblyTest {
    private static WorldState.TileObject fence(String sprite, boolean north, boolean west) {
        return new WorldState.TileObject(0, "zombie.iso.IsoObject", "", sprite,
                false, false, north, north, west, false, true);
    }

    @Test
    void routesEveryInstalledShortChainLinkVariantThroughOneAssembly() {
        assertTrue(FenceAssembly.shortChainLink(fence("fencing_01_24", true, false)));
        assertTrue(FenceAssembly.shortChainLink(fence("fencing_01_25", false, false)));
        assertTrue(FenceAssembly.shortChainLink(fence("fencing_01_26", false, true)));
        assertTrue(FenceAssembly.shortChainLink(fence("fencing_01_27", false, false)));
        assertTrue(FenceAssembly.north(fence("fencing_01_24", false, false)));
        assertFalse(FenceAssembly.north(fence("fencing_01_26", false, false)));
        assertFalse(FenceAssembly.shortChainLink(fence("fencing_01_28", true, false)));
    }
}
