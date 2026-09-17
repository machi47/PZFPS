package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;

public final class PlayerUpdatePatch {
    private PlayerUpdatePatch() {}

    @Advice.OnMethodExit
    public static void exit(@Advice.This IsoPlayer player) {
        BridgeRuntime.onPlayerUpdate(player);
    }
}
