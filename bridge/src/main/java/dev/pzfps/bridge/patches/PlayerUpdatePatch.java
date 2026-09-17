package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.characters.IsoPlayer;

public final class PlayerUpdatePatch {
    private PlayerUpdatePatch() {}

    @Advice.OnMethodEnter
    public static void enter(@Advice.This IsoPlayer player) {
        BridgeRuntime.onPlayerUpdateStart(player);
    }

    @Advice.OnMethodExit
    public static void exit(@Advice.This IsoPlayer player) {
        BridgeRuntime.onPlayerUpdate(player);
    }
}
