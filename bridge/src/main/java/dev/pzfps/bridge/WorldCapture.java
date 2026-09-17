package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.lwjgl.util.vector.Matrix4f;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.core.skinnedmodel.animation.AnimationMultiTrack;
import zombie.core.skinnedmodel.animation.AnimationPlayer;
import zombie.core.skinnedmodel.animation.AnimationTrack;
import zombie.core.skinnedmodel.model.Model;
import zombie.core.skinnedmodel.model.ModelInstance;
import zombie.core.skinnedmodel.model.SkinningData;
import zombie.inventory.InventoryItem;
import zombie.iso.IsoCell;
import zombie.iso.IsoChunk;
import zombie.iso.IsoChunkMap;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMovingObject;
import zombie.iso.IsoObject;
import zombie.iso.Vector3;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.sprite.IsoSprite;
import zombie.util.list.PZArrayList;
import zombie.vehicles.BaseVehicle;

/** Copies game-owned state to immutable primitive records on the game thread. */
public final class WorldCapture {
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int MAX_POSE_BONES = 128;
    private static final int MAX_MODEL_PARTS = 64;
    private static final float POSE_RADIUS_SQUARED = 50.0f * 50.0f;

    private WorldCapture() {}

    public static WorldState.Player player(
            IsoPlayer player, long sequence, long captureNanos, long captureEpochMillis) {
        return new WorldState.Player(
                sequence,
                captureNanos,
                captureEpochMillis,
                InputState.current().sequence(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getForwardDirectionX(),
                player.getForwardDirectionY(),
                cameraPitchRadians(player),
                nonNull(player.getCurrentActionContextStateName()),
                player.isAiming(),
                player.isAttacking(),
                player.getVehicle() != null,
                eyeHeight(player));
    }

    private static float eyeHeight(IsoPlayer player) {
        if (player.getVehicle() != null) return 1.25f;
        if (player.isCrawling() || player.isOnFloor()) return 0.48f;
        if (player.isSitOnGround() || player.isSittingOnFurniture()) return 0.88f;
        if (player.isSneaking()) return 1.16f;
        return 1.62f;
    }

    private static float cameraPitchRadians(IsoPlayer player) {
        InputState.Sample input = InputState.current();
        if (input.active()) return input.pitch();
        if (FirstPersonInput.isCaptured()) return FirstPersonInput.pitch();
        return (float) Math.toRadians(player.getCurrentVerticalAimAngle());
    }

    public static WorldState.Entities entities(
            IsoPlayer player, long sequence, long captureNanos, long captureEpochMillis) {
        IsoCell cell = player.getCell();
        ArrayList<WorldState.Entity> values = new ArrayList<>();
        for (IsoMovingObject object : cell.getObjectList()) {
            if (object == null || object == player || object.isDestroyed()) continue;
            String kind = object instanceof BaseVehicle
                    ? "vehicle"
                    : object.isZombie() ? "zombie" : object.isCharacter() ? "character" : "moving";
            String subtype = object instanceof BaseVehicle vehicle
                    ? nonNull(vehicle.getScriptName())
                    : nonNull(object.getObjectName());
            float forwardX = 0.0f;
            float forwardY = 0.0f;
            String state = "";
            if (object instanceof IsoGameCharacter character) {
                forwardX = character.getForwardDirectionX();
                forwardY = character.getForwardDirectionY();
                state = nonNull(character.getCurrentStateName());
            }
            WorldState.ActorPose pose = object instanceof IsoGameCharacter character
                    && distanceSquared(player, character) <= POSE_RADIUS_SQUARED
                    ? actorPose(character)
                    : WorldState.ActorPose.unavailable();
            values.add(new WorldState.Entity(
                    object.getID(),
                    nonNull(object.getUID()),
                    kind,
                    subtype,
                    object.getX(),
                    object.getY(),
                    object.getZ(),
                    forwardX,
                    forwardY,
                    state,
                    object.isOnFloor(),
                    object.isCrawling(),
                    pose));
        }
        values.sort(Comparator.comparingInt(WorldState.Entity::id));
        return new WorldState.Entities(sequence, captureNanos, captureEpochMillis, values);
    }

    private static WorldState.ActorPose actorPose(IsoGameCharacter character) {
        AnimationPlayer animationPlayer = character.getAnimationPlayer();
        if (animationPlayer == null || !animationPlayer.isReady() || !animationPlayer.hasSkinningData()) {
            return WorldState.ActorPose.unavailable();
        }
        SkinningData skinning = animationPlayer.getSkinningData();
        if (skinning == null) return WorldState.ActorPose.unavailable();

        ModelInstance rootModel = character.getModelInstance();
        String model = modelIdentity(rootModel);
        ArrayList<String> modelParts = new ArrayList<>();
        collectModelParts(rootModel, modelParts);

        AnimationTrack primary = primaryTrack(animationPlayer.getMultiTrack());
        String animation = primary == null ? "" : nonNull(primary.getName());
        float animationTime = primary == null ? 0.0f : primary.getCurrentAnimationTime();
        float animationWeight = primary == null ? 0.0f : primary.getBlendWeight();

        int boneCount = Math.min(
                MAX_POSE_BONES,
                Math.min(animationPlayer.getModelTransformsCount(), skinning.numBones()));
        String[] names = boneNames(skinning, boneCount);
        ArrayList<WorldState.BonePose> bones = new ArrayList<>(boneCount);
        Vector3 world = new Vector3();
        for (int index = 0; index < boneCount; index++) {
            Matrix4f matrix = animationPlayer.getModelTransformAt(index);
            if (matrix == null) continue;
            Model.boneToWorldCoords(character, index, world);
            bones.add(new WorldState.BonePose(
                    index,
                    skinning.getParentBoneIdx(index),
                    names[index],
                    matrix.m00,
                    matrix.m01,
                    matrix.m02,
                    matrix.m03,
                    matrix.m10,
                    matrix.m11,
                    matrix.m12,
                    matrix.m13,
                    matrix.m20,
                    matrix.m21,
                    matrix.m22,
                    matrix.m23,
                    matrix.m30,
                    matrix.m31,
                    matrix.m32,
                    matrix.m33,
                    world.x,
                    world.y,
                    world.z));
        }
        return new WorldState.ActorPose(
                !bones.isEmpty(), model, modelParts, animation, animationTime, animationWeight, bones);
    }

    private static AnimationTrack primaryTrack(AnimationMultiTrack tracks) {
        if (tracks == null) return null;
        AnimationTrack strongest = null;
        for (int index = 0; index < tracks.getTrackCount(); index++) {
            AnimationTrack track = tracks.getTrackAt(index);
            if (track == null || !track.hasClip() || !track.isPlaying) continue;
            if (track.isPrimary) return track;
            if (strongest == null || track.getBlendWeight() > strongest.getBlendWeight()) {
                strongest = track;
            }
        }
        return strongest;
    }

    private static String[] boneNames(SkinningData skinning, int boneCount) {
        String[] names = new String[boneCount];
        for (int index = 0; index < boneCount; index++) names[index] = "bone_" + index;
        for (Map.Entry<String, Integer> entry : skinning.boneIndices.entrySet()) {
            Integer index = entry.getValue();
            if (index != null && index >= 0 && index < boneCount) names[index] = nonNull(entry.getKey());
        }
        return names;
    }

    private static void collectModelParts(ModelInstance instance, List<String> values) {
        if (instance == null || values.size() >= MAX_MODEL_PARTS) return;
        String identity = modelIdentity(instance);
        if (!identity.isEmpty()) values.add(identity);
        for (ModelInstance child : instance.sub) {
            if (values.size() >= MAX_MODEL_PARTS) break;
            collectModelParts(child, values);
        }
    }

    private static String modelIdentity(ModelInstance instance) {
        if (instance == null) return "";
        if (instance.modelScript != null) return nonNull(instance.modelScript.getFullType());
        return instance.model == null ? "" : nonNull(instance.model.name);
    }

    private static float distanceSquared(IsoPlayer player, IsoGameCharacter character) {
        float dx = character.getX() - player.getX();
        float dy = character.getY() - player.getY();
        return dx * dx + dy * dy;
    }

    public static Set<Long> loadedChunkKeys(IsoPlayer player, int chunkRadius) {
        IsoCell cell = player.getCell();
        int blockSize = IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        int centerX = Math.floorDiv((int) Math.floor(player.getX()), blockSize);
        int centerY = Math.floorDiv((int) Math.floor(player.getY()), blockSize);
        HashSet<Long> keys = new HashSet<>();
        for (int dy = -chunkRadius; dy <= chunkRadius; dy++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                IsoChunk chunk = cell.getChunk(centerX + dx, centerY + dy);
                if (chunk != null && chunk.loaded) {
                    keys.add(key(chunk.wx, chunk.wy));
                }
            }
        }
        return keys;
    }

    public static List<IsoChunk> loadedChunks(IsoPlayer player, int chunkRadius) {
        IsoCell cell = player.getCell();
        int blockSize = IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        int centerX = Math.floorDiv((int) Math.floor(player.getX()), blockSize);
        int centerY = Math.floorDiv((int) Math.floor(player.getY()), blockSize);
        ArrayList<IsoChunk> chunks = new ArrayList<>();
        for (int dy = -chunkRadius; dy <= chunkRadius; dy++) {
            for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                IsoChunk chunk = cell.getChunk(centerX + dx, centerY + dy);
                if (chunk != null && chunk.loaded) chunks.add(chunk);
            }
        }
        return chunks;
    }

    public static long fingerprint(IsoChunk chunk, int playerIndex) {
        long hash = mix(FNV_OFFSET, chunk.wx);
        hash = mix(hash, chunk.wy);
        hash = mix(hash, chunk.getMinLevel());
        hash = mix(hash, chunk.getMaxLevel());
        int size = IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    IsoGridSquare square = chunk.getGridSquare(x, y, z);
                    if (square == null) {
                        hash = mix(hash, -1);
                        continue;
                    }
                    hash = mix(hash, square.isSolidFloor() ? 1 : 0);
                    hash = mix(hash, squareTopologyFlags(square));
                    PZArrayList<IsoObject> objects = square.getObjects();
                    hash = mix(hash, objects.size());
                    for (int index = 0; index < objects.size(); index++) {
                        IsoObject object = objects.get(index);
                        if (object == null) {
                            hash = mix(hash, -1);
                            continue;
                        }
                        WorldState.TileObject value = tileObject(index, object);
                        hash = mix(hash, stringHash(value.sprite()));
                        hash = mix(hash, objectFlags(value));
                        hash = mix(hash, worldItemFingerprint(value.worldItem()));
                    }
                }
            }
        }
        return hash;
    }

    public static WorldState.Chunk chunk(IsoChunk chunk, int playerIndex, long fingerprint) {
        int size = IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        ArrayList<WorldState.Square> squares = new ArrayList<>();
        for (int z = chunk.getMinLevel(); z <= chunk.getMaxLevel(); z++) {
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    IsoGridSquare square = chunk.getGridSquare(x, y, z);
                    if (square == null) continue;
                    ArrayList<WorldState.TileObject> objects = new ArrayList<>();
                    PZArrayList<IsoObject> sourceObjects = square.getObjects();
                    for (int index = 0; index < sourceObjects.size(); index++) {
                        IsoObject object = sourceObjects.get(index);
                        if (object == null) continue;
                        objects.add(tileObject(index, object));
                    }
                    squares.add(new WorldState.Square(
                            x,
                            y,
                            z,
                            square.getRoomID(),
                            visibility(square, playerIndex),
                            square.GetRLightLevel(),
                            square.GetGLightLevel(),
                            square.GetBLightLevel(),
                            square.isSolidFloor(),
                            square.isOutside(),
                            square.haveRoof,
                            square.HasStairs(),
                            square.HasStairsBelow(),
                            square.HasStairTop(),
                            objects));
                }
            }
        }
        return new WorldState.Chunk(
                chunk.wx, chunk.wy, chunk.revision, fingerprint, squares);
    }

    private static WorldState.TileObject tileObject(int index, IsoObject object) {
        boolean door = object instanceof IsoDoor
                || (object instanceof IsoThumpable thumpable && thumpable.isDoor());
        boolean window = object instanceof IsoWindow
                || (object instanceof IsoThumpable thumpable
                        && (thumpable.isWindowN() || thumpable.isWindowW()));
        boolean north = object instanceof IsoDoor isoDoor
                ? isoDoor.getNorth()
                : object instanceof IsoWindow isoWindow
                        ? isoWindow.getNorth()
                        : object instanceof IsoThumpable thumpable && thumpable.getNorth();
        boolean open = object instanceof IsoDoor isoDoor
                ? isoDoor.isOpen()
                : object instanceof IsoWindow isoWindow
                        ? isoWindow.IsOpen()
                        : object instanceof IsoThumpable thumpable && thumpable.IsOpen();
        boolean edgeNorth = object.isWallN()
                || object.hasProperty(IsoFlagType.collideN)
                || ((door || window) && north);
        boolean edgeWest = object.isWallW()
                || object.hasProperty(IsoFlagType.collideW)
                || ((door || window) && !north);
        return new WorldState.TileObject(
                index,
                object.getClass().getName(),
                object.getType() == null ? "" : object.getType().name(),
                spriteName(object),
                door,
                window,
                north,
                edgeNorth,
                edgeWest,
                open,
                object.isHoppable(),
                object.getContainerCount() > 0 || object.getContainer() != null,
                object.hasProperty(IsoFlagType.solid),
                object.hasProperty(IsoFlagType.solidtrans),
                object.hasProperty(IsoFlagType.blocksight),
                worldItem(object));
    }

    private static int visibility(IsoGridSquare square, int playerIndex) {
        int flags = 0;
        if (square.isSeen(playerIndex)) flags |= 1;
        if (square.isCouldSee(playerIndex)) flags |= 1 << 1;
        if (square.isCanSee(playerIndex)) flags |= 1 << 2;
        return flags;
    }

    private static int objectFlags(WorldState.TileObject value) {
        int flags = 0;
        if (value.door()) flags |= 1;
        if (value.window()) flags |= 1 << 1;
        if (value.north()) flags |= 1 << 2;
        if (value.open()) flags |= 1 << 3;
        if (value.hoppable()) flags |= 1 << 4;
        if (value.edgeNorth()) flags |= 1 << 5;
        if (value.edgeWest()) flags |= 1 << 6;
        if (value.container()) flags |= 1 << 7;
        if (value.solid()) flags |= 1 << 8;
        if (value.solidTrans()) flags |= 1 << 9;
        if (value.blocksSight()) flags |= 1 << 10;
        return flags;
    }

    private static WorldState.WorldItem worldItem(IsoObject object) {
        if (!(object instanceof IsoWorldInventoryObject worldObject)) {
            return WorldState.WorldItem.none();
        }
        InventoryItem item = worldObject.getItem();
        if (item == null) return WorldState.WorldItem.none();
        return new WorldState.WorldItem(
                true,
                item.getID(),
                nonNull(item.getFullType()),
                nonNull(item.getStaticModel()),
                nonNull(item.getWorldStaticModel()),
                nonNull(item.getWorldObjectSprite()),
                nonNull(item.getWorldTexture()),
                worldObject.getWorldPosX(),
                worldObject.getWorldPosY(),
                worldObject.getWorldPosZ(),
                item.getWorldXRotation(),
                item.getWorldYRotation(),
                item.getWorldZRotation(),
                item.worldScale,
                worldObject.isExtendedPlacement());
    }

    private static long worldItemFingerprint(WorldState.WorldItem item) {
        if (!item.present()) return 0;
        long hash = mix(FNV_OFFSET, item.itemId());
        hash = mix(hash, stringHash(item.fullType()));
        hash = mix(hash, stringHash(item.staticModel()));
        hash = mix(hash, stringHash(item.worldStaticModel()));
        hash = mix(hash, stringHash(item.worldObjectSprite()));
        hash = mix(hash, stringHash(item.worldTexture()));
        hash = mix(hash, Float.floatToIntBits(item.worldX()));
        hash = mix(hash, Float.floatToIntBits(item.worldY()));
        hash = mix(hash, Float.floatToIntBits(item.worldZ()));
        hash = mix(hash, Float.floatToIntBits(item.rotationX()));
        hash = mix(hash, Float.floatToIntBits(item.rotationY()));
        hash = mix(hash, Float.floatToIntBits(item.rotationZ()));
        hash = mix(hash, Float.floatToIntBits(item.scale()));
        return mix(hash, item.extendedPlacement() ? 1 : 0);
    }

    private static int squareTopologyFlags(IsoGridSquare square) {
        int flags = 0;
        if (square.HasStairs()) flags |= 1;
        if (square.HasStairsBelow()) flags |= 1 << 1;
        if (square.HasStairTop()) flags |= 1 << 2;
        return flags;
    }

    private static String spriteName(IsoObject object) {
        IsoSprite sprite = object.getSprite();
        return sprite == null ? nonNull(object.getSpriteName()) : nonNull(sprite.getName());
    }

    private static long key(int worldX, int worldY) {
        return ((long) worldX << 32) ^ (worldY & 0xffff_ffffL);
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * FNV_PRIME;
    }

    private static long stringHash(String value) {
        long hash = FNV_OFFSET;
        for (int index = 0; index < value.length(); index++) {
            hash = mix(hash, value.charAt(index));
        }
        return hash;
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
