package dev.pzfps.bridge;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class WireProtocol {
    public static final int MAGIC = 0x505a4650; // PZFP
    public static final short VERSION = 4;

    public static final short HELLO = 1;
    public static final short PLAYER = 2;
    public static final short ENTITIES = 3;
    public static final short CHUNK_UPSERT = 4;
    public static final short CHUNK_REMOVE = 5;
    public static final short RESNAPSHOT_DONE = 6;
    public static final short INPUT = 100;
    public static final short RESNAPSHOT_REQUEST = 101;
    public static final short PING = 102;

    private static final int MAX_PACKET_BYTES = 64 * 1024 * 1024;

    private WireProtocol() {}

    public static synchronized void writeHello(OutputStream raw, long sessionId) throws IOException {
        writePacket(raw, HELLO, sessionId, 0, out -> {
            writeString(out, "PZFPSBridge");
            writeString(out, "42.20");
            out.writeInt(IsoChunkConstants.chunkSizeInSquares());
        });
    }

    public static synchronized void writePlayer(OutputStream raw, long sessionId, WorldState.Player value)
            throws IOException {
        writePacket(raw, PLAYER, sessionId, value.frameSequence(), out -> {
            out.writeLong(value.captureNanos());
            out.writeLong(value.captureEpochMillis());
            out.writeLong(value.acceptedInputSequence());
            out.writeFloat(value.x());
            out.writeFloat(value.y());
            out.writeFloat(value.z());
            out.writeFloat(value.forwardX());
            out.writeFloat(value.forwardY());
            out.writeFloat(value.verticalAim());
            writeString(out, value.actionState());
            out.writeBoolean(value.aiming());
            out.writeBoolean(value.attacking());
            out.writeBoolean(value.inVehicle());
            out.writeFloat(value.eyeHeight());
        });
    }

    public static synchronized void writeEntities(
            OutputStream raw, long sessionId, WorldState.Entities entities) throws IOException {
        writePacket(raw, ENTITIES, sessionId, entities.frameSequence(), out -> {
            out.writeLong(entities.captureNanos());
            out.writeLong(entities.captureEpochMillis());
            out.writeInt(entities.values().size());
            for (WorldState.Entity value : entities.values()) {
                out.writeInt(value.id());
                writeString(out, value.uid());
                writeString(out, value.kind());
                writeString(out, value.subtype());
                out.writeFloat(value.x());
                out.writeFloat(value.y());
                out.writeFloat(value.z());
                out.writeFloat(value.forwardX());
                out.writeFloat(value.forwardY());
                writeString(out, value.state());
                out.writeBoolean(value.onFloor());
                out.writeBoolean(value.crawling());
                writePose(out, value.pose());
            }
        });
    }

    private static void writePose(DataOutputStream out, WorldState.ActorPose pose) throws IOException {
        out.writeBoolean(pose.available());
        if (!pose.available()) return;
        writeString(out, pose.model());
        out.writeShort(pose.modelParts().size());
        for (String part : pose.modelParts()) writeString(out, part);
        writeString(out, pose.animation());
        out.writeFloat(pose.animationTime());
        out.writeFloat(pose.animationWeight());
        out.writeShort(pose.bones().size());
        for (WorldState.BonePose bone : pose.bones()) {
            out.writeShort(bone.index());
            out.writeShort(bone.parent());
            writeString(out, bone.name());
            out.writeFloat(bone.m00()); out.writeFloat(bone.m01());
            out.writeFloat(bone.m02()); out.writeFloat(bone.m03());
            out.writeFloat(bone.m10()); out.writeFloat(bone.m11());
            out.writeFloat(bone.m12()); out.writeFloat(bone.m13());
            out.writeFloat(bone.m20()); out.writeFloat(bone.m21());
            out.writeFloat(bone.m22()); out.writeFloat(bone.m23());
            out.writeFloat(bone.m30()); out.writeFloat(bone.m31());
            out.writeFloat(bone.m32()); out.writeFloat(bone.m33());
            out.writeFloat(bone.worldX());
            out.writeFloat(bone.worldY());
            out.writeFloat(bone.worldZ());
        }
    }

    public static synchronized void writeChunk(
            OutputStream raw, long sessionId, long sequence, WorldState.Chunk chunk) throws IOException {
        writePacket(raw, CHUNK_UPSERT, sessionId, sequence, out -> {
            out.writeInt(chunk.worldX());
            out.writeInt(chunk.worldY());
            out.writeLong(chunk.sourceRevision());
            out.writeLong(chunk.fingerprint());
            out.writeInt(chunk.squares().size());
            for (WorldState.Square square : chunk.squares()) {
                out.writeByte(square.localX());
                out.writeByte(square.localY());
                out.writeByte(square.z());
                out.writeLong(square.roomId());
                out.writeByte(square.visibility());
                out.writeShort(square.lightR());
                out.writeShort(square.lightG());
                out.writeShort(square.lightB());
                int flags = (square.solidFloor() ? 1 : 0)
                        | (square.exterior() ? 1 << 1 : 0)
                        | (square.roof() ? 1 << 2 : 0)
                        | (square.stairs() ? 1 << 3 : 0)
                        | (square.stairsBelow() ? 1 << 4 : 0)
                        | (square.stairTop() ? 1 << 5 : 0);
                out.writeByte(flags);
                out.writeShort(square.objects().size());
                for (WorldState.TileObject object : square.objects()) {
                    out.writeShort(object.index());
                    writeString(out, object.javaType());
                    writeString(out, object.objectType());
                    writeString(out, object.sprite());
                    int objectFlags = (object.door() ? 1 : 0)
                            | (object.window() ? 1 << 1 : 0)
                            | (object.north() ? 1 << 2 : 0)
                            | (object.open() ? 1 << 3 : 0)
                            | (object.hoppable() ? 1 << 4 : 0)
                            | (object.edgeNorth() ? 1 << 5 : 0)
                            | (object.edgeWest() ? 1 << 6 : 0)
                            | (object.container() ? 1 << 7 : 0);
                    out.writeByte(objectFlags);
                    writeWorldItem(out, object.worldItem());
                }
            }
        });
    }

    private static void writeWorldItem(DataOutputStream out, WorldState.WorldItem item)
            throws IOException {
        out.writeBoolean(item.present());
        if (!item.present()) return;
        out.writeInt(item.itemId());
        writeString(out, item.fullType());
        writeString(out, item.staticModel());
        writeString(out, item.worldStaticModel());
        writeString(out, item.worldObjectSprite());
        writeString(out, item.worldTexture());
        out.writeFloat(item.worldX());
        out.writeFloat(item.worldY());
        out.writeFloat(item.worldZ());
        out.writeFloat(item.rotationX());
        out.writeFloat(item.rotationY());
        out.writeFloat(item.rotationZ());
        out.writeFloat(item.scale());
        out.writeBoolean(item.extendedPlacement());
    }

    public static synchronized void writeChunkRemove(
            OutputStream raw, long sessionId, long sequence, long key) throws IOException {
        writePacket(raw, CHUNK_REMOVE, sessionId, sequence, out -> {
            out.writeInt((int) (key >> 32));
            out.writeInt((int) key);
        });
    }

    public static synchronized void writeResnapshotDone(
            OutputStream raw, long sessionId, long sequence) throws IOException {
        writePacket(raw, RESNAPSHOT_DONE, sessionId, sequence, ignored -> {});
    }

    public static ClientMessage readClientMessage(DataInputStream input, int previousButtons)
            throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException eof) {
            return null;
        }
        if (length < 20 || length > MAX_PACKET_BYTES) {
            throw new IOException("Invalid PZFPS packet length: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) throw new EOFException("Truncated PZFPS packet");
        DataInputStream packet = new DataInputStream(new java.io.ByteArrayInputStream(bytes));
        int magic = packet.readInt();
        short version = packet.readShort();
        short kind = packet.readShort();
        long sequence = packet.readLong();
        packet.readLong(); // client session/reserved
        if (magic != MAGIC) throw new IOException("Invalid PZFPS packet magic");
        if (version != VERSION) throw new IOException("Unsupported PZFPS protocol version " + version);
        if (kind == INPUT) {
            float strafe = packet.readFloat();
            float forward = packet.readFloat();
            float yaw = packet.readFloat();
            float pitch = packet.readFloat();
            int buttons = packet.readInt();
            return new ClientMessage(kind, new InputState.Sample(
                    true, sequence, strafe, forward, yaw, pitch, buttons, previousButtons));
        }
        return new ClientMessage(kind, null);
    }

    private static void writePacket(
            OutputStream raw, short kind, long sessionId, long sequence, PacketBody body)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeShort(VERSION);
        out.writeShort(kind);
        out.writeLong(sequence);
        out.writeLong(sessionId);
        body.write(out);
        out.flush();
        DataOutputStream framed = new DataOutputStream(raw);
        framed.writeInt(bytes.size());
        bytes.writeTo(framed);
        framed.flush();
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public record ClientMessage(short kind, InputState.Sample input) {}

    @FunctionalInterface
    private interface PacketBody {
        void write(DataOutputStream out) throws IOException;
    }

    private static final class IsoChunkConstants {
        private static int chunkSizeInSquares() {
            return zombie.iso.IsoChunkMap.CHUNK_SIZE_IN_SQUARES;
        }
    }
}
