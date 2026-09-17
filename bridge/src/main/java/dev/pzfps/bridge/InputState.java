package dev.pzfps.bridge;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import zombie.iso.Vector2;

/** Latest renderer input. It is sampled by PZ; it never sets a world position. */
public final class InputState {
    public static final int AIM = 1;
    public static final int PRIMARY = 1 << 1;
    public static final int INTERACT = 1 << 2;
    public static final int RUN = 1 << 3;
    public static final int SPRINT = 1 << 4;
    public static final int CROUCH = 1 << 5;
    public static final int RELOAD = 1 << 6;
    public static final int SHOUT = 1 << 7;

    private static final AtomicReference<Sample> CURRENT =
            new AtomicReference<>(Sample.inactive());

    private InputState() {}

    public static void set(Sample sample) {
        CURRENT.set(sample.sanitized());
    }

    public static void deactivate() {
        CURRENT.set(Sample.inactive());
    }

    public static Sample current() {
        return CURRENT.get();
    }

    public static Vector2 movementVector(Vector2 result) {
        Sample sample = current();
        if (!sample.active()) return FirstPersonInput.movementVector(result);
        return FirstPersonInput.rotateDigitalMovement(
                result, sample.yaw(), sample.forward(), sample.strafe());
    }

    public static Vector2 aimVector(Vector2 result) {
        Sample sample = current();
        if (!sample.active()) return FirstPersonInput.aimVector(result);
        return result.set((float) Math.cos(sample.yaw()), (float) Math.sin(sample.yaw()));
    }

    public static boolean isButtonDown(int mask) {
        Sample sample = current();
        return sample.active() && (sample.buttons() & mask) != 0;
    }

    public static boolean isButtonPressed(int mask) {
        Sample sample = current();
        return sample.active()
                && (sample.buttons() & mask) != 0
                && (sample.previousButtons() & mask) == 0;
    }

    public static boolean isActionDown(String action) {
        if (action == null) return false;
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "interact" -> isButtonDown(INTERACT);
            case "run" -> isButtonDown(RUN);
            case "sprint" -> isButtonDown(SPRINT);
            case "crouch" -> isButtonDown(CROUCH);
            case "shout" -> isButtonDown(SHOUT);
            default -> false;
        };
    }

    public static boolean isActionPressed(String action) {
        if (action == null) return false;
        return switch (action.toLowerCase(Locale.ROOT)) {
            case "interact" -> isButtonPressed(INTERACT);
            case "crouch" -> isButtonPressed(CROUCH);
            case "reload" -> isButtonPressed(RELOAD);
            case "shout" -> isButtonPressed(SHOUT);
            default -> false;
        };
    }

    public record Sample(
            boolean active,
            long sequence,
            float strafe,
            float forward,
            float yaw,
            float pitch,
            int buttons,
            int previousButtons) {

        public static Sample inactive() {
            return new Sample(false, 0, 0, 0, 0, 0, 0, 0);
        }

        public Sample sanitized() {
            return new Sample(
                    active,
                    Math.max(0, sequence),
                    clamp(strafe, -1.0f, 1.0f),
                    clamp(forward, -1.0f, 1.0f),
                    normalizeYaw(yaw),
                    clamp(pitch, -1.553343f, 1.553343f),
                    buttons,
                    previousButtons);
        }

        private static float clamp(float value, float minimum, float maximum) {
            if (!Float.isFinite(value)) return 0.0f;
            return Math.max(minimum, Math.min(maximum, value));
        }

        private static float normalizeYaw(float value) {
            if (!Float.isFinite(value)) return 0.0f;
            float twoPi = (float) (Math.PI * 2.0);
            value %= twoPi;
            return value < 0.0f ? value + twoPi : value;
        }
    }
}
