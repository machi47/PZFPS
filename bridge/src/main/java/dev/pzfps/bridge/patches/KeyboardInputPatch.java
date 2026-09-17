package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;

public final class KeyboardInputPatch {
    private KeyboardInputPatch() {}

    public static final class Down {
        private Down() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) String action,
                @Advice.Return(readOnly = false) boolean result) {
            if (InputState.current().active() && InputState.isActionDown(action)) result = true;
        }
    }

    public static final class Pressed {
        private Pressed() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) String action,
                @Advice.Return(readOnly = false) boolean result) {
            if (InputState.current().active() && InputState.isActionPressed(action)) result = true;
        }
    }
}
