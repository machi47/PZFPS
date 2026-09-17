package dev.pzfps.bridge;

/** Entry point invoked by ZombieBuddy after the mod JAR is approved and loaded. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        DirectPatchInstaller.install();
        BridgeRuntime.start();
    }
}
