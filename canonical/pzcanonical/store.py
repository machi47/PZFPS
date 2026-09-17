"""Immutable content-addressed assets and transactional instance assignments."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import tempfile
from typing import Callable, Iterator
from contextlib import contextmanager

from filelock import FileLock


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical_json(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


class Store:
    """Safe across threads/processes. No process-global DB connection or RNG."""
    def __init__(self, root: Path):
        self.root = root.resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        (self.root / "objects").mkdir(exist_ok=True)
        (self.root / "locks").mkdir(exist_ok=True)
        self.database = self.root / "assignments.sqlite3"
        with self._connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("CREATE TABLE IF NOT EXISTS assignments (world TEXT NOT NULL, instance TEXT NOT NULL, prototype TEXT NOT NULL, asset TEXT NOT NULL, PRIMARY KEY(world, instance))")

    @contextmanager
    def _connect(self) -> Iterator[sqlite3.Connection]:
        connection = sqlite3.connect(self.database, timeout=30)
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def path(self, key: str) -> Path:
        if re.fullmatch(r"[a-f0-9]{64}", key) is None:
            raise ValueError("asset key must be lowercase SHA-256")
        return self.root / "objects" / key

    def verify(self, key: str) -> Path:
        path = self.path(key)
        manifest = json.loads((path / "integrity.json").read_text())
        if manifest["key"] != key:
            raise ValueError("cache key does not match manifest")
        for name, expected in manifest["files"].items():
            candidate = path / name
            if candidate.resolve().parent != path.resolve() or not candidate.is_file():
                raise ValueError("invalid cache artifact path")
            if digest(candidate.read_bytes()) != expected:
                raise ValueError(f"cached artifact changed: {name}")
        return path

    def materialize(self, key: str, build: Callable[[Path], None]) -> tuple[Path, bool]:
        target = self.path(key)
        with FileLock(str(self.root / "locks" / f"{key}.lock"), timeout=120):
            if target.exists():
                return self.verify(key), True
            staging = Path(tempfile.mkdtemp(prefix=f".{key}-", dir=self.root / "objects"))
            try:
                build(staging)
                files = sorted(staging.iterdir())
                if not files or any(not p.is_file() or p.is_symlink() for p in files):
                    raise ValueError("builder must produce regular top-level artifact files")
                manifest = {"key": key, "files": {p.name: digest(p.read_bytes()) for p in files}}
                (staging / "integrity.json").write_bytes(canonical_json(manifest))
                os.replace(staging, target)
            finally:
                if staging.exists():
                    shutil.rmtree(staging)
        return self.verify(key), False

    def assign(self, world: str, instance: str, prototype: str, key: str) -> str:
        self.verify(key)
        if not world or not instance or not prototype:
            raise ValueError("world, persistent instance ID and prototype are required")
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            row = connection.execute("SELECT prototype, asset FROM assignments WHERE world=? AND instance=?", (world, instance)).fetchone()
            if row:
                if row[0] != prototype:
                    raise ValueError("instance ID reused for another prototype; use a new lifecycle ID")
                return str(row[1])
            connection.execute("INSERT INTO assignments VALUES(?,?,?,?)", (world, instance, prototype, key))
        return key

    def accept(self, world: str, instance: str, expected: str, replacement: str) -> None:
        self.verify(replacement)
        with self._connect() as connection:
            connection.execute("BEGIN IMMEDIATE")
            result = connection.execute("UPDATE assignments SET asset=? WHERE world=? AND instance=? AND asset=?", (replacement, world, instance, expected))
            if result.rowcount != 1:
                raise ValueError("appearance assignment changed; refusing stale acceptance")

    def lookup(self, world: str, instance: str) -> str | None:
        with self._connect() as connection:
            row = connection.execute("SELECT asset FROM assignments WHERE world=? AND instance=?", (world, instance)).fetchone()
        return str(row[0]) if row else None
