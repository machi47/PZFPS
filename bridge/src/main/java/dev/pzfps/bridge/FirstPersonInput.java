package dev.pzfps.bridge;

import org.lwjglx.input.Keyboard;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.input.GameKeyboard;
import zombie.input.Mouse;
import zombie.iso.Vector2;
import zombie.ui.UIManager;

/** Native single-window mouse look and camera-relative movement for the local PZ client. */
public final class FirstPersonInput {
    private static final float MOUSE_RADIANS_PER_PIXEL =
            Float.parseFloat(System.getProperty("pzfps.mouseSensitivity", "0.0025"));
    private static final float MAX_PITCH = (float) Math.toRadians(89.0);
    private static boolean initialized;
    private static boolean captured = true;
    private static boolean waitingForCenter;
    private static float yaw;
    private static float pitch;

    private FirstPersonInput() {}

    /** Runs only at the verified PZ local-player/render boundary. */
    public static void update(IsoPlayer player) {
        if (!initialized) {
            yaw = normalizeYaw(
                    (float) Math.atan2(player.getForwardDirectionY(), player.getForwardDirectionX()));
            pitch = clamp(
                    (float) Math.toRadians(player.getCurrentVerticalAimAngle()),
                    -MAX_PITCH,
                    MAX_PITCH);
            initialized = true;
            centerCursor();
            waitingForCenter = true;
            Mouse.setCursorVisible(false);
            System.out.println("[PZFPS input] mouse look captured; F1 releases/captures the UI cursor");
            return;
        }

    }

    /** Consumes each PZ mouse poll at most once, after Mouse.update has published it. */
    public static void onMouseUpdate() {
        if (!initialized) return;
        boolean releaseForUi = GameKeyboard.isKeyPressed(Keyboard.KEY_ESCAPE)
                || UIManager.isModalVisible()
                || UIManager.isForceCursorVisible();
        if (releaseForUi && captured) {
            captured = false;
            waitingForCenter = false;
            Mouse.setCursorVisible(true);
            System.out.println("[PZFPS input] mouse look released for UI");
            return;
        }
        if (GameKeyboard.isKeyPressed(Keyboard.KEY_F1)) {
            captured = !captured;
            Mouse.setCursorVisible(!captured);
            if (captured) {
                centerCursor();
                waitingForCenter = true;
            }
            System.out.printf("[PZFPS input] mouse look captured=%s%n", captured);
            return;
        }
        if (!captured) {
            if (Mouse.isButtonPressed(Mouse.LMB) && !UIManager.isForceCursorVisible()) {
                captured = true;
                centerCursor();
                waitingForCenter = true;
                Mouse.setCursorVisible(false);
                System.out.println("[PZFPS input] mouse look recaptured by world click");
            }
            return;
        }

        Core core = Core.getInstance();
        int centerX = Math.max(1, core.getScreenWidth()) / 2;
        int centerY = Math.max(1, core.getScreenHeight()) / 2;
        int mouseX = Mouse.getXA();
        int mouseY = Mouse.getYA();
        if (waitingForCenter) {
            if (mouseX == centerX && mouseY == centerY) waitingForCenter = false;
            return;
        }
        int deltaX = mouseX - centerX;
        int deltaY = mouseY - centerY;
        if (deltaX != 0 || deltaY != 0) {
            yaw = normalizeYaw(yaw + deltaX * MOUSE_RADIANS_PER_PIXEL);
            pitch = clamp(pitch - deltaY * MOUSE_RADIANS_PER_PIXEL, -MAX_PITCH, MAX_PITCH);
            centerCursor();
            waitingForCenter = true;
        }
        Mouse.setCursorVisible(false);
    }

    public static boolean isCaptured() {
        return initialized && captured;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static Vector2 movementVector(Vector2 value) {
        return isCaptured() ? rotateMovement(value, yaw) : value;
    }

    public static Vector2 aimVector(Vector2 value) {
        if (!isCaptured()) return value;
        return value.set((float) Math.cos(yaw), (float) Math.sin(yaw));
    }

    static Vector2 rotateMovement(Vector2 value, float cameraYaw) {
        if (value.getLengthSquared() == 0.0f) return value;
        float speed = value.getLength();
        float forward = -value.y / speed;
        float strafe = value.x / speed;
        float sin = (float) Math.sin(cameraYaw);
        float cos = (float) Math.cos(cameraYaw);
        value.set(forward * cos - strafe * sin, forward * sin + strafe * cos);
        return value.setLength(speed);
    }

    private static void centerCursor() {
        Core core = Core.getInstance();
        Mouse.setXY(Math.max(1, core.getScreenWidth()) / 2, Math.max(1, core.getScreenHeight()) / 2);
    }

    private static float normalizeYaw(float value) {
        float twoPi = (float) (Math.PI * 2.0);
        value %= twoPi;
        return value < 0.0f ? value + twoPi : value;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
