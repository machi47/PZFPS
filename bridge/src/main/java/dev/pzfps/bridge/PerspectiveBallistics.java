package dev.pzfps.bridge;

import java.util.concurrent.atomic.AtomicBoolean;
import org.joml.Quaternionf;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.physics.BallisticsController;
import zombie.core.physics.Bullet;
import zombie.iso.Vector3;
import zombie.network.GameServer;

/** Adapts the perspective view ray to PZ's existing muzzle and native target queries. */
final class PerspectiveBallistics {
    static final float LEVEL_HEIGHT = 3.0f;
    static final float BALLISTICS_VERTICAL_SCALE = 2.44949f;
    /** Reused only at the verified PZ game-thread combat boundary. */
    private static final Vector3 PZ_DIRECTION = new Vector3();
    private static final Quaternionf CAMERA_ROTATION = new Quaternionf();
    private static final AtomicBoolean FIRST_NATIVE_RAY = new AtomicBoolean();

    private PerspectiveBallistics() {}

    /**
     * B42 expresses muzzle directions in (world X, world Y, floor Z), while the replacement
     * renderer uses a three-unit visual height for one floor. Preserve that coordinate relation
     * so PZ's own normalization, range checks, collision tests and damage remain authoritative.
     */
    static Vector3 pzDirection(float yaw, float pitch, Vector3 destination) {
        float horizontal = (float) Math.cos(pitch);
        return destination.set(
                (float) Math.cos(yaw) * horizontal,
                (float) Math.sin(yaw) * horizontal,
                (float) Math.sin(pitch) / LEVEL_HEIGHT);
    }

    /** Quaternion whose local -Z axis is the requested direction in native Bullet coordinates. */
    static Quaternionf bulletCameraRotation(
            float yaw, float pitch, Quaternionf destination) {
        float horizontal = (float) Math.cos(pitch);
        float x = (float) Math.cos(yaw) * horizontal;
        float y = (float) Math.sin(pitch) * BALLISTICS_VERTICAL_SCALE / LEVEL_HEIGHT;
        float z = (float) Math.sin(yaw) * horizontal;
        float inverseLength = 1.0f / (float) Math.sqrt(x * x + y * y + z * z);
        return destination.rotationTo(
                0.0f, 0.0f, -1.0f,
                x * inverseLength, y * inverseLength, z * inverseLength);
    }

    static void overrideMuzzleDirection(IsoGameCharacter owner, Vector3 direction) {
        if (!isLocalPlayer(owner) || direction == null) return;
        InputState.Sample input = InputState.current();
        if (input.active()) {
            pzDirection(input.yaw(), input.pitch(), direction);
        } else if (FirstPersonInput.isPerspectiveActive()) {
            pzDirection(FirstPersonInput.yaw(), FirstPersonInput.pitch(), direction);
        }
    }

    /** Runs immediately before B42 asks native Bullet for camera/body-part targets. */
    static void configureNativeCameraRay(
            BallisticsController controller, IsoGameCharacter owner) {
        if (!isLocalPlayer(owner) || controller == null || GameServer.server) return;
        InputState.Sample input = InputState.current();
        boolean external = input.active();
        if (!external && !FirstPersonInput.isPerspectiveActive()) return;
        float yaw = external ? input.yaw() : FirstPersonInput.yaw();
        float pitch = external ? input.pitch() : FirstPersonInput.pitch();

        Vector3 muzzle = controller.getMuzzlePosition();
        pzDirection(yaw, pitch, PZ_DIRECTION);
        float bulletX = PZ_DIRECTION.x;
        float bulletY = PZ_DIRECTION.z * BALLISTICS_VERTICAL_SCALE;
        float bulletZ = PZ_DIRECTION.y;
        float inverseLength = 1.0f
                / (float) Math.sqrt(
                        bulletX * bulletX + bulletY * bulletY + bulletZ * bulletZ);
        bulletX *= inverseLength;
        bulletY *= inverseLength;
        bulletZ *= inverseLength;

        int id = owner.getID();
        Bullet.updateBallisticsMuzzleAimDirection(id, bulletX, bulletY, bulletZ);
        Bullet.updateBallisticsAimReticlePosition(
                id, muzzle.x, muzzle.z * BALLISTICS_VERTICAL_SCALE, muzzle.y);
        Quaternionf rotation = bulletCameraRotation(yaw, pitch, CAMERA_ROTATION);
        Bullet.updateBallisticsAimReticleQuaternion(
                id, rotation.x, rotation.y, rotation.z, rotation.w);

        // B42 also admits candidates near this isometric reticle point before the native query.
        // Move it to the muzzle so off-axis isometric cursor targets cannot bypass the FPS ray;
        // the normal forward-ray tolerance still handles valid targets along the view direction.
        controller.getIsoAimingPosition().set(muzzle);

        if (FIRST_NATIVE_RAY.compareAndSet(false, true)) {
            System.out.println(
                    "[PZFPS aim] perspective muzzle and native Bullet camera ray active; PZ target validation retained");
        }
    }

    private static boolean isLocalPlayer(IsoGameCharacter owner) {
        return owner instanceof IsoPlayer player && player == IsoPlayer.getInstance();
    }
}
