from __future__ import annotations

from pathlib import Path
from typing import Any

from .common import now_utc, sha256_file, write_json


class TileDefinitionParseError(ValueError):
    pass


def compile_tile_definitions(source: Path, output: Path, *, game_version: str) -> dict[str, Any]:
    """Index installed text tile definitions without copying texture pixels.

    The installed B42 text format is line-oriented and deliberately differs from
    tileGeometry.txt: assignments have no trailing commas and empty values are
    meaningful boolean properties. Keep every property so family compilers can
    use source semantics such as RoofGroup/attachedN/isEave rather than suffix
    guesses.
    """
    tiles: dict[str, dict[str, Any]] = {}
    header: dict[str, str] = {}
    tileset: dict[str, str] | None = None
    tile: dict[str, str] | None = None
    pending: str | None = None

    for line_number, raw in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split("//", 1)[0].strip()
        if not line:
            continue
        if line in {"tileset", "tile"}:
            if pending is not None:
                raise TileDefinitionParseError(f"line {line_number}: nested pending block")
            pending = line
            continue
        if line == "{":
            if pending == "tileset":
                if tileset is not None or tile is not None:
                    raise TileDefinitionParseError(f"line {line_number}: nested tileset")
                tileset = {}
            elif pending == "tile":
                if tileset is None or tile is not None:
                    raise TileDefinitionParseError(f"line {line_number}: tile outside tileset")
                tile = {}
            else:
                raise TileDefinitionParseError(f"line {line_number}: unexpected opening brace")
            pending = None
            continue
        if line == "}":
            if pending is not None:
                raise TileDefinitionParseError(f"line {line_number}: missing opening brace")
            if tile is not None:
                file_name = _required(tileset, "file", line_number)
                width, _ = _pair(_required(tileset, "size", line_number), line_number)
                x, y = _pair(_required(tile, "xy", line_number), line_number)
                identity = f"{file_name}_{y * width + x}"
                if identity in tiles:
                    raise TileDefinitionParseError(f"line {line_number}: duplicate tile {identity}")
                properties = {key: value for key, value in tile.items() if key != "xy"}
                tiles[identity] = {
                    "tileset": file_name,
                    "xy": [x, y],
                    "properties": properties,
                }
                tile = None
            elif tileset is not None:
                _required(tileset, "file", line_number)
                _required(tileset, "size", line_number)
                tileset = None
            else:
                raise TileDefinitionParseError(f"line {line_number}: unexpected closing brace")
            continue
        if "=" not in line:
            raise TileDefinitionParseError(f"line {line_number}: expected assignment, found {line!r}")
        key, value = (part.strip() for part in line.split("=", 1))
        target = tile if tile is not None else tileset
        if target is None:
            # The installed file begins with a format-version assignment.
            header[key] = value
            continue
        target[key] = value

    if pending is not None or tile is not None or tileset is not None:
        raise TileDefinitionParseError("unterminated tile-definition block")
    document = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "source": str(source),
        "source_sha256": sha256_file(source),
        "source_format_version": header.get("version", ""),
        "tile_count": len(tiles),
        "tiles": tiles,
    }
    write_json(output, document)
    return document


def _required(values: dict[str, str] | None, key: str, line_number: int) -> str:
    if values is None or key not in values:
        raise TileDefinitionParseError(f"line {line_number}: missing {key}")
    return values[key]


def _pair(value: str, line_number: int) -> tuple[int, int]:
    parts = value.split(",")
    if len(parts) != 2:
        raise TileDefinitionParseError(f"line {line_number}: expected integer pair, found {value!r}")
    try:
        return int(parts[0]), int(parts[1])
    except ValueError as error:
        raise TileDefinitionParseError(f"line {line_number}: invalid integer pair {value!r}") from error
