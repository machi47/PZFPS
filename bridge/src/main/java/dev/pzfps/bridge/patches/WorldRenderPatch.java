package dev.pzfps.bridge;

import me.zed_0xff.zombie_buddy.annotations.Patch;

/** Replaces only the isometric world draw; IngameState's later text and UI passes remain. */
@Patch(className = "zombie.iso.IsoWorld", methodName = "render", strictMatch = true)
public final class WorldRenderPatch {
    private WorldRenderPatch() {}

    @Patch.OnEnter(skipOn = true)
    public static boolean enter() {
        BridgeRuntime.onWorldRender();
        return InProcessWorldRenderer.replaceWorldDraw();
    }
}
