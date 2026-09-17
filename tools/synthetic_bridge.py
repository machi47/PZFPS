#!/usr/bin/env python3
"""One-shot protocol peer for renderer integration tests; not a gameplay simulator."""

from __future__ import annotations

import argparse
import socket
import struct
import time


MAGIC = 0x505A4650
VERSION = 4
HELLO = 1
PLAYER = 2
ENTITIES = 3
CHUNK_UPSERT = 4
RESNAPSHOT_DONE = 6


def string(value: str) -> bytes:
    encoded = value.encode("utf-8")
    return struct.pack(">I", len(encoded)) + encoded


def packet(kind: int, sequence: int, session: int, body: bytes = b"") -> bytes:
    payload = struct.pack(">IHHQQ", MAGIC, VERSION, kind, sequence, session) + body
    return struct.pack(">I", len(payload)) + payload


def hello(session: int) -> bytes:
    return packet(HELLO, 0, session, string("PZFPSBridge") + string("42.20") + struct.pack(">i", 8))


def player(session: int, sequence: int) -> bytes:
    body = struct.pack(">QQQffffff", time.monotonic_ns(), time.time_ns() // 1_000_000, 0, 10620.5, 9825.5, 0.0, 1.0, 0.0, 0.0)
    body += string("Idle") + struct.pack(">???f", False, False, False, 1.62)
    return packet(PLAYER, sequence, session, body)


def entities(session: int, sequence: int) -> bytes:
    body = struct.pack(">QQI", time.monotonic_ns(), time.time_ns() // 1_000_000, 1)
    body += struct.pack(">i", 7)
    body += string("synthetic-zombie-7") + string("zombie") + string("Zombie")
    body += struct.pack(">fffff", 10623.0, 9825.0, 0.0, -1.0, 0.0)
    body += string("Idle") + struct.pack(">??", False, False)
    body += actor_pose()
    return packet(ENTITIES, sequence, session, body)


def actor_pose() -> bytes:
    parts = ["Base.MaleBody", "Base.HoodieUP"]
    bones = [
        (0, -1, "Bip01", (10623.0, 9825.0, 0.05)),
        (1, 0, "Bip01_Pelvis", (10623.0, 9825.0, 0.82)),
        (2, 1, "Bip01_Spine", (10623.0, 9825.0, 1.25)),
        (3, 2, "Bip01_Head", (10623.0, 9825.0, 1.72)),
    ]
    body = struct.pack(">?", True)
    body += string(parts[0]) + struct.pack(">H", len(parts))
    body += b"".join(string(part) for part in parts)
    body += string("Idle") + struct.pack(">ffH", 0.25, 1.0, len(bones))
    identity = (1.0, 0.0, 0.0, 0.0,
                0.0, 1.0, 0.0, 0.0,
                0.0, 0.0, 1.0, 0.0,
                0.0, 0.0, 0.0, 1.0)
    for index, parent, name, world in bones:
        body += struct.pack(">HH", index, parent & 0xFFFF)
        body += string(name)
        body += struct.pack(">16f3f", *identity, *world)
    return body


def chunk(session: int, sequence: int) -> bytes:
    # One authoritative-looking square using a real installed B42 tile identity.
    body = struct.pack(">iiqqI", 1327, 1228, 1, 0x12345678, 1)
    body += struct.pack(">BBBqBHHHBH", 4, 1, 0, 1, 0b111, 220, 210, 200, 0b011, 1)
    body += struct.pack(">H", 0)
    body += string("zombie.iso.IsoObject") + string("Normal") + string("furniture_bedding_01_0")
    body += struct.pack(">B?", 0, False)
    return packet(CHUNK_UPSERT, sequence, session, body)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=24872)
    args = parser.parse_args()
    session = 0x505A465054455354
    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.host, args.port))
        server.listen(1)
        print(f"synthetic bridge listening on {args.host}:{args.port}", flush=True)
        connection, address = server.accept()
        with connection:
            print(f"renderer connected from {address}", flush=True)
            connection.sendall(hello(session))
            connection.sendall(player(session, 1))
            connection.sendall(entities(session, 2))
            connection.sendall(chunk(session, 3))
            connection.sendall(packet(RESNAPSHOT_DONE, 3, session))
            connection.settimeout(3.0)
            try:
                received = connection.recv(4096)
                print(f"renderer input bytes={len(received)}", flush=True)
            except TimeoutError:
                print("renderer sent no input before timeout", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
