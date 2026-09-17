#!/usr/bin/env python3
"""Bounded, read-only protocol-5 player trace for ordinary native-input tests.

No input packets, position changes, or simulation calls are sent to the client.
Capture rate is NOT gameplay FPS. Pair with test_isolated_input.swift and a clip.
"""
import argparse
import json
import socket
import struct
import time
from pathlib import Path
from inspect_live_actors import Reader


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seconds', type=float, default=10)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if not 1 <= args.seconds <= 30:
        parser.error('seconds must be 1..30')
    started = time.monotonic()
    samples = []
    with socket.create_connection(('127.0.0.1', 24872), timeout=3) as sock:
        sock.settimeout(.5)
        pending = bytearray()
        while time.monotonic() - started < args.seconds:
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
                packet = bytes(pending[4:4+length])
                del pending[:4+length]
                magic, version, kind, sequence, session = struct.unpack_from('>Ihhqq', packet)
                if magic != 0x505A4650 or version != 5:
                    raise ValueError('unexpected protocol')
                if kind != 2:
                    continue
                r = Reader(packet[24:])
                nanos, epoch, accepted = r.take('qqq')
                x, y, z, fx, fy, pitch = r.take('6f')
                state = r.string()
                aiming, attacking, vehicle = r.take('???')
                eye, = r.take('f')
                samples.append(dict(elapsed=time.monotonic()-started, sequence=sequence,
                                    session=session, capture_epoch_ms=epoch,
                                    position=[x,y,z], forward=[fx,fy], pitch=pitch,
                                    state=state, aiming=aiming, attacking=attacking,
                                    in_vehicle=vehicle, eye_height=eye))
                if len(samples) >= 3600:
                    raise ValueError('trace sample limit exceeded')
    if not samples:
        raise RuntimeError('No live player samples received')
    args.output.write_text(json.dumps(dict(samples=samples), indent=2)+'\n')
    print(json.dumps(dict(output=str(args.output), samples=len(samples),
                          first=samples[0], last=samples[-1])))


if __name__ == '__main__':
    main()
