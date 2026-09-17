package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MovementDiagnosticsTest {
    @Test
    void measuresWorldDirectionRatherThanIsometricInputAxes() {
        assertEquals(1.0f, MovementDiagnostics.alignment(1, 1, 0.25f, 0.25f), 0.0001f);
        assertEquals(0.0f, MovementDiagnostics.alignment(1, 0, 0, 1), 0.0001f);
        assertEquals(-1.0f, MovementDiagnostics.alignment(1, 0, -0.1f, 0), 0.0001f);
        assertTrue(Float.isNaN(MovementDiagnostics.alignment(1, 0, 0, 0)));
    }
}
