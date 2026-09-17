package dev.pzfps.bridge;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/** Single-client localhost transport. Game-thread publishers never block on I/O. */
public final class BridgeServer implements AutoCloseable {
    private final BridgeConfig config;
    private final long sessionId = System.nanoTime() ^ ProcessHandle.current().pid();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<WorldState.Player> latestPlayer = new AtomicReference<>();
    private final AtomicReference<WorldState.Entities> latestEntities = new AtomicReference<>();
    private final ConcurrentHashMap<Long, WorldState.Chunk> dirtyChunks = new ConcurrentHashMap<>();
    private final Set<Long> removedChunks = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Long> resnapshotCompletions = new ConcurrentLinkedQueue<>();
    private final AtomicLong transportSequence = new AtomicLong();
    private volatile ServerSocket serverSocket;
    private volatile Socket clientSocket;

    public BridgeServer(BridgeConfig config) {
        this.config = config;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        Thread serverThread = new Thread(this::acceptLoop, "PZFPS-bridge-io");
        serverThread.setDaemon(true);
        serverThread.start();
    }

    public void publishPlayer(WorldState.Player value) {
        latestPlayer.set(value);
    }

    public void publishEntities(WorldState.Entities value) {
        latestEntities.set(value);
    }

    public void publishChunk(WorldState.Chunk value) {
        removedChunks.remove(value.key());
        dirtyChunks.put(value.key(), value);
    }

    public void removeChunk(long key) {
        dirtyChunks.remove(key);
        removedChunks.add(key);
    }

    public void publishResnapshotDone(long sequence) {
        resnapshotCompletions.offer(sequence);
    }

    private void acceptLoop() {
        try (ServerSocket server = new ServerSocket()) {
            serverSocket = server;
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(config.bindAddress(), config.port()), 1);
            System.out.printf("[PZFPS] bridge listening on %s:%d protocol=%d%n",
                    config.bindAddress().getHostAddress(), config.port(), WireProtocol.VERSION);
            while (running.get()) {
                try {
                    Socket socket = server.accept();
                    handleClient(socket);
                } catch (SocketException error) {
                    if (running.get()) logFailure("accept", error);
                } catch (IOException error) {
                    logFailure("client", error);
                } finally {
                    InputState.deactivate();
                    clientSocket = null;
                }
            }
        } catch (IOException error) {
            running.set(false);
            logFailure("listen", error);
        }
    }

    private void handleClient(Socket socket) throws IOException {
        clientSocket = socket;
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream(), 256 * 1024);
        DataInputStream input = new DataInputStream(
                new BufferedInputStream(socket.getInputStream(), 64 * 1024));
        WireProtocol.writeHello(output, sessionId);
        BridgeRuntime.requestResnapshot();
        System.out.printf("[PZFPS] renderer connected from %s%n", socket.getRemoteSocketAddress());

        AtomicBoolean clientRunning = new AtomicBoolean(true);
        Thread reader = new Thread(() -> readClient(input, clientRunning), "PZFPS-bridge-input");
        reader.setDaemon(true);
        reader.start();

        try {
            while (running.get() && clientRunning.get() && !socket.isClosed()) {
                boolean wrote = false;
                WorldState.Player player = latestPlayer.getAndSet(null);
                if (player != null) {
                    WireProtocol.writePlayer(output, sessionId, player);
                    wrote = true;
                }
                WorldState.Entities entities = latestEntities.getAndSet(null);
                if (entities != null) {
                    WireProtocol.writeEntities(output, sessionId, entities);
                    wrote = true;
                }
                for (var entry : dirtyChunks.entrySet()) {
                    WorldState.Chunk chunk = entry.getValue();
                    if (!dirtyChunks.remove(entry.getKey(), chunk)) continue;
                    WireProtocol.writeChunk(
                            output, sessionId, transportSequence.incrementAndGet(), chunk);
                    wrote = true;
                }
                for (Long key : removedChunks) {
                    if (!removedChunks.remove(key)) continue;
                    WireProtocol.writeChunkRemove(
                            output, sessionId, transportSequence.incrementAndGet(), key);
                    wrote = true;
                }
                Long completion;
                while ((completion = resnapshotCompletions.poll()) != null) {
                    WireProtocol.writeResnapshotDone(output, sessionId, completion);
                    wrote = true;
                }
                if (!wrote) LockSupport.parkNanos(2_000_000L);
            }
        } finally {
            clientRunning.set(false);
            socket.close();
            System.out.println("[PZFPS] renderer disconnected; virtual input disabled");
        }
    }

    private void readClient(DataInputStream input, AtomicBoolean clientRunning) {
        int previousButtons = 0;
        try {
            while (running.get() && clientRunning.get()) {
                WireProtocol.ClientMessage message =
                        WireProtocol.readClientMessage(input, previousButtons);
                if (message == null) break;
                if (message.kind() == WireProtocol.INPUT && message.input() != null) {
                    InputState.set(message.input());
                    previousButtons = message.input().buttons();
                } else if (message.kind() == WireProtocol.RESNAPSHOT_REQUEST) {
                    BridgeRuntime.requestResnapshot();
                }
            }
        } catch (IOException error) {
            if (clientRunning.get()) logFailure("input", error);
        } finally {
            clientRunning.set(false);
        }
    }

    private static void logFailure(String operation, Exception error) {
        System.err.printf("[PZFPS] bridge %s failure: %s%n", operation, error);
    }

    @Override
    public void close() {
        running.set(false);
        closeQuietly(clientSocket);
        closeQuietly(serverSocket);
        InputState.deactivate();
    }

    private static void closeQuietly(AutoCloseable value) {
        if (value == null) return;
        try {
            value.close();
        } catch (Exception ignored) {
            // Shutdown path: the socket may already be closed by the peer.
        }
    }
}
