import struct
import tempfile
import unittest
from pathlib import Path

from pzfps.texture_packs import (
    PNG_SIGNATURE,
    TexturePackError,
    extract_sprite_page,
    index_texture_pack,
    read_indexed_pages,
)


def _i32(value: int) -> bytes:
    return struct.pack("<i", value)


def _string(value: str) -> bytes:
    encoded = value.encode("latin-1")
    return _i32(len(encoded)) + encoded


class TexturePackTests(unittest.TestCase):
    def test_indexes_and_extracts_one_page(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            pack = root / "Tiles2x.pack"
            png = PNG_SIGNATURE + b"synthetic-png-payload"
            pack.write_bytes(
                b"PZPK"
                + _i32(1)
                + _i32(1)
                + _string("atlas_page")
                + _i32(1)
                + _i32(1)
                + _string("furniture_bedding_01_0")
                + b"".join(_i32(value) for value in (10, 20, 64, 128, 2, 3, 68, 134))
                + _i32(len(png))
                + png
            )
            index = root / "index.json"
            document = index_texture_pack(pack, index, game_version="42.20")
            self.assertEqual(document["page_count"], 1)
            self.assertEqual(document["texture_count"], 1)
            texture = document["textures"]["furniture_bedding_01_0"]
            self.assertEqual(texture["width"], 64)
            manifest = extract_sprite_page(index, "furniture_bedding_01_0", root / "out")
            self.assertEqual(Path(manifest["page_path"]).read_bytes(), png)
            self.assertEqual(manifest["region"]["original_height"], 134)
            self.assertEqual(read_indexed_pages(document, ["atlas_page"]), {
                "atlas_page": png,
            })

    def test_rejects_legacy_pack(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            pack = root / "legacy.pack"
            pack.write_bytes(b"nope")
            with self.assertRaisesRegex(TexturePackError, "legacy"):
                index_texture_pack(pack, root / "index.json", game_version="42.20")
