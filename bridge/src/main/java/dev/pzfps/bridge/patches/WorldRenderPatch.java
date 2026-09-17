package dev.pzfps.bridge;

import java.util.LinkedHashSet;
import net.bytebuddy.asm.Advice;

/** Replaces only the isometric world draw; IngameState's later text and UI passes remain. */
public final class WorldRenderPatch {
    private WorldRenderPatch() {}

    @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
    public static boolean enter() {
        BridgeRuntime.onWorldRender();
        NativeActorPass.PreparedFrame actors = BridgeRuntime.prepareNativeActors();
        NativeVehiclePass.PreparedFrame vehicles = BridgeRuntime.prepareNativeVehicles();
        NativeFirstPersonHandsPass.PreparedFrame hands = BridgeRuntime.prepareNativeHands();
        LinkedHashSet<Integer> nativeEntityIds = new LinkedHashSet<>(actors.entityIds());
        nativeEntityIds.addAll(vehicles.entityIds());
        boolean replaced = InProcessWorldRenderer.replaceWorldDraw(nativeEntityIds);
        if (replaced) {
            vehicles.queueAfterWorld();
            actors.queueAfterWorld();
            BridgeRuntime.queueNativeWorldItems();
            hands.queueAfterWorld();
        } else {
            hands.discard();
            vehicles.discard();
            actors.discard();
        }
        return replaced;
    }
}
