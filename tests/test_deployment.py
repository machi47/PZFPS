from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pzfps import deployment


class DeploymentTests(unittest.TestCase):
    def test_cleanup_only_removes_unchanged_project_owned_artifact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            local = base / "workspace" / ".local"
            destination = base / "external" / "mods" / "PZFPSExperiment"
            with patch.object(deployment, "LOCAL", local):
                manifest = deployment.record_before(destination, "project")
                destination.mkdir(parents=True)
                (destination / "mod.info").write_text("id=test\n", encoding="utf-8")
                deployment.record_installed(manifest)
                action = deployment.cleanup(manifest, confirmed=True)
                self.assertFalse(destination.exists())
                self.assertIn("removed", action)

    def test_cleanup_refuses_user_edits(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            local = base / "workspace" / ".local"
            destination = base / "external" / "mods" / "PZFPSExperiment"
            with patch.object(deployment, "LOCAL", local):
                manifest = deployment.record_before(destination, "project")
                destination.mkdir(parents=True)
                file = destination / "mod.info"
                file.write_text("id=test\n", encoding="utf-8")
                deployment.record_installed(manifest)
                file.write_text("id=user-edit\n", encoding="utf-8")
                with self.assertRaisesRegex(ValueError, "user edits"):
                    deployment.cleanup(manifest, confirmed=True)

    def test_workshop_cleanup_is_left_to_steam(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            local = base / "workspace" / ".local"
            destination = base / "external" / "workshop" / "3786653645"
            with patch.object(deployment, "LOCAL", local):
                manifest = deployment.record_before(destination, "steam-workshop")
                self.assertIn("unsubscribe through Steam", manifest.read_text(encoding="utf-8"))
                destination.mkdir(parents=True)
                (destination / "mod.info").write_text("id=FirstPersonView\n", encoding="utf-8")
                deployment.record_installed(manifest)
                with self.assertRaisesRegex(ValueError, "removed by its owner"):
                    deployment.cleanup(manifest, confirmed=True)


if __name__ == "__main__":
    unittest.main()
