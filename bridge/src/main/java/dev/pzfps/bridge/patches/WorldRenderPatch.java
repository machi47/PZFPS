package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;

/** Replaces only the isometric world draw; IngameState's later text and UI passes remain. */
public final class WorldRenderPatch {
    private WorldRenderPatch() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter() {
        BridgeRuntime.onWorldRender();
        boolean replaced = InProcessWorldRenderer.replaceWorldDraw();
        if (replaced) BridgeRuntime.queueNativeWorldItems();
        return replaced;
    }
}
