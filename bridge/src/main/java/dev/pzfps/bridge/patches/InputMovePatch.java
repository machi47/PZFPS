package dev.pzfps.bridge;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.iso.Vector2;

@Patch(className = "zombie.characters.IsoPlayer", methodName = "getInputMoveVector")
public final class InputMovePatch {
    private InputMovePatch() {}

    @Patch.OnExit
    public static void exit(@Patch.Return(readOnly = false) Vector2 result) {
        if (result != null && InputState.current().active()) {
            InputState.movementVector(result);
        }
    }
}
