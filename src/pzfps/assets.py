from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Iterator

from .common import now_utc, sha256_file, write_json


TOKEN = re.compile(
    r"\s+|/\*.*?\*/|//[^\r\n]*|[{}=,]|[^\s{}=,]+",
    re.DOTALL,
)


class GeometryParseError(ValueError):
    pass


@dataclass
class Block:
    name: str
    values: dict[str, str] = field(default_factory=dict)
    children: list["Block"] = field(default_factory=list)

    def children_named(self, name: str) -> Iterator["Block"]:
        return (child for child in self.children if child.name == name)


def tokenize(text: str) -> list[str]:
    tokens: list[str] = []
    position = 0
    for match in TOKEN.finditer(text):
        if match.start() != position:
            raise GeometryParseError(f"unrecognized input at byte {position}")
        position = match.end()
        token = match.group(0)
        if token.isspace() or token.startswith("/*") or token.startswith("//"):
            continue
        tokens.append(token)
    if position != len(text):
        raise GeometryParseError(f"unrecognized input at byte {position}")
    return tokens


class Parser:
    def __init__(self, tokens: list[str]) -> None:
        self.tokens = tokens
        self.index = 0

    def parse(self) -> Block:
        name = self.take_atom()
        root = self.parse_block(name)
        if self.index != len(self.tokens):
            raise GeometryParseError(f"trailing token {self.peek()!r}")
        return root

    def parse_block(self, name: str) -> Block:
        self.expect("{")
        result = Block(name)
        while self.peek() != "}":
            key = self.take_atom()
            following = self.peek()
            if following == "{":
                result.children.append(self.parse_block(key))
                continue
            self.expect("=")
            value: list[str] = []
            while self.peek() != ",":
                if self.peek() in {"{", "}", "="}:
                    raise GeometryParseError(
                        f"unexpected {self.peek()!r} in value for {key!r}"
                    )
                value.append(self.take())
            self.expect(",")
            result.values[key] = " ".join(value)
        self.expect("}")
        return result

    def take_atom(self) -> str:
        value = self.take()
        if value in {"{", "}", "=", ","}:
            raise GeometryParseError(f"expected identifier, found {value!r}")
        return value

    def take(self) -> str:
        if self.index >= len(self.tokens):
            raise GeometryParseError("unexpected end of input")
        value = self.tokens[self.index]
        self.index += 1
        return value

    def expect(self, expected: str) -> None:
        value = self.take()
        if value != expected:
            raise GeometryParseError(f"expected {expected!r}, found {value!r}")

    def peek(self) -> str:
        if self.index >= len(self.tokens):
            raise GeometryParseError("unexpected end of input")
        return self.tokens[self.index]


def parse_vector(value: str, dimensions: int, *, scale: float = 1.0) -> list[float]:
    parts = value.split("x")
    if len(parts) != dimensions:
        raise GeometryParseError(
            f"expected {dimensions}-component vector, found {value!r}"
        )
    return [int(part) / scale for part in parts]


def compile_geometry(source: Path, output: Path, *, game_version: str) -> dict[str, object]:
    root = Parser(tokenize(source.read_text(encoding="utf-8"))).parse()
    if root.name != "tileGeometry":
        raise GeometryParseError(f"expected tileGeometry root, found {root.name!r}")

    tiles: dict[str, object] = {}
    geometry_counts = {"box": 0, "cylinder": 0, "polygon": 0}
    for tileset in root.children_named("tileset"):
        tileset_name = required(tileset, "name")
        for tile in tileset.children_named("tile"):
            x, y = parse_vector(required(tile, "xy"), 2)
            sprite_name = f"{tileset_name}_{int(y) * 8 + int(x)}"
            geometries: list[dict[str, object]] = []
            properties: dict[str, str] = {}
            for child in tile.children:
                if child.name == "properties":
                    properties.update(child.values)
                elif child.name in geometry_counts:
                    geometries.append(compile_primitive(child))
                    geometry_counts[child.name] += 1
            tiles[sprite_name] = {
                "geometry": geometries,
                "properties": properties,
            }

    document: dict[str, object] = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "source": str(source),
        "source_sha256": sha256_file(source),
        "coordinate_scale": 10_000,
        "rotation_units_per_degree": 10_000,
        "tileset_columns": 8,
        "tile_count": len(tiles),
        "geometry_counts": geometry_counts,
        "tiles": tiles,
    }
    write_json(output, document)
    return document


def compile_primitive(block: Block) -> dict[str, object]:
    common: dict[str, object] = {
        "kind": block.name,
        "translate": parse_vector(required(block, "translate"), 3, scale=10_000),
        "rotate_degrees": parse_vector(required(block, "rotate"), 3, scale=10_000),
    }
    if block.name == "box":
        common["min"] = parse_vector(required(block, "min"), 3, scale=10_000)
        common["max"] = parse_vector(required(block, "max"), 3, scale=10_000)
    elif block.name == "cylinder":
        common["radius1"] = int(required(block, "radius1")) / 10_000
        common["radius2"] = int(required(block, "radius2")) / 10_000
        common["height"] = int(required(block, "height")) / 10_000
    elif block.name == "polygon":
        common["plane"] = required(block, "plane")
        common["points"] = [
            parse_vector(point, 2, scale=10_000)
            for point in required(block, "points").split()
        ]
    else:
        raise GeometryParseError(f"unsupported geometry primitive {block.name!r}")
    return common


def required(block: Block, key: str) -> str:
    try:
        return block.values[key]
    except KeyError as error:
        raise GeometryParseError(f"{block.name!r} is missing {key!r}") from error
