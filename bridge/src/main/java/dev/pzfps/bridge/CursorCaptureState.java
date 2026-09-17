package dev.pzfps.bridge;

/**
 * Separates deliberate cursor release from temporary UI ownership.
 *
 * <p>The distinction matters because an inventory/context menu disappearing should return to
 * mouse-look, while an F8 release must remain released until the player explicitly toggles it.
 */
final class CursorCaptureState {
    enum Mode {
        GAMEPLAY_CAPTURED,
        USER_RELEASED,
        UI_RELEASED
    }

    private final int clearUpdatesRequired;
    private Mode mode = Mode.GAMEPLAY_CAPTURED;
    private int clearUpdates;

    CursorCaptureState(int clearUpdatesRequired) {
        if (clearUpdatesRequired < 1) {
            throw new IllegalArgumentException("clearUpdatesRequired must be positive");
        }
        this.clearUpdatesRequired = clearUpdatesRequired;
    }

    Mode mode() {
        return mode;
    }

    boolean captured() {
        return mode == Mode.GAMEPLAY_CAPTURED;
    }

    /** Applies one input/UI observation and reports whether physical capture should change. */
    boolean update(boolean togglePressed, boolean uiWantsCursor) {
        boolean wasCaptured = captured();

        if (togglePressed) {
            if (mode == Mode.GAMEPLAY_CAPTURED) {
                mode = Mode.USER_RELEASED;
            } else if (uiWantsCursor) {
                mode = Mode.UI_RELEASED;
            } else {
                mode = Mode.GAMEPLAY_CAPTURED;
            }
            clearUpdates = 0;
            return wasCaptured != captured();
        }

        switch (mode) {
            case GAMEPLAY_CAPTURED -> {
                if (uiWantsCursor) {
                    mode = Mode.UI_RELEASED;
                    clearUpdates = 0;
                }
            }
            case UI_RELEASED -> {
                if (uiWantsCursor) {
                    clearUpdates = 0;
                } else if (++clearUpdates >= clearUpdatesRequired) {
                    mode = Mode.GAMEPLAY_CAPTURED;
                    clearUpdates = 0;
                }
            }
            case USER_RELEASED -> {
                // Only another explicit toggle may undo a deliberate user release.
            }
        }
        return wasCaptured != captured();
    }

    /** Gives a newly opened PZ UI surface ownership without overriding a manual release. */
    boolean releaseForUi() {
        boolean wasCaptured = captured();
        if (mode == Mode.GAMEPLAY_CAPTURED) {
            mode = Mode.UI_RELEASED;
            clearUpdates = 0;
        }
        return wasCaptured != captured();
    }
}
