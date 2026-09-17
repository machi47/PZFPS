package dev.pzfps.bridge;

import java.util.Collection;
import java.util.Optional;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.util.list.PZArrayList;

/** Perspective selection plus identity-checked re-resolution of a live PZ object. */
final class InteractionTarget {
    private static final float TARGET_HALF_WIDTH = 0.72f;

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
        float forwardX = player.forwardX() / forwardLength;
        float forwardY = player.forwardY() / forwardLength;
        float bestScore = Float.POSITIVE_INFINITY;
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
                    float targetX = squareX + 0.5f;
                    float targetY = squareY + 0.5f;
                    if (object.edgeNorth() && !object.edgeWest()) targetY = squareY;
                    if (object.edgeWest() && !object.edgeNorth()) targetX = squareX;
                    float dx = targetX - player.x();
                    float dy = targetY - player.y();
                    float along = dx * forwardX + dy * forwardY;
                    if (along < 0.05f || along > maximumReach) continue;
                    float perpendicular = Math.abs(dx * forwardY - dy * forwardX);
                    if (perpendicular > TARGET_HALF_WIDTH) continue;
                    float score = along + perpendicular * 1.5f;
                    if (score >= bestScore) continue;
                    bestScore = score;
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
