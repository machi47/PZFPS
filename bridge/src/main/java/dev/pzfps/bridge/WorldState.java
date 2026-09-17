package dev.pzfps.bridge;

import java.util.List;

/** Immutable primitives copied from the verified game thread. */
public final class WorldState {
    private WorldState() {}

    public record Player(
            long frameSequence,
            long captureNanos,
            long captureEpochMillis,
            long acceptedInputSequence,
            float x,
            float y,
            float z,
            float forwardX,
            float forwardY,
            float verticalAim,
            String actionState,
            boolean aiming,
            boolean attacking,
            boolean inVehicle,
            float eyeHeight) {}

    public record Entity(
            int id,
            String uid,
            String kind,
            String subtype,
            float x,
            float y,
            float z,
            float forwardX,
            float forwardY,
            String state,
            boolean onFloor,
            boolean crawling,
            ActorPose pose) {}

    /** Evaluated client pose; matrices are copied after PZ animation evaluation. */
    public record ActorPose(
            boolean available,
            String model,
            List<String> modelParts,
            String animation,
            float animationTime,
            float animationWeight,
            List<BonePose> bones) {
        public ActorPose {
            modelParts = List.copyOf(modelParts);
            bones = List.copyOf(bones);
        }

        public static ActorPose unavailable() {
            return new ActorPose(false, "", List.of(), "", 0.0f, 0.0f, List.of());
        }
    }

    /** Full affine model transform plus the evaluated bone position in PZ world coordinates. */
    public record BonePose(
            int index,
            int parent,
            String name,
            float m00,
            float m01,
            float m02,
            float m03,
            float m10,
            float m11,
            float m12,
            float m13,
            float m20,
            float m21,
            float m22,
            float m23,
            float m30,
            float m31,
            float m32,
            float m33,
            float worldX,
            float worldY,
            float worldZ) {}

    public record Entities(
            long frameSequence, long captureNanos, long captureEpochMillis, List<Entity> values) {
        public Entities {
            values = List.copyOf(values);
        }
    }

    public record TileObject(
            int index,
            String javaType,
            String objectType,
            String sprite,
            boolean door,
            boolean window,
            boolean north,
            boolean edgeNorth,
            boolean edgeWest,
            boolean open,
            boolean hoppable,
            boolean container,
            boolean solid,
            boolean solidTrans,
            boolean blocksSight,
            WorldItem worldItem) {
        public TileObject {
            worldItem = worldItem == null ? WorldItem.none() : worldItem;
        }

        public TileObject(
                int index,
                String javaType,
                String objectType,
                String sprite,
                boolean door,
                boolean window,
                boolean north,
                boolean edgeNorth,
                boolean edgeWest,
                boolean open,
                boolean hoppable) {
            this(
                    index,
                    javaType,
                    objectType,
                    sprite,
                    door,
                    window,
                    north,
                    edgeNorth,
                    edgeWest,
                    open,
                    hoppable,
                    false,
                    false,
                    false,
                    false,
                    WorldItem.none());
        }

        /** Compatibility constructor for fixtures created before collision facts were captured. */
        public TileObject(
                int index,
                String javaType,
                String objectType,
                String sprite,
                boolean door,
                boolean window,
                boolean north,
                boolean edgeNorth,
                boolean edgeWest,
                boolean open,
                boolean hoppable,
                boolean container,
                WorldItem worldItem) {
            this(
                    index,
                    javaType,
                    objectType,
                    sprite,
                    door,
                    window,
                    north,
                    edgeNorth,
                    edgeWest,
                    open,
                    hoppable,
                    container,
                    false,
                    false,
                    false,
                    worldItem);
        }
    }

    /** Authoritative identity and placement for a dropped/placed inventory item. */
    public record WorldItem(
            boolean present,
            int itemId,
            String fullType,
            String staticModel,
            String worldStaticModel,
            String worldObjectSprite,
            String worldTexture,
            float worldX,
            float worldY,
            float worldZ,
            float rotationX,
            float rotationY,
            float rotationZ,
            float scale,
            boolean extendedPlacement) {
        public static WorldItem none() {
            return new WorldItem(
                    false, -1, "", "", "", "", "", 0.0f, 0.0f, 0.0f,
                    0.0f, 0.0f, 0.0f, 1.0f, false);
        }
    }

    public record Square(
            int localX,
            int localY,
            int z,
            long roomId,
            int visibility,
            int lightR,
            int lightG,
            int lightB,
            boolean solidFloor,
            boolean exterior,
            boolean roof,
            boolean stairs,
            boolean stairsBelow,
            boolean stairTop,
            List<TileObject> objects) {
        public Square {
            objects = List.copyOf(objects);
        }
    }

    public record Chunk(
            int worldX,
            int worldY,
            long sourceRevision,
            long fingerprint,
            List<Square> squares) {
        public Chunk {
            squares = List.copyOf(squares);
        }

        public long key() {
            return ((long) worldX << 32) ^ (worldY & 0xffff_ffffL);
        }
    }
}
