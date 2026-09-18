package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

final class WallAttachmentAssemblyTest {
    private static TileGeometryRegistry.Primitive box(
            float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return new TileGeometryRegistry.Primitive(
                "box", 0,0,0, 0,0,0,
                minX,minY,minZ,maxX,maxY,maxZ, 0,0,0,"",List.of());
    }

    private static WorldState.TileObject light(String sprite) {
        return new WorldState.TileObject(
                2, "zombie.iso.objects.IsoLightSwitch", "lightswitch", sprite,
                false,false,false,false,false,false,false);
    }

    @Test
    void supportThinAxisSelectsOwningWallAtCorner() {
        var west = WallAttachmentAssembly.placement(
                light("lighting_indoor_01_1"),
                List.of(box(-.45f,0,-.5f,-.4f,2.4495f,.5f)),
                StructuralPropClip.NORTH | StructuralPropClip.WEST).orElseThrow();
        assertEquals(StructuralPropClip.WEST, west.edge());
        assertEquals(.42f, west.width());
        assertEquals(.22f, west.height());

        var south = WallAttachmentAssembly.placement(
                light("lighting_indoor_01_3"),
                List.of(box(-.5f,0,.45f,.5f,2.4495f,.5f)),
                StructuralPropClip.WEST | StructuralPropClip.SOUTH).orElseThrow();
        assertEquals(StructuralPropClip.SOUTH, south.edge());
    }

    @Test
    void requiresAuthoritativeWallAndExactRuntimeFamily() {
        assertTrue(WallAttachmentAssembly.placement(
                light("lighting_indoor_01_10"), List.of(), 0).isEmpty());
        var unrelated = new WorldState.TileObject(
                0,"zombie.iso.IsoObject","MAX","lighting_indoor_01_10",
                false,false,false,false,false,false,false);
        assertFalse(WallAttachmentAssembly.eligible(unrelated));
    }
}
