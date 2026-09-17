import tempfile
import unittest
from pathlib import Path

from pzfps.model_assets import ModelScriptParser, compile_model_index
from pzfps.assets import tokenize


SCRIPT = """
module Base
{
    model Hammer
    {
        mesh = weapons/1handed/Hammer,
        texture = weapons/1handed/Hammer_New,
        scale = 0.125,
        attachment world
        {
            offset = 0.015 0.11 0.0,
            rotate = 180.0 0.0 180.0,
        }
    }
}
"""


class ModelAssetTests(unittest.TestCase):
    def test_parser_preserves_named_model_and_attachment(self) -> None:
        module = ModelScriptParser(tokenize(SCRIPT)).parse()[0]
        model = module.children[0]
        self.assertEqual((module.kind, module.identifier), ("module", "Base"))
        self.assertEqual((model.kind, model.identifier), ("model", "Hammer"))
        self.assertEqual(model.values["scale"], "0.125")
        self.assertEqual(model.children[0].identifier, "world")

    def test_index_resolves_exact_mesh_texture_and_world_transform(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            scripts = root / "scripts"
            models = root / "models_X"
            textures = root / "textures"
            (scripts / "generated").mkdir(parents=True)
            (models / "weapons" / "1handed").mkdir(parents=True)
            (textures / "weapons" / "1handed").mkdir(parents=True)
            source = scripts / "generated" / "models_weapons.txt"
            source.write_text(SCRIPT, encoding="utf-8")
            (models / "weapons" / "1handed" / "Hammer.fbx").write_bytes(b"fbx")
            (textures / "weapons" / "1handed" / "Hammer_New.png").write_bytes(b"png")

            document = compile_model_index(
                scripts,
                models,
                textures,
                root / "model-index.json",
                game_version="42.20",
            )

            model = document["models"]["Base.Hammer"]
            self.assertTrue(model["mesh_path"].endswith("Hammer.fbx"))
            self.assertTrue(model["texture_path"].endswith("Hammer_New.png"))
            self.assertEqual(model["world_attachment"]["offset"], [0.015, 0.11, 0.0])
            self.assertEqual(document["aliases"]["Hammer"], "Base.Hammer")
            self.assertEqual(document["mesh_file_count"], 1)
            self.assertEqual(document["texture_file_count"], 1)
            self.assertEqual(document["unresolved_mesh_count"], 0)
            self.assertEqual(document["unresolved_texture_count"], 0)

    def test_index_rejects_missing_install_roots(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            with self.assertRaisesRegex(FileNotFoundError, "scripts directory"):
                compile_model_index(
                    root / "missing-scripts",
                    root / "missing-models",
                    root / "missing-textures",
                    root / "model-index.json",
                    game_version="42.20",
                )


if __name__ == "__main__":
    unittest.main()
