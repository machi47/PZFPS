package dev.pzfps.bridge;

import java.net.InetAddress;
import java.net.UnknownHostException;

public record BridgeConfig(
        InetAddress bindAddress,
        int port,
        int chunkRadius,
        long worldIntervalNanos,
        long entityIntervalNanos) {

    public static BridgeConfig fromSystemProperties() {
        try {
            String bind = System.getProperty("pzfps.bind", "127.0.0.1");
            int port = boundedInt("pzfps.port", 24872, 1024, 65535);
            int radius = boundedInt("pzfps.chunkRadius", 6, 1, 32);
            int worldHz = boundedInt("pzfps.worldHz", 4, 1, 30);
            int entityHz = boundedInt("pzfps.entityHz", 30, 1, 120);
            return new BridgeConfig(
                    InetAddress.getByName(bind),
                    port,
                    radius,
                    1_000_000_000L / worldHz,
                    1_000_000_000L / entityHz);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Invalid pzfps.bind", e);
        }
    }

    private static int boundedInt(String name, int fallback, int minimum, int maximum) {
        int value = Integer.getInteger(name, fallback);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be in [" + minimum + ", " + maximum + "]: " + value);
        }
        return value;
    }
}
