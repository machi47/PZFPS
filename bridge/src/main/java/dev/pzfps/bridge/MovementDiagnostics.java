package dev.pzfps.bridge;

import zombie.characters.IsoPlayer;

/** Measures camera-relative movement behavior without changing PZ's collision-owned result. */
final class MovementDiagnostics {
    private static final int REPORT_SAMPLES = 180;
    private static final float MINIMUM_DISPLACEMENT = 0.00001f;
    private static float startX;
    private static float startY;
    private static FirstPersonInput.MovementRequest deferredRequest =
            FirstPersonInput.MovementRequest.inactive();
    private static boolean sampling;
    private static int requestedSamples;
    private static int movingSamples;
    private static int stationarySamples;
    private static int opposedSamples;
    private static double alignmentSum;
    private static double displacementSum;

    private MovementDiagnostics() {}

    static void begin(IsoPlayer player) {
        sampling = player != null;
        if (!sampling) return;
        // Animation evaluates the current intent near the end of one player update and applies
        // its deferred/root displacement at the start of the next. nextX/nextY are the
        // collision-owned movement target changed by moveUnmodded(); getX/getY are not committed
        // at this boundary and made the old same-update report read stationary while moving.
        deferredRequest = FirstPersonInput.deferredMovementRequest();
        startX = player.getNextX();
        startY = player.getNextY();
    }

    static void end(IsoPlayer player) {
        if (!sampling || player == null) return;
        sampling = false;
        FirstPersonInput.MovementRequest request = deferredRequest;
        if (!request.active()) return;

        float actualX = player.getNextX() - startX;
        float actualY = player.getNextY() - startY;
        float actualLength = (float) Math.hypot(actualX, actualY);
        requestedSamples++;
        if (actualLength <= MINIMUM_DISPLACEMENT) {
            stationarySamples++;
        } else {
            movingSamples++;
            displacementSum += actualLength;
            float alignment = alignment(
                    request.worldX(), request.worldY(), actualX, actualY);
            alignmentSum += alignment;
            if (alignment < 0.0f) opposedSamples++;
        }

        if (requestedSamples < REPORT_SAMPLES) return;
        double meanAlignment = movingSamples == 0 ? Double.NaN : alignmentSum / movingSamples;
        double meanDisplacement = movingSamples == 0 ? 0.0 : displacementSum / movingSamples;
        System.out.printf(
                "[PZFPS movement] requestedUpdates=%d moving=%d stationary=%d opposed=%d meanDirectionAlignment=%.4f meanDisplacementPerUpdate=%.6f; stationary includes collision/action constraints, not renderer FPS%n",
                requestedSamples,
                movingSamples,
                stationarySamples,
                opposedSamples,
                meanAlignment,
                meanDisplacement);
        resetWindow();
    }

    static float alignment(
            float desiredX, float desiredY, float actualX, float actualY) {
        float desiredLength = (float) Math.hypot(desiredX, desiredY);
        float actualLength = (float) Math.hypot(actualX, actualY);
        if (desiredLength <= MINIMUM_DISPLACEMENT
                || actualLength <= MINIMUM_DISPLACEMENT) {
            return Float.NaN;
        }
        return (desiredX * actualX + desiredY * actualY)
                / (desiredLength * actualLength);
    }

    private static void resetWindow() {
        requestedSamples = 0;
        movingSamples = 0;
        stationarySamples = 0;
        opposedSamples = 0;
        alignmentSum = 0.0;
        displacementSum = 0.0;
    }
}
