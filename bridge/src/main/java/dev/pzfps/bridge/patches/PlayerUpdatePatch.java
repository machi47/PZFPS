package dev.pzfps.bridge;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.characters.IsoPlayer;

@Patch(className = "zombie.characters.IsoPlayer", methodName = "update", strictMatch = true)
public final class PlayerUpdatePatch {
    private PlayerUpdatePatch() {}

    @Patch.OnExit
    public static void exit(@Patch.This IsoPlayer player) {
        BridgeRuntime.onPlayerUpdate(player);
    }
}
