package dev.pzfps.bridge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.LosUtil;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoWindow;
import zombie.util.list.PZArrayList;

/** Perspective selection plus identity-checked re-resolution of a live PZ object. */
final class InteractionTarget {
    private static final float EDGE_HALF_THICKNESS = 0.08f;
    private static final float TARGET_PADDING = 0.06f;
    private static final float MINIMUM_TARGET_DISTANCE = 0.05f;
    private static final float WORLD_ITEM_HALF_EXTENT = 0.35f;
    private static final float FLOOR_HALF_THICKNESS = 0.04f;
    private static final int MAXIMUM_CONTEXT_CANDIDATES = 24;

    record Reference(
            int squareX,
            int squareY,
            int z,
            int objectIndex,
            String javaType,
            String objectType,
            String sprite,
            int worldItemId,
            boolean door,
            boolean window,
            boolean container) {}

    private record Hit(float distance, Reference reference) {}

    private InteractionTarget() {}

    static Optional<Reference> nearestInteractive(
            WorldState.Player player, Collection<WorldState.Chunk> chunks, float maximumReach) {
        List<Reference> candidates = candidates(
                player, chunks, maximumReach, InteractionTarget::isInteractive, 1);
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.getFirst());
    }

    /**
     * Returns perspective hits in near-to-far order for PZ's own context-menu test pass. The
     * filter deliberately accepts ordinary and mod-defined world objects: Java chooses only a
     * geometric candidate, while PZ decides whether that object/square actually supplies actions.
     */
    static List<Reference> contextCandidates(
            WorldState.Player player, Collection<WorldState.Chunk> chunks, float maximumReach) {
        return candidates(
                player,
                chunks,
                maximumReach,
                InteractionTarget::canRequestContext,
                MAXIMUM_CONTEXT_CANDIDATES);
    }

    private static List<Reference> candidates(
            WorldState.Player player,
            Collection<WorldState.Chunk> chunks,
            float maximumReach,
            Predicate<WorldState.TileObject> accepted,
            int limit) {
        if (maximumReach <= 0.0f) throw new IllegalArgumentException("maximumReach must be positive");
        PerspectiveViewRay.Ray ray = PerspectiveViewRay.fromPlayer(player);
        ArrayList<Hit> hits = new ArrayList<>();
        int chunkSize = zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;

        for (WorldState.Chunk chunk : chunks) {
            int chunkX = chunk.worldX() * chunkSize;
            int chunkY = chunk.worldY() * chunkSize;
            for (WorldState.Square square : chunk.squares()) {
                int squareX = chunkX + square.localX();
                int squareY = chunkY + square.localY();
                for (WorldState.TileObject object : square.objects()) {
                    if (!accepted.test(object)) continue;
                    float[] bounds = bounds(squareX, squareY, square.z(), object);
                    float distance = rayBoxDistance(
                            ray.originX(),
                            ray.originY(),
                            ray.originZ(),
                            ray.directionX(),
                            ray.directionY(),
                            ray.directionZ(),
                            bounds,
                            maximumReach);
                    if (!Float.isFinite(distance)) continue;
                    hits.add(new Hit(distance, reference(squareX, squareY, square.z(), object)));
                }
            }
        }
        hits.sort(Comparator.comparingDouble(Hit::distance));
        ArrayList<Reference> result = new ArrayList<>(Math.min(hits.size(), limit));
        for (int index = 0; index < hits.size() && index < limit; index++) {
            result.add(hits.get(index).reference());
        }
        return List.copyOf(result);
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
            if (matches(indexed, reference)
                    && hasAuthoritativeSightline(player, indexed, reference)) {
                return indexed;
            }
        }

        IsoObject unique = null;
        for (int index = 0; index < objects.size(); index++) {
            IsoObject candidate = objects.get(index);
            if (!matches(candidate, reference)) continue;
            if (!hasAuthoritativeSightline(player, candidate, reference)) continue;
            if (unique != null) return null;
            unique = candidate;
        }
        return unique;
    }

    /**
     * Uses B42's own square-visibility traversal on the game thread. A closed door or window is
     * reachable only when it is itself the selected object; it must not expose a container behind
     * it. Open doors remain traversable, while an ordinary blocking wall rejects the target.
     */
    private static boolean hasAuthoritativeSightline(
            IsoPlayer player, IsoObject object, Reference reference) {
        int startX = (int) Math.floor(player.getX());
        int startY = (int) Math.floor(player.getY());
        int startZ = (int) Math.floor(player.getZ());
        if (startX == reference.squareX()
                && startY == reference.squareY()
                && startZ == reference.z()) {
            return true;
        }
        LosUtil.TestResults result = LosUtil.lineClear(
                player.getCell(),
                startX,
                startY,
                startZ,
                reference.squareX(),
                reference.squareY(),
                reference.z(),
                false);
        return allowsSightline(result, isDoor(object), isWindow(object));
    }

    static boolean allowsSightline(
            LosUtil.TestResults result, boolean targetIsDoor, boolean targetIsWindow) {
        if (result == null || result == LosUtil.TestResults.Blocked) return false;
        if (result == LosUtil.TestResults.ClearThroughClosedDoor) return targetIsDoor;
        if (result == LosUtil.TestResults.ClearThroughWindow) return targetIsWindow;
        return true;
    }

    private static boolean isDoor(IsoObject object) {
        return object instanceof IsoDoor
                || (object instanceof IsoThumpable thumpable && thumpable.isDoor());
    }

    private static boolean isWindow(IsoObject object) {
        return object instanceof IsoWindow
                || (object instanceof IsoThumpable thumpable
                        && (thumpable.isWindowN() || thumpable.isWindowW()));
    }

    private static boolean isInteractive(WorldState.TileObject object) {
        return object.door() || object.window() || object.container();
    }

    private static boolean canRequestContext(WorldState.TileObject object) {
        return isInteractive(object)
                || object.worldItem().present()
                || !object.sprite().isBlank()
                || !object.objectType().isBlank();
    }

    private static float[] bounds(
            int squareX, int squareY, int z, WorldState.TileObject object) {
        float baseY = z * PerspectiveViewRay.LEVEL_HEIGHT;
        if (object.worldItem().present()) {
            WorldState.WorldItem item = object.worldItem();
            float centreY = item.worldZ() * PerspectiveViewRay.LEVEL_HEIGHT;
            return new float[] {
                item.worldX() - WORLD_ITEM_HALF_EXTENT,
                centreY - WORLD_ITEM_HALF_EXTENT,
                item.worldY() - WORLD_ITEM_HALF_EXTENT,
                item.worldX() + WORLD_ITEM_HALF_EXTENT,
                centreY + WORLD_ITEM_HALF_EXTENT,
                item.worldY() + WORLD_ITEM_HALF_EXTENT
            };
        }
        float minimumX = squareX - TARGET_PADDING;
        float maximumX = squareX + 1.0f + TARGET_PADDING;
        float minimumZ = squareY - TARGET_PADDING;
        float maximumZ = squareY + 1.0f + TARGET_PADDING;
        if (object.floor()) {
            return new float[] {
                minimumX,
                baseY - FLOOR_HALF_THICKNESS,
                minimumZ,
                maximumX,
                baseY + FLOOR_HALF_THICKNESS,
                maximumZ
            };
        }
        if (object.edgeNorth() && !object.edgeWest()) {
            minimumZ = squareY - EDGE_HALF_THICKNESS;
            maximumZ = squareY + EDGE_HALF_THICKNESS;
        } else if (object.edgeWest() && !object.edgeNorth()) {
            minimumX = squareX - EDGE_HALF_THICKNESS;
            maximumX = squareX + EDGE_HALF_THICKNESS;
        }
        float minimumY = baseY;
        float maximumY = minimumY + PerspectiveViewRay.LEVEL_HEIGHT;
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
                object.worldItem().present() ? object.worldItem().itemId() : -1,
                object.door(),
                object.window(),
                object.container());
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
