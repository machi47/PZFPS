package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;

/** Restores FPS view state after B42's isometric ballistics path updates actor aim. */
public final class AimStatePatch {
    private AimStatePatch() {}

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void exit(@Advice.This IsoPlayer player) {
        BridgeRuntime.restorePerspectiveAim(player);
    }
}
