#!/usr/bin/env python3
"""Read-only, bounded protocol-5 actor/pose inspection of the isolated local client."""

import argparse
import io
import json
import socket
import struct
import time


class Reader:
    def __init__(self, payload):
        self.data = io.BytesIO(payload)

    def take(self, fmt):
        size = struct.calcsize('>' + fmt)
        raw = self.data.read(size)
        if len(raw) != size:
            raise ValueError('truncated actor snapshot')
        return struct.unpack('>' + fmt, raw)

    def string(self):
        length, = self.take('i')
        if not 0 <= length <= 1_000_000:
            raise ValueError('invalid string length')
        raw = self.data.read(length)
        if len(raw) != length:
            raise ValueError('truncated string')
        return raw.decode('utf-8')


def actors(payload):
    r = Reader(payload)
    capture_ns, epoch_ms, count = r.take('qqi')
    result = []
    for _ in range(count):
        identity, = r.take('i')
        uid, kind, subtype = (r.string() for _ in range(3))
        x, y, z, fx, fy = r.take('fffff')
        state = r.string()
        on_floor, crawling, available = r.take('???')
        actor = dict(id=identity, uid=uid, kind=kind, subtype=subtype,
                     position=[x, y, z], forward=[fx, fy], state=state,
                     on_floor=on_floor, crawling=crawling, pose_available=available)
        if available:
            actor['model'] = r.string()
            part_count, = r.take('H')
            actor['parts'] = [r.string() for _ in range(part_count)]
            actor['animation'] = r.string()
            actor['animation_time'], actor['animation_weight'] = r.take('ff')
            bone_count, = r.take('H')
            bones = []
            for _ in range(bone_count):
                index, parent = r.take('hh')
                name = r.string()
                matrix = r.take('16f')
                world = r.take('3f')
                if any(s in name.lower() for s in ('head', 'foot', 'pelvis', 'hand')):
                    bones.append(dict(name=name, index=index, parent=parent,
                                      model=[matrix[3], matrix[7], matrix[11]], world=world))
            actor['bones'] = bones
        if kind in ('zombie', 'character'):
            result.append(actor)
    return dict(capture_ns=capture_ns, epoch_ms=epoch_ms, actors=result)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--port', type=int, default=24872)
    parser.add_argument('--seconds', type=float, default=5)
    args = parser.parse_args()
    deadline = time.monotonic() + min(30, max(1, args.seconds))
    latest = None
    player = None
    samples = 0
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
                    raise ValueError(f'invalid packet length {length}')
                if len(pending) < length + 4:
                    break
                packet = bytes(pending[4:4 + length])
                del pending[:4 + length]
                magic, version, kind, sequence, session = struct.unpack_from('>Ihhqq', packet)
                if magic != 0x505A4650 or version != 5:
                    raise ValueError('unexpected bridge protocol')
                payload = packet[24:]
                if kind == 2:
                    r = Reader(payload)
                    r.take('qqq')
                    player = r.take('6f')
                if kind == 3:
                    latest = actors(payload)
                    samples += 1
    print(json.dumps(dict(entity_samples=samples, player=player, latest=latest), indent=2))


if __name__ == '__main__':
    main()
