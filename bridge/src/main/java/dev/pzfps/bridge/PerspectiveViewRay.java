package dev.pzfps.bridge;

/**
 * One perspective-view contract shared by rendering, the visible reticle, interactions and
 * combat. The ray uses renderer coordinates: world X, vertical Y, world Y as Z.
 */
final class PerspectiveViewRay {
    static final float LEVEL_HEIGHT = 3.0f;

    private PerspectiveViewRay() {}

    record Orientation(float yaw, float pitch) {
        float horizontalX() {
            return (float) Math.cos(yaw);
        }

        float horizontalY() {
            return (float) Math.sin(yaw);
        }

        Direction direction() {
            float horizontal = (float) Math.cos(pitch);
            return new Direction(
                    horizontalX() * horizontal,
                    (float) Math.sin(pitch),
                    horizontalY() * horizontal);
        }
    }

    record Direction(float x, float verticalY, float worldY) {}

    record Ray(
            float originX,
            float originY,
            float originZ,
            float directionX,
            float directionY,
            float directionZ) {}

    /** Returns the live mouse/external-input view, or null before FPS input is active. */
    static Orientation currentOrientation() {
        InputState.Sample input = InputState.current();
        if (input.active()) return new Orientation(input.yaw(), input.pitch());
        if (FirstPersonInput.isPerspectiveActive()) {
            return new Orientation(FirstPersonInput.yaw(), FirstPersonInput.pitch());
        }
        return null;
    }

    /** Uses native character facing only before a perspective view has been established. */
    static Orientation captureOrientation(
            float bodyForwardX,
            float bodyForwardY,
            float nativePitchRadians) {
        InputState.Sample input = InputState.current();
        return captureOrientation(
                bodyForwardX,
                bodyForwardY,
                nativePitchRadians,
                input,
                FirstPersonInput.isPerspectiveActive(),
                FirstPersonInput.yaw(),
                FirstPersonInput.pitch());
    }

    static Orientation captureOrientation(
            float bodyForwardX,
            float bodyForwardY,
            float nativePitchRadians,
            InputState.Sample input,
            boolean nativePerspectiveActive,
            float nativeYaw,
            float nativePitch) {
        if (input.active()) return new Orientation(input.yaw(), input.pitch());
        if (nativePerspectiveActive) return new Orientation(nativeYaw, nativePitch);
        float length = (float) Math.hypot(bodyForwardX, bodyForwardY);
        float yaw = length < 0.0001f
                ? 0.0f
                : (float) Math.atan2(bodyForwardY / length, bodyForwardX / length);
        return new Orientation(yaw, nativePitchRadians);
    }

    /** Reconstructs the exact centre-screen ray carried by an immutable player snapshot. */
    static Ray fromPlayer(WorldState.Player player) {
        return fromPlayer(player, player.eyeHeight());
    }

    static Ray fromPlayer(WorldState.Player player, float eyeHeight) {
        float length = (float) Math.hypot(player.forwardX(), player.forwardY());
        float horizontalX = length < 0.0001f ? 1.0f : player.forwardX() / length;
        float horizontalY = length < 0.0001f ? 0.0f : player.forwardY() / length;
        float horizontal = (float) Math.cos(player.verticalAim());
        return new Ray(
                player.x(),
                player.z() * LEVEL_HEIGHT + eyeHeight,
                player.y(),
                horizontalX * horizontal,
                (float) Math.sin(player.verticalAim()),
                horizontalY * horizontal);
    }
}
