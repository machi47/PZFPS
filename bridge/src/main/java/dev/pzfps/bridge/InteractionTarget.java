package dev.pzfps.bridge;

import java.util.Collection;
import java.util.Optional;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.util.list.PZArrayList;

/** Perspective selection plus identity-checked re-resolution of a live PZ object. */
final class InteractionTarget {
    private static final float LEVEL_HEIGHT = 3.0f;
    private static final float EDGE_HALF_THICKNESS = 0.08f;
    private static final float TARGET_PADDING = 0.06f;
    private static final float MINIMUM_TARGET_DISTANCE = 0.05f;

    record Reference(
            int squareX,
            int squareY,
            int z,
            int objectIndex,
            String javaType,
            String objectType,
            String sprite,
            int worldItemId) {}

    private InteractionTarget() {}

    static Optional<Reference> nearestInteractive(
            WorldState.Player player, Collection<WorldState.Chunk> chunks, float maximumReach) {
        if (maximumReach <= 0.0f) throw new IllegalArgumentException("maximumReach must be positive");
        float forwardLength = (float) Math.hypot(player.forwardX(), player.forwardY());
        if (forwardLength < 0.0001f) return Optional.empty();
        float horizontal = (float) Math.cos(player.verticalAim());
        float directionX = player.forwardX() / forwardLength * horizontal;
        float directionY = (float) Math.sin(player.verticalAim());
        float directionZ = player.forwardY() / forwardLength * horizontal;
        float originY = player.z() * LEVEL_HEIGHT + player.eyeHeight();
        float bestDistance = Float.POSITIVE_INFINITY;
        Reference best = null;
        int chunkSize = zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;

        for (WorldState.Chunk chunk : chunks) {
            int chunkX = chunk.worldX() * chunkSize;
            int chunkY = chunk.worldY() * chunkSize;
            for (WorldState.Square square : chunk.squares()) {
                if (Math.abs(square.z() - player.z()) > 0.51f) continue;
                int squareX = chunkX + square.localX();
                int squareY = chunkY + square.localY();
                for (WorldState.TileObject object : square.objects()) {
                    if (!isInteractive(object)) continue;
                    float[] bounds = bounds(squareX, squareY, square.z(), object);
                    float distance = rayBoxDistance(
                            player.x(),
                            originY,
                            player.y(),
                            directionX,
                            directionY,
                            directionZ,
                            bounds,
                            maximumReach);
                    if (!Float.isFinite(distance) || distance >= bestDistance) continue;
                    bestDistance = distance;
                    best = reference(squareX, squareY, square.z(), object);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Must be called on PZ's game thread immediately before requesting an action. */
    static IsoObject resolveLive(IsoPlayer player, Reference reference, float maximumReach) {
        if (player == null || reference == null) return null;
        float dx = reference.squareX() + 0.5f - player.getX();
        float dy = reference.squareY() + 0.5f - player.getY();
        if (dx * dx + dy * dy > maximumReach * maximumReach) return null;
        IsoGridSquare square = player.getCell().getGridSquare(
                reference.squareX(), reference.squareY(), reference.z());
        if (square == null) return null;
        PZArrayList<IsoObject> objects = square.getObjects();
        if (reference.objectIndex() >= 0 && reference.objectIndex() < objects.size()) {
            IsoObject indexed = objects.get(reference.objectIndex());
            if (matches(indexed, reference)) return indexed;
        }

        IsoObject unique = null;
        for (int index = 0; index < objects.size(); index++) {
            IsoObject candidate = objects.get(index);
            if (!matches(candidate, reference)) continue;
            if (unique != null) return null;
            unique = candidate;
        }
        return unique;
    }

    private static boolean isInteractive(WorldState.TileObject object) {
        return object.door() || object.window() || object.container();
    }

    private static float[] bounds(
            int squareX, int squareY, int z, WorldState.TileObject object) {
        float minimumX = squareX - TARGET_PADDING;
        float maximumX = squareX + 1.0f + TARGET_PADDING;
        float minimumZ = squareY - TARGET_PADDING;
        float maximumZ = squareY + 1.0f + TARGET_PADDING;
        if (object.edgeNorth() && !object.edgeWest()) {
            minimumZ = squareY - EDGE_HALF_THICKNESS;
            maximumZ = squareY + EDGE_HALF_THICKNESS;
        } else if (object.edgeWest() && !object.edgeNorth()) {
            minimumX = squareX - EDGE_HALF_THICKNESS;
            maximumX = squareX + EDGE_HALF_THICKNESS;
        }
        float minimumY = z * LEVEL_HEIGHT;
        float maximumY = minimumY + LEVEL_HEIGHT;
        return new float[] {
            minimumX, minimumY, minimumZ, maximumX, maximumY, maximumZ
        };
    }

    static float rayBoxDistance(
            float originX,
            float originY,
            float originZ,
            float directionX,
            float directionY,
            float directionZ,
            float[] bounds,
            float maximumReach) {
        float near = MINIMUM_TARGET_DISTANCE;
        float far = maximumReach;
        float[] origins = {originX, originY, originZ};
        float[] directions = {directionX, directionY, directionZ};
        for (int axis = 0; axis < 3; axis++) {
            float direction = directions[axis];
            float minimum = bounds[axis];
            float maximum = bounds[axis + 3];
            if (Math.abs(direction) < 0.00001f) {
                if (origins[axis] < minimum || origins[axis] > maximum) {
                    return Float.POSITIVE_INFINITY;
                }
                continue;
            }
            float first = (minimum - origins[axis]) / direction;
            float second = (maximum - origins[axis]) / direction;
            if (first > second) {
                float swap = first;
                first = second;
                second = swap;
            }
            near = Math.max(near, first);
            far = Math.min(far, second);
            if (near > far) return Float.POSITIVE_INFINITY;
        }
        return near;
    }

    private static Reference reference(
            int squareX, int squareY, int z, WorldState.TileObject object) {
        return new Reference(
                squareX,
                squareY,
                z,
                object.index(),
                object.javaType(),
                object.objectType(),
                object.sprite(),
                object.worldItem().present() ? object.worldItem().itemId() : -1);
    }

    private static boolean matches(IsoObject object, Reference reference) {
        if (object == null || !object.getClass().getName().equals(reference.javaType())) return false;
        String type = object.getType() == null ? "" : object.getType().name();
        if (!type.equals(reference.objectType())) return false;
        String sprite = object.getSprite() == null
                ? nonNull(object.getSpriteName())
                : nonNull(object.getSprite().getName());
        if (!sprite.equals(reference.sprite())) return false;
        if (reference.worldItemId() < 0) return true;
        if (!(object instanceof zombie.iso.objects.IsoWorldInventoryObject worldObject)) return false;
        return worldObject.getItem() != null
                && worldObject.getItem().getID() == reference.worldItemId();
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
