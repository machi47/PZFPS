import tempfile
import unittest
from pathlib import Path

from pzfps.tile_definitions import TileDefinitionParseError, compile_tile_definitions


SAMPLE = """version = 1
tileset
{
    file = roofs_02
    size = 8,16
    id = 110
    // roofs_02_3
    tile
    {
        xy = 3,0
        BlockRain =
        RoofGroup = 3
        attachedN =
        isEave =
    }
}
"""


class TileDefinitionTests(unittest.TestCase):
    def test_preserves_empty_boolean_and_roof_group_properties(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "definitions.txt"
            output = root / "definitions.json"
            source.write_text(SAMPLE)
            report = compile_tile_definitions(source, output, game_version="42.20")
            self.assertEqual(report["source_format_version"], "1")
            tile = report["tiles"]["roofs_02_3"]
            self.assertEqual(tile["xy"], [3, 0])
            self.assertEqual(tile["properties"]["RoofGroup"], "3")
            self.assertIn("BlockRain", tile["properties"])
            self.assertIn("attachedN", tile["properties"])
            self.assertIn("isEave", tile["properties"])

    def test_rejects_tile_without_coordinates(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            source = root / "definitions.txt"
            source.write_text(SAMPLE.replace("xy = 3,0", "notxy = 3,0"))
            with self.assertRaisesRegex(TileDefinitionParseError, "missing xy"):
                compile_tile_definitions(source, root / "out.json", game_version="42.20")
