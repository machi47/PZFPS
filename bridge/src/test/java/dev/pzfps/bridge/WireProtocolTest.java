package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WireProtocolTest {
    @Test
    void decodesFramedInputAndCarriesPreviousButtons() throws Exception {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        DataOutputStream payload = new DataOutputStream(payloadBytes);
        payload.writeInt(WireProtocol.MAGIC);
        payload.writeShort(WireProtocol.VERSION);
        payload.writeShort(WireProtocol.INPUT);
        payload.writeLong(93);
        payload.writeLong(0);
        payload.writeFloat(-0.5f);
        payload.writeFloat(1.0f);
        payload.writeFloat(1.25f);
        payload.writeFloat(-0.2f);
        payload.writeInt(InputState.AIM | InputState.RUN);
        payload.flush();

        ByteArrayOutputStream framedBytes = new ByteArrayOutputStream();
        DataOutputStream framed = new DataOutputStream(framedBytes);
        framed.writeInt(payloadBytes.size());
        payloadBytes.writeTo(framed);

        WireProtocol.ClientMessage message = WireProtocol.readClientMessage(
                new DataInputStream(new ByteArrayInputStream(framedBytes.toByteArray())),
                InputState.PRIMARY);
        assertNotNull(message);
        assertEquals(WireProtocol.INPUT, message.kind());
        assertEquals(93, message.input().sequence());
        assertEquals(-0.5f, message.input().strafe());
        assertEquals(InputState.PRIMARY, message.input().previousButtons());
    }

    @Test
    void encodesCollisionFactsInVersionFiveObjectFlags() throws Exception {
        WorldState.TileObject blocker = new WorldState.TileObject(
                3, "zombie.iso.IsoObject", "normal", "fixtures_blocker_01_2",
                false, false, false, false, false, false, false, true,
                true, true, true, WorldState.WorldItem.none());
        WorldState.Square square = new WorldState.Square(
                2, 4, 1, 9, 7, 255, 224, 192,
                true, false, true, false, false, false, List.of(blocker));
        WorldState.Chunk chunk = new WorldState.Chunk(5, 6, 7, 8, List.of(square));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        WireProtocol.writeChunk(bytes, 11, 12, chunk);

        DataInputStream input = new DataInputStream(
                new ByteArrayInputStream(bytes.toByteArray()));
        input.readInt();
        assertEquals(WireProtocol.MAGIC, input.readInt());
        assertEquals(5, input.readShort());
        assertEquals(WireProtocol.CHUNK_UPSERT, input.readShort());
        input.readLong();
        input.readLong();
        input.readInt();
        input.readInt();
        input.readLong();
        input.readLong();
        assertEquals(1, input.readInt());
        input.readByte();
        input.readByte();
        input.readByte();
        input.readLong();
        input.readByte();
        input.readShort();
        input.readShort();
        input.readShort();
        input.readByte();
        assertEquals(1, input.readShort());
        input.readShort();
        readString(input);
        readString(input);
        readString(input);
        int flags = input.readUnsignedShort();
        assertEquals(1 << 7 | 1 << 8 | 1 << 9 | 1 << 10, flags);
    }

    private static String readString(DataInputStream input) throws Exception {
        return new String(input.readNBytes(input.readInt()), StandardCharsets.UTF_8);
    }
}
