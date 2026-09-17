package dev.pzfps.bridge;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.iso.Vector2;

@Patch(className = "zombie.characters.IsoPlayer", methodName = "getAimVector")
public final class AimVectorPatch {
    private AimVectorPatch() {}

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) Vector2 result) {
        if (result != null && InputState.current().active()) {
            InputState.aimVector(result);
        }
    }
}
