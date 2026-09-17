package dev.pzfps.bridge;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import zombie.input.Mouse;

public final class MouseInputPatch {
    private MouseInputPatch() {}

    @Patch(className = "zombie.input.Mouse", methodName = "isButtonDown")
    public static final class Down {
        private Down() {}

        @Patch.OnExit
        public static void exit(
                @Patch.Argument(0) int button,
                @Patch.Return(readOnly = false) boolean result) {
            if (!InputState.current().active()) return;
            if (button == Mouse.LMB && InputState.isButtonDown(InputState.PRIMARY)) result = true;
            if (button == Mouse.RMB && InputState.isButtonDown(InputState.AIM)) result = true;
        }
    }

    @Patch(className = "zombie.input.Mouse", methodName = "isButtonPressed")
    public static final class Pressed {
        private Pressed() {}

        @Patch.OnExit
        public static void exit(
                @Patch.Argument(0) int button,
                @Patch.Return(readOnly = false) boolean result) {
            if (!InputState.current().active()) return;
            if (button == Mouse.LMB && InputState.isButtonPressed(InputState.PRIMARY)) result = true;
            if (button == Mouse.RMB && InputState.isButtonPressed(InputState.AIM)) result = true;
        }
    }
}
