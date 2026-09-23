import json
import tempfile
import unittest
from pathlib import Path

from pzfps.map_usage import MapUsageError, index_map_header_usage


class MapUsageTests(unittest.TestCase):
    def test_indexes_only_exact_atlas_identities_from_binary_headers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            maps = root / "maps"
            (maps / "Muldraugh, KY").mkdir(parents=True)
            (maps / "Challenge").mkdir()
            (maps / "Muldraugh, KY" / "1_2.lotheader").write_bytes(
                b"\x04\x00junk roofs_used_1\x00not_in_atlas_2\x00"
            )
            (maps / "Challenge" / "0_0.lotheader").write_bytes(
                b"\xffroofs_used_1\x00walls_used_3\x00"
            )
            textures = root / "textures.json"
            textures.write_text(json.dumps({
                "game_version": "42.20",
                "textures": {
                    "roofs_used_1": {},
                    "walls_used_3": {},
                    "atlas_only_4": {},
                },
            }))
            output = root / "map-usage.json"
            report = index_map_header_usage(
                maps, textures, output, game_version="42.20")

            self.assertEqual(report["source_header_count"], 2)
            self.assertEqual(report["referenced_identity_count"], 2)
            self.assertEqual(report["identities"]["roofs_used_1"]["header_count"], 2)
            self.assertEqual(
                report["identities"]["roofs_used_1"]["map_directories"],
                ["Challenge", "Muldraugh, KY"],
            )
            self.assertNotIn("not_in_atlas_2", report["identities"])
            self.assertNotIn("atlas_only_4", report["identities"])
            self.assertTrue(output.is_file())

    def test_rejects_texture_index_from_another_game_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            maps = root / "maps"
            maps.mkdir()
            textures = root / "textures.json"
            textures.write_text(json.dumps({"game_version": "41.78", "textures": {}}))
            with self.assertRaises(MapUsageError):
                index_map_header_usage(
                    maps, textures, root / "output.json", game_version="42.20")
