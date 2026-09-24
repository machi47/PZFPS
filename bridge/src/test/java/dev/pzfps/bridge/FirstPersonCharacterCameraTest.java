package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import zombie.core.skinnedmodel.model.Model;
import zombie.iso.Vector3;

final class FirstPersonCharacterCameraTest {
    @Test
    void placesStandingCharacterAtPerspectiveWorldCoordinates() {
        Matrix4f transform = FirstPersonCharacterCamera.modelTransform(
                12.5f, 7.25f, 3.0f, 0.0f, false, new Matrix4f());
        Vector3f feet = transform.transformPosition(new Vector3f());

        assertEquals(12.5f, feet.x, 0.0001f);
        assertEquals(7.25f, feet.z, 0.0001f);
        assertEquals(9.0f, feet.y, 0.0001f);
    }

    @Test
    void matchesInstalledGameBoneToWorldCoordinatesAcrossHeadingsAndPoses() {
        Vector3f[] points = {
            new Vector3f(0.11629251f, 0.07357821f, 0.07825955f), // observed live standing foot
            new Vector3f(0.0447587f, 0.79136145f, -0.08962253f), // observed live standing head
            new Vector3f(0.00423459f, 0.26347837f, -0.31149226f), // live window-climb head
            new Vector3f(-0.31f, 0.48f, 0.27f)
        };
        for (float yaw : new float[] {0f, 0.37f, 1.5707964f, 3.1415927f, 4.71f}) {
            Matrix4f transform = FirstPersonCharacterCamera.modelTransform(
                    12.5f, 7.25f, 13f, yaw, false, new Matrix4f());
            for (Vector3f point : points) {
                Vector3 nativeWorld = new Vector3(point.x, point.y, point.z);
                Model.vectorToWorldCoords(12.5f, 7.25f, 13f, yaw, nativeWorld);
                Vector3f actual = transform.transformPosition(new Vector3f(point));
                assertEquals(nativeWorld.x, actual.x, 0.00001f);
                assertEquals(nativeWorld.z * 3f, actual.y, 0.00001f);
                assertEquals(nativeWorld.y, actual.z, 0.00001f);
            }
        }
    }

    @Test
    void leavesVehicleOccupantScaleForPzSeatTransform() {
        Matrix4f transform = FirstPersonCharacterCamera.modelTransform(
                2.0f, 4.0f, 1.0f, 0.0f, true, new Matrix4f());
        Vector3f oneModelUnit = transform.transformDirection(new Vector3f(0.0f, 1.0f, 0.0f));

        assertEquals(1.0f, oneModelUnit.length(), 0.0001f);
    }

    @Test
    void placesVehicleRootWithoutCharacterScaleOrFootOffset() {
        Matrix4f transform = FirstPersonCharacterCamera.modelTransform(
                8.0f, 9.0f, 2.0f, 0.0f, false, true, new Matrix4f());
        Vector3f origin = transform.transformPosition(new Vector3f());
        Vector3f oneModelUnit = transform.transformDirection(new Vector3f(0.0f, 1.0f, 0.0f));

        assertEquals(8.0f, origin.x, 0.0001f);
        assertEquals(6.0f, origin.y, 0.0001f);
        assertEquals(9.0f, origin.z, 0.0001f);
        assertEquals(1.0f, oneModelUnit.length(), 0.0001f);
    }

    @Test
    void vehicleRootUsesPzCameraAngleInsteadOfItsNaNSlotAnimationAngle() {
        float vehicleAngle = FirstPersonCharacterCamera.cameraAngle(Float.NaN, true);
        assertEquals(0.0f, vehicleAngle);
        assertEquals(0.37f, FirstPersonCharacterCamera.cameraAngle(0.37f, false), 0.0001f);

        float[] matrix = FirstPersonCharacterCamera.modelTransform(
                8.0f, 9.0f, 2.0f, vehicleAngle, false, true, new Matrix4f()).get(new float[16]);
        for (float component : matrix) {
            org.junit.jupiter.api.Assertions.assertTrue(Float.isFinite(component));
        }
    }
}
