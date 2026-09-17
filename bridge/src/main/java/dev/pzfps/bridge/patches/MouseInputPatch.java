package dev.pzfps.bridge;

import net.bytebuddy.asm.Advice;
import zombie.input.Mouse;

public final class MouseInputPatch {
    private static final int RAW_LEFT = Mouse.LMB - Mouse.BTN_OFFSET;
    private static final int RAW_RIGHT = Mouse.RMB - Mouse.BTN_OFFSET;

    private MouseInputPatch() {}

    public static final class Down {
        private Down() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) int button,
                @Advice.Return(readOnly = false) boolean result) {
            if (button == RAW_LEFT && FirstPersonInput.shouldAdvanceDisposableLoadingScreen()) {
                result = true;
                return;
            }
            if (!InputState.current().active()) return;
            if (button == RAW_LEFT && InputState.isButtonDown(InputState.PRIMARY)) result = true;
            if (button == RAW_RIGHT && InputState.isButtonDown(InputState.AIM)) result = true;
        }
    }

    public static final class Pressed {
        private Pressed() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) int button,
                @Advice.Return(readOnly = false) boolean result) {
            if (!InputState.current().active()) return;
            if (button == RAW_LEFT && InputState.isButtonPressed(InputState.PRIMARY)) result = true;
            if (button == RAW_RIGHT && InputState.isButtonPressed(InputState.AIM)) result = true;
        }
    }

    /** Main-menu/UI path uses this wrapper instead of the gameplay button query. */
    public static final class UiCheck {
        private UiCheck() {}

        @Advice.OnMethodExit
        public static void exit(
                @Advice.Argument(0) int button,
                @Advice.Return(readOnly = false) boolean result) {
            if (!InputState.current().active()) return;
            if (button == RAW_LEFT && InputState.isButtonDown(InputState.PRIMARY)) result = true;
            if (button == RAW_RIGHT && InputState.isButtonDown(InputState.AIM)) result = true;
        }
    }
}
