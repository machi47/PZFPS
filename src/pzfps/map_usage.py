from __future__ import annotations

from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
from typing import Any

from .common import now_utc, write_json


_ASCII_IDENTITY = re.compile(rb"[A-Za-z][A-Za-z0-9_]{2,}")


class MapUsageError(ValueError):
    pass


def index_map_header_usage(
    maps: Path,
    textures_path: Path,
    output: Path,
    *,
    game_version: str,
) -> dict[str, Any]:
    """Index exact atlas identities named by installed map header dictionaries.

    ``.lotheader`` files contain their referenced sprite names as ASCII strings even though
    the rest of the format is binary. Intersecting extracted tokens with the authoritative
    texture-pack index avoids inventing identities and lets the coverage report distinguish
    unused atlas art from assets that the installed world can actually request. Header
    presence is deliberately not reported as an object-instance count.
    """
    textures_document = json.loads(textures_path.read_text(encoding="utf-8"))
    if textures_document.get("game_version") != game_version:
        raise MapUsageError("texture index describes a different game version")
    atlas_identities = set(textures_document.get("textures", {}))
    if not maps.is_dir():
        raise MapUsageError(f"map directory is absent: {maps}")

    references: dict[str, list[str]] = defaultdict(list)
    source_digest = hashlib.sha256()
    header_count = 0
    source_bytes = 0
    for header in sorted(maps.rglob("*.lotheader")):
        relative = header.relative_to(maps).as_posix()
        data = header.read_bytes()
        header_count += 1
        source_bytes += len(data)
        source_digest.update(relative.encode("utf-8"))
        source_digest.update(b"\0")
        source_digest.update(hashlib.sha256(data).digest())
        tokens = {
            match.group().decode("ascii")
            for match in _ASCII_IDENTITY.finditer(data)
        }
        for identity in sorted(tokens & atlas_identities):
            references[identity].append(relative)

    identities = {
        identity: {
            "header_count": len(headers),
            "headers": headers,
            "map_directories": sorted({header.split("/", 1)[0] for header in headers}),
        }
        for identity, headers in sorted(references.items())
    }
    document = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "source": str(maps.resolve()),
        "source_tree_sha256": source_digest.hexdigest(),
        "source_header_count": header_count,
        "source_bytes": source_bytes,
        "texture_index": str(textures_path.resolve()),
        "texture_index_sha256": hashlib.sha256(textures_path.read_bytes()).hexdigest(),
        "referenced_identity_count": len(identities),
        "identities": identities,
    }
    write_json(output, document)
    return document
