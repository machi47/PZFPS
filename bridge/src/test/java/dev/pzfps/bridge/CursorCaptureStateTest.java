package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class CursorCaptureStateTest {
    @Test
    void manualReleaseNeverRecapturesMerelyBecauseUiClears() {
        CursorCaptureState state = new CursorCaptureState(2);

        assertTrue(state.update(true, false));
        assertEquals(CursorCaptureState.Mode.USER_RELEASED, state.mode());
        assertFalse(state.update(false, false));
        assertFalse(state.update(false, false));
        assertFalse(state.captured());

        assertTrue(state.update(true, false));
        assertEquals(CursorCaptureState.Mode.GAMEPLAY_CAPTURED, state.mode());
    }

    @Test
    void uiReleaseNeedsConsecutiveClearUpdatesBeforeRecapture() {
        CursorCaptureState state = new CursorCaptureState(2);

        assertTrue(state.update(false, true));
        assertEquals(CursorCaptureState.Mode.UI_RELEASED, state.mode());
        assertFalse(state.update(false, false));
        assertFalse(state.captured());
        assertTrue(state.update(false, false));
        assertTrue(state.captured());
    }

    @Test
    void renewedUiOwnershipResetsRecaptureGrace() {
        CursorCaptureState state = new CursorCaptureState(2);

        state.releaseForUi();
        state.update(false, false);
        state.update(false, true);
        assertFalse(state.update(false, false));
        assertFalse(state.captured());
        assertTrue(state.update(false, false));
        assertTrue(state.captured());
    }

    @Test
    void programmaticUiReleaseDoesNotOverrideManualRelease() {
        CursorCaptureState state = new CursorCaptureState(2);

        state.update(true, false);
        assertFalse(state.releaseForUi());
        assertEquals(CursorCaptureState.Mode.USER_RELEASED, state.mode());
    }

    @Test
    void toggleCannotCaptureThroughAnOpenUi() {
        CursorCaptureState state = new CursorCaptureState(2);

        state.update(false, true);
        assertFalse(state.update(true, true));
        assertEquals(CursorCaptureState.Mode.UI_RELEASED, state.mode());
        assertFalse(state.captured());
    }
}
