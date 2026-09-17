#!/usr/bin/env python3
"""Read-only, bounded protocol-5 tile snapshot around the real player."""
import argparse
import json
import socket
import struct
import time
from pathlib import Path

from inspect_live_actors import Reader


def chunk(payload):
    r = Reader(payload)
    cx, cy, revision, fingerprint, count = r.take('iiqqi')
    squares = []
    if not 0 <= count <= 100000:
        raise ValueError('invalid square count')
    for _ in range(count):
        x, y, z, room, visibility, lr, lg, lb, flags, objects = r.take('BBbqBhhhBH')
        entries = []
        for _ in range(objects):
            index, = r.take('H')
            java, kind, sprite = (r.string() for _ in range(3))
            oflags, present = r.take('H?')
            if present:
                r.take('i')
                for _ in range(5):
                    r.string()
                r.take('7f?')
            entries.append(dict(index=index, java=java, kind=kind, sprite=sprite,
                                flags=oflags, world_item=present))
        squares.append(dict(local=[x, y, z], room=room, flags=flags,
                            light=[lr, lg, lb], objects=entries))
    return dict(chunk=[cx, cy], revision=revision, squares=squares)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=float, default=5)
    parser.add_argument('--port', type=int, default=24872)
    parser.add_argument('--radius', type=int, default=4)
    parser.add_argument('--chunk-size', type=int, default=8)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    chunks, player = {}, None
    deadline = time.monotonic() + min(30, max(1, args.seconds))
    with socket.create_connection(('127.0.0.1', args.port), timeout=3) as sock:
        sock.settimeout(1)
        pending = bytearray()
        while time.monotonic() < deadline:
            try:
                block = sock.recv(65536)
            except socket.timeout:
                continue
            if not block:
                break
            pending.extend(block)
            while len(pending) >= 4:
                length, = struct.unpack_from('>i', pending)
                if not 24 <= length <= 64 * 1024 * 1024:
                    raise ValueError('invalid packet length')
                if len(pending) < length + 4:
                    break
                packet = bytes(pending[4:4 + length])
                del pending[:4 + length]
                magic, version, kind, sequence, session = struct.unpack_from('>Ihhqq', packet)
                if magic != 0x505A4650 or version != 5:
                    raise ValueError('unexpected protocol')
                if kind == 2:
                    r = Reader(packet[24:])
                    r.take('qqq')
                    player = r.take('6f')
                if kind == 4:
                    value = chunk(packet[24:])
                    chunks[tuple(value['chunk'])] = value
    nearby = []
    if player:
        for value in chunks.values():
            cx, cy = value['chunk']
            for square in value['squares']:
                lx, ly, z = square.pop('local')
                x, y = cx * args.chunk_size + lx, cy * args.chunk_size + ly
                if abs(x - player[0]) <= args.radius and abs(y - player[1]) <= args.radius:
                    nearby.append(dict(position=[x, y, z], **square))
    report = dict(player=player, received_chunks=len(chunks), squares=nearby)
    if args.output:
        args.output.write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(dict(output=str(args.output), received_chunks=len(chunks), squares=len(nearby))))
    else:
        print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
