from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from pzfps.doctor import _manifest, _quoted_pairs, steam_libraries


class DoctorParsingTests(unittest.TestCase):
    def test_quoted_pairs(self) -> None:
        text = '"AppState"\n{\n  "appid"  "108600"\n  "buildid"  "24909800"\n}'
        self.assertEqual(
            _quoted_pairs(text),
            [("appid", "108600"), ("buildid", "24909800")],
        )

    def test_manifest_reads_leaf_values(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "appmanifest.acf"
            path.write_text('"appid" "108600"\n"buildid" "24909800"\n', encoding="utf-8")
            self.assertEqual(_manifest(path)["buildid"], "24909800")

    def test_steam_libraries_deduplicates_default(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "steamapps").mkdir()
            (root / "steamapps" / "libraryfolders.vdf").write_text(
                f'"path" "{root}"\n"path" "/Volumes/Games"\n', encoding="utf-8"
            )
            self.assertEqual(steam_libraries(root), [root, Path("/Volumes/Games")])


if __name__ == "__main__":
    unittest.main()
