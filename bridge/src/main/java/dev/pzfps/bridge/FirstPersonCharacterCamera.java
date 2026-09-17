package dev.pzfps.bridge;

import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import zombie.core.Core;
import zombie.core.opengl.MatrixStack;
import zombie.core.skinnedmodel.ModelCamera;
import zombie.core.skinnedmodel.model.ModelSlotRenderData;

/** Perspective camera for PZ's already-evaluated character model render data. */
final class FirstPersonCharacterCamera extends ModelCamera {
    private static final float LEVEL_HEIGHT = 3.0f;
    private static final float MODEL_SCALE = Core.ModelScale;
    static final FirstPersonCharacterCamera INSTANCE = new FirstPersonCharacterCamera();

    private boolean pushed;

    private FirstPersonCharacterCamera() {}

    void configure(ModelSlotRenderData data) {
        x = data.x;
        y = data.y;
        z = data.z;
        useAngle = data.animPlayerAngle;
        inVehicle = data.inVehicle;
    }

    @Override
    public void Begin() {
        InProcessWorldRenderer.CameraMatrices camera =
                InProcessWorldRenderer.currentCameraMatrices();
        if (camera == null) {
            throw new IllegalStateException("perspective world camera is unavailable");
        }
        Core core = Core.getInstance();
        MatrixStack projectionStack = core.projectionMatrixStack;
        MatrixStack modelViewStack = core.modelViewMatrixStack;
        Matrix4f projection = projectionStack.alloc().set(camera.projectionView());
        Matrix4f modelView = modelViewStack.alloc().set(camera.worldView());
        modelView.mul(modelTransform(x, y, z, useAngle, inVehicle, new Matrix4f()));
        projectionStack.push(projection);
        modelViewStack.push(modelView);
        pushed = true;
        GL11.glDepthMask(depthMask);
    }

    @Override
    public void End() {
        if (!pushed) return;
        Core core = Core.getInstance();
        core.modelViewMatrixStack.pop();
        core.projectionMatrixStack.pop();
        pushed = false;
    }

    /**
     * Perspective equivalent of the final object-local portion of Core.DoPushIsoStuff(). PZ's
     * character renderer applies passenger transforms after Begin(), so vehicle occupants retain
     * the engine's evaluated seat and vehicle pose.
     */
    static Matrix4f modelTransform(
            float worldX,
            float worldY,
            float worldZ,
            float renderedAngle,
            boolean inVehicle,
            Matrix4f destination) {
        destination.identity()
                .translate(worldX, worldZ * LEVEL_HEIGHT, worldY)
                .scale(
                        inVehicle ? -1.0f : -MODEL_SCALE,
                        inVehicle ? 1.0f : MODEL_SCALE,
                        inVehicle ? 1.0f : MODEL_SCALE)
                .rotateY(renderedAngle + (float) Math.PI);
        if (!inVehicle) destination.translate(0.0f, -0.48f, 0.0f);
        return destination;
    }
}
