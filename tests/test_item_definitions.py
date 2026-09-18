import json
import tempfile
import unittest
from pathlib import Path

from pzfps.item_definitions import compile_item_definitions


SCRIPT = """
module Base
{
    item Hammer
    {
        ItemType = base:weapon,
        Icon = Hammer,
        StaticModel = HammerModel,
        WorldStaticModel = HammerWorld,
    }

    item Paper
    {
        ItemType = base:normal,
        Icon = Paper,
    }
}
"""


class ItemDefinitionTests(unittest.TestCase):
    def test_indexes_every_item_and_resolves_world_model(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            scripts = root / "scripts"
            scripts.mkdir()
            (scripts / "items.txt").write_text(SCRIPT, encoding="utf-8")
            models = root / "models.json"
            models.write_text(json.dumps({
                "game_version": "42.20",
                "aliases": {"HammerWorld": "Base.HammerWorld"},
                "models": {"Base.HammerWorld": {}},
            }), encoding="utf-8")

            document = compile_item_definitions(
                scripts, models, root / "items.json", game_version="42.20")

            self.assertEqual(document["item_count"], 2)
            self.assertEqual(document["definition_count"], 2)
            self.assertEqual(document["duplicate_identity_count"], 0)
            self.assertEqual(document["items_with_world_model"], 1)
            self.assertEqual(document["world_models_resolved"], 1)
            hammer = document["items"]["Base.Hammer"]
            self.assertEqual(hammer["world_model"], "HammerWorld")
            self.assertEqual(hammer["resolved_model_identity"], "Base.HammerWorld")
            self.assertEqual(hammer["properties"]["Icon"], "Hammer")
            self.assertEqual(hammer["definition_count"], 1)

    def test_preserves_duplicate_definitions_and_selects_last(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            scripts = root / "scripts"
            scripts.mkdir()
            second = SCRIPT.replace(
                "StaticModel = HammerModel,\n        WorldStaticModel = HammerWorld,",
                "StaticModel = OtherModel,\n        WorldStaticModel = OtherWorld,")
            (scripts / "items.txt").write_text(SCRIPT + second, encoding="utf-8")
            models = root / "models.json"
            models.write_text(json.dumps({
                "game_version": "42.20",
                "aliases": {},
                "models": {},
            }), encoding="utf-8")

            document = compile_item_definitions(
                scripts, models, root / "items.json", game_version="42.20")

            self.assertEqual(document["item_count"], 2)
            self.assertEqual(document["definition_count"], 4)
            self.assertEqual(document["duplicate_identity_count"], 2)
            hammer = document["items"]["Base.Hammer"]
            self.assertEqual(hammer["definition_count"], 2)
            self.assertEqual(hammer["world_model"], "OtherWorld")
            self.assertEqual(len(hammer["definitions"]), 2)

    def test_rejects_mismatched_model_version(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            scripts = root / "scripts"
            scripts.mkdir()
            (scripts / "items.txt").write_text(SCRIPT, encoding="utf-8")
            models = root / "models.json"
            models.write_text(json.dumps({
                "game_version": "41",
                "aliases": {},
                "models": {},
            }), encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "different game versions"):
                compile_item_definitions(
                    scripts, models, root / "items.json", game_version="42.20")


if __name__ == "__main__":
    unittest.main()
