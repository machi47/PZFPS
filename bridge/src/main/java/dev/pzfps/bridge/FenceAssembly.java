package dev.pzfps.bridge;

/** Unifies the installed short chain-link family on exact tile-boundary panels. */
final class FenceAssembly {
    private FenceAssembly() {}

    static boolean shortChainLink(WorldState.TileObject object) {
        String prefix = "fencing_01_";
        if (!object.sprite().startsWith(prefix)) return false;
        int suffix;
        try {
            suffix = Integer.parseInt(object.sprite().substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return false;
        }
        return suffix >= 24 && suffix <= 27;
    }

    static boolean north(WorldState.TileObject object) {
        if (object.edgeNorth() != object.edgeWest()) return object.edgeNorth();
        // Installed sprites 24/25 are north-edge views; 26/27 are west-edge views.
        int suffix = Integer.parseInt(object.sprite().substring(object.sprite().lastIndexOf('_') + 1));
        return suffix <= 25;
    }
}
