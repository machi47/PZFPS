package dev.pzfps.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
}
