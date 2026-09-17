from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import BinaryIO

from .common import now_utc, sha256_file, write_json


MAGIC = b"PZPK"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
MAX_COUNT = 1_000_000
MAX_STRING_BYTES = 1_000_000


class TexturePackError(ValueError):
    pass


@dataclass
class _Reader:
    stream: BinaryIO
    source: Path

    def exact(self, size: int) -> bytes:
        value = self.stream.read(size)
        if len(value) != size:
            raise TexturePackError(
                f"unexpected end of {self.source} at byte {self.stream.tell()}"
            )
        return value

    def i32(self) -> int:
        return struct.unpack("<i", self.exact(4))[0]

    def string(self) -> str:
        size = self.i32()
        if size < 0 or size > MAX_STRING_BYTES:
            raise TexturePackError(f"invalid string length {size} in {self.source}")
        return self.exact(size).decode("latin-1")


def index_texture_pack(source: Path, output: Path, *, game_version: str) -> dict[str, object]:
    """Index PZ's version-1 pack metadata without copying proprietary image payloads."""
    source = source.resolve()
    pages: list[dict[str, object]] = []
    textures: dict[str, dict[str, object]] = {}
    with source.open("rb") as stream:
        reader = _Reader(stream, source)
        magic = reader.exact(4)
        if magic != MAGIC:
            raise TexturePackError(
                f"unsupported legacy texture pack (missing PZPK header): {source}"
            )
        version = reader.i32()
        if version != 1:
            raise TexturePackError(f"unsupported texture pack version {version}: {source}")
        page_count = reader.i32()
        _validate_count("page", page_count)
        for _ in range(page_count):
            page_name = reader.string()
            entry_count = reader.i32()
            _validate_count("subtexture", entry_count)
            has_alpha = reader.i32() != 0
            page_textures: list[str] = []
            for _entry in range(entry_count):
                name = reader.string()
                values = [reader.i32() for _component in range(8)]
                if name in textures:
                    raise TexturePackError(f"duplicate subtexture {name!r} in {source}")
                texture = {
                    "page": page_name,
                    "x": values[0],
                    "y": values[1],
                    "width": values[2],
                    "height": values[3],
                    "offset_x": values[4],
                    "offset_y": values[5],
                    "original_width": values[6],
                    "original_height": values[7],
                }
                textures[name] = texture
                page_textures.append(name)
            length_offset = stream.tell()
            png_length = reader.i32()
            remaining = source.stat().st_size - stream.tell()
            if png_length < len(PNG_SIGNATURE) or png_length > remaining:
                raise TexturePackError(
                    f"invalid PNG length {png_length} for page {page_name!r} in {source}"
                )
            png_offset = stream.tell()
            if reader.exact(len(PNG_SIGNATURE)) != PNG_SIGNATURE:
                raise TexturePackError(
                    f"page {page_name!r} does not start with a PNG signature in {source}"
                )
            stream.seek(png_offset + png_length)
            pages.append(
                {
                    "name": page_name,
                    "has_alpha": has_alpha,
                    "png_length_offset": length_offset,
                    "png_offset": png_offset,
                    "png_length": png_length,
                    "texture_count": len(page_textures),
                }
            )

    document: dict[str, object] = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "source": str(source),
        "source_sha256": sha256_file(source),
        "source_size": source.stat().st_size,
        "pack_version": version,
        "page_count": len(pages),
        "texture_count": len(textures),
        "pages": pages,
        "textures": textures,
    }
    write_json(output, document)
    return document


def extract_sprite_page(index_path: Path, sprite: str, output_directory: Path) -> dict[str, object]:
    """Extract only the atlas page needed by one sprite and record the crop rectangle."""
    index_path = index_path.resolve()
    output_directory = output_directory.resolve()
    document = json.loads(index_path.read_text(encoding="utf-8"))
    texture = document.get("textures", {}).get(sprite)
    if texture is None:
        raise TexturePackError(f"sprite {sprite!r} is absent from {index_path}")
    page_name = texture["page"]
    page = next((value for value in document["pages"] if value["name"] == page_name), None)
    if page is None:
        raise TexturePackError(f"page {page_name!r} is absent from {index_path}")
    source = Path(document["source"])
    if sha256_file(source) != document["source_sha256"]:
        raise TexturePackError(f"texture pack checksum changed since indexing: {source}")
    with source.open("rb") as stream:
        stream.seek(page["png_offset"])
        png = stream.read(page["png_length"])
    if len(png) != page["png_length"] or not png.startswith(PNG_SIGNATURE):
        raise TexturePackError(f"failed to read indexed page {page_name!r} from {source}")
    output_directory.mkdir(parents=True, exist_ok=True)
    page_path = output_directory / f"{page_name}.png"
    page_path.write_bytes(png)
    manifest = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": document["game_version"],
        "sprite": sprite,
        "page": page_name,
        "page_path": str(page_path),
        "page_sha256": sha256_file(page_path),
        "region": {
            key: texture[key]
            for key in (
                "x",
                "y",
                "width",
                "height",
                "offset_x",
                "offset_y",
                "original_width",
                "original_height",
            )
        },
        "source_pack": str(source),
        "source_pack_sha256": document["source_sha256"],
    }
    manifest_path = output_directory / f"{sprite}.json"
    write_json(manifest_path, manifest)
    manifest["manifest_path"] = str(manifest_path)
    return manifest


def _validate_count(kind: str, count: int) -> None:
    if count < 0 or count > MAX_COUNT:
        raise TexturePackError(f"invalid {kind} count {count}")
