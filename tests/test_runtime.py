import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pzfps import runtime


class RuntimeProcessTests(unittest.TestCase):
    def test_agent_uses_project_local_zombie_buddy_config(self) -> None:
        option = runtime._agent_option(Path("/tmp/ZombieBuddy.jar"), Path("/tmp/PZFPSBridge.jar"))
        self.assertEqual(
            option,
            "-javaagent:/tmp/ZombieBuddy.jar=verbosity=1,policy=deny-new,"
            f"config_dir={runtime.ZB_CONFIG}",
        )

    @patch("pzfps.runtime.subprocess.run")
    def test_detects_direct_and_packaged_clients(self, run) -> None:
        run.return_value.returncode = 0
        run.return_value.stdout = (
            "101 /game/ProjectZomboid/Project Zomboid.app/Contents/PlugIns/jre/bin/java "
            "zombie.gameStates.MainScreenState\n"
            "202 /project/.local/pz-runtime/PZFPS Isolated.app/Contents/MacOS/JavaAppLauncher\n"
            "303 unrelated\n"
        )
        self.assertEqual([value["pid"] for value in runtime.pz_processes()], [101, 202])

    def test_project_local_guard_rejects_external_path(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            with self.assertRaisesRegex(runtime.RuntimeError, "non-project"):
                runtime._require_project_local(Path(temporary_directory) / "external")

    def test_windowed_profile_changes_only_display_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            cache = Path(temporary_directory)
            options = cache / "options.ini"
            options.write_text(
                "fullScreen=true\nborderless=true\nwidth=1920\nheight=1200\nvolume=7\n",
                encoding="utf-8",
            )
            with patch.object(runtime, "PZ_CACHE", cache):
                runtime._configure_windowed_profile()
            self.assertEqual(
                options.read_text(encoding="utf-8"),
                "fullScreen=false\nborderless=false\nwidth=1600\nheight=1000\nvolume=7\n",
            )
