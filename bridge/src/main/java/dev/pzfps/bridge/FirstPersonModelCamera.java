package dev.pzfps.bridge;

import org.joml.Matrix4f;
import org.joml.Vector3fc;
import org.lwjgl.opengl.GL11;
import zombie.core.Core;
import zombie.core.opengl.IModelCamera;
import zombie.core.opengl.MatrixStack;

/** Camera adapter that draws PZ's native static models into the replacement perspective depth. */
final class FirstPersonModelCamera implements IModelCamera {
    private static final float LEVEL_HEIGHT = 3.0f;
    private static final float MODEL_SCALE = Core.ModelScale;
    private final float worldX;
    private final float worldY;
    private final float worldZ;
    private final Vector3fc angleDegrees;
    private boolean pushed;

    FirstPersonModelCamera(
            float worldX, float worldY, float worldZ, Vector3fc angleDegrees) {
        this.worldX = worldX;
        this.worldY = worldY;
        this.worldZ = worldZ;
        this.angleDegrees = angleDegrees;
    }

    @Override
    public void Begin() {
        InProcessWorldRenderer.CameraMatrices camera =
                InProcessWorldRenderer.currentCameraMatrices();
        if (camera == null) return;
        Core core = Core.getInstance();
        MatrixStack projectionStack = core.projectionMatrixStack;
        MatrixStack modelViewStack = core.modelViewMatrixStack;
        Matrix4f projection = projectionStack.alloc().set(camera.projectionView());
        Matrix4f modelView = modelViewStack.alloc().set(camera.worldView());
        modelView.mul(modelTransform(
                worldX, worldY, worldZ, angleDegrees, new Matrix4f()));
        projectionStack.push(projection);
        modelViewStack.push(modelView);
        pushed = true;
        GL11.glDepthMask(true);
    }

    @Override
    public void End() {
        if (!pushed) return;
        Core core = Core.getInstance();
        core.modelViewMatrixStack.pop();
        core.projectionMatrixStack.pop();
        pushed = false;
    }

    /** Model-space convention copied from B42's world-item camera, with a perspective location. */
    static Matrix4f modelTransform(
            float worldX,
            float worldY,
            float worldZ,
            Vector3fc angleDegrees,
            Matrix4f destination) {
        float radians = (float) (Math.PI / 180.0);
        return destination.identity()
                .translate(worldX, worldZ * LEVEL_HEIGHT, worldY)
                .scale(-MODEL_SCALE, MODEL_SCALE, MODEL_SCALE)
                .translate(0.0f, -0.48f, 0.0f)
                .rotateX(angleDegrees.x() * radians)
                .rotateY(angleDegrees.y() * radians)
                .rotateZ(angleDegrees.z() * radians);
    }
}
