import tempfile
import unittest
from pathlib import Path

from pzfps.assets import Parser, compile_geometry, parse_vector, tokenize


SAMPLE = """
tileGeometry {
  VERSION = 2,
  tileset {
    name = furniture_test_01,
    tile {
      xy = 1x2,
      box {
        translate = 10000x0x-5000,
        rotate = 0x900000x0,
        min = -5000x0x-5000,
        max = 5000x10000x5000,
      }
      polygon {
        translate = 0x0x0,
        rotate = 0x0x0,
        plane = XY,
        points = -5000x0 5000x0 0x10000,
      }
      properties { Surface = 12, Translucent = true, }
    }
  }
}
"""


class AssetCompilerTests(unittest.TestCase):
    def test_parser_preserves_nested_geometry(self) -> None:
        root = Parser(tokenize(SAMPLE)).parse()
        tileset = next(root.children_named("tileset"))
        tile = next(tileset.children_named("tile"))
        self.assertEqual(tileset.values["name"], "furniture_test_01")
        self.assertEqual(tile.values["xy"], "1x2")
        self.assertEqual(
            [child.name for child in tile.children],
            ["box", "polygon", "properties"],
        )

    def test_compile_geometry_normalizes_pz_units(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "tileGeometry.txt"
            output = root / "geometry.json"
            source.write_text(SAMPLE, encoding="utf-8")
            document = compile_geometry(source, output, game_version="42.20")
            tile = document["tiles"]["furniture_test_01_17"]
            self.assertEqual(tile["geometry"][0]["translate"], [1.0, 0.0, -0.5])
            self.assertEqual(tile["geometry"][0]["rotate_degrees"], [0.0, 90.0, 0.0])
            self.assertEqual(tile["geometry"][1]["points"][2], [0.0, 1.0])
            self.assertEqual(tile["properties"]["Surface"], "12")
            self.assertTrue(output.is_file())

    def test_parse_vector_rejects_wrong_dimension(self) -> None:
        with self.assertRaisesRegex(ValueError, "3-component"):
            parse_vector("1x2", 3)
