package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.input.Mouse;

public final class MouseInputPatch {
    private MouseInputPatch() {}

    public static final class Down {
        private Down() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) int button,
                @Advice.Return(readOnly = false) boolean result) {
            if (!InputState.current().active()) return;
            if (button == Mouse.LMB && InputState.isButtonDown(InputState.PRIMARY)) result = true;
            if (button == Mouse.RMB && InputState.isButtonDown(InputState.AIM)) result = true;
        }
    }

    public static final class Pressed {
        private Pressed() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) int button,
                @Advice.Return(readOnly = false) boolean result) {
            if (!InputState.current().active()) return;
            if (button == Mouse.LMB && InputState.isButtonPressed(InputState.PRIMARY)) result = true;
            if (button == Mouse.RMB && InputState.isButtonPressed(InputState.AIM)) result = true;
        }
    }
}
