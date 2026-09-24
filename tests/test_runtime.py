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

    @patch("pzfps.runtime.pz_processes")
    def test_filters_project_isolated_clients(self, processes) -> None:
        processes.return_value = [
            {
                "pid": 101,
                "command": f"java -Ddeployment.user.cachedir={runtime.PZ_CACHE_PARENT} main",
            },
            {
                "pid": 202,
                "command": "/project/.local/pz-runtime/PZFPS Isolated.app/Contents/"
                "MacOS/JavaAppLauncher",
            },
            {
                "pid": 303,
                "command": "/game/ProjectZomboid/Project Zomboid.app/Contents/JavaAppLauncher",
            },
        ]
        self.assertEqual(
            [value["pid"] for value in runtime.isolated_pz_processes()], [101, 202]
        )

    @patch("pzfps.runtime.isolated_pz_processes")
    @patch("pzfps.runtime.os.kill")
    def test_packaged_process_state_follows_launchservices_successor(
        self, kill, isolated_processes
    ) -> None:
        kill.side_effect = ProcessLookupError
        isolated_processes.return_value = [
            {
                "pid": 202,
                "command": "/project/.local/pz-runtime/PZFPS Isolated.app/Contents/"
                "MacOS/JavaAppLauncher",
            }
        ]
        state = {
            "pid": 101,
            "kind": "packaged",
            "executable": "/project/.local/pz-runtime/PZFPS Isolated.app/Contents/"
            "MacOS/JavaAppLauncher",
        }
        with tempfile.TemporaryDirectory() as temporary_directory:
            state_path = Path(temporary_directory) / "isolated-game.json"
            state_path.write_text(runtime.json.dumps(state), encoding="utf-8")
            with patch.object(runtime, "STATE", state_path):
                result = runtime.process_state()

        self.assertTrue(result["running"])
        self.assertEqual(result["recorded_pid"], 101)
        self.assertEqual(result["pid"], 202)

    @patch("pzfps.runtime.write_json")
    @patch("pzfps.runtime.time.sleep")
    @patch("pzfps.runtime.os.kill")
    @patch("pzfps.runtime.isolated_pz_processes")
    @patch("pzfps.runtime.process_state")
    def test_stop_terminates_unrecorded_packaged_client(
        self, process_state, isolated_processes, kill, _sleep, write_json
    ) -> None:
        process_state.return_value = {"pid": 101, "running": False}
        isolated_processes.side_effect = [
            [{"pid": 202, "command": "PZFPS Isolated.app/Contents/MacOS/JavaAppLauncher"}],
            [],
        ]
        result = runtime.stop()
        kill.assert_called_once_with(202, runtime.signal.SIGTERM)
        self.assertEqual(result["stopped_pids"], [202])
        self.assertFalse(result["running"])
        write_json.assert_called_once()

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

    @patch("pzfps.runtime._gradle_executable", return_value=Path("/tools/gradle"))
    @patch("pzfps.runtime.subprocess.run")
    def test_bridge_is_built_from_current_sources_before_staging(self, run, _gradle) -> None:
        run.return_value.returncode = 0
        run.return_value.stdout = ""
        run.return_value.stderr = ""
        game = Path("/game/projectzomboid.jar")
        agent = Path("/project/ZombieBuddy.jar")

        runtime._build_bridge(game, agent)

        args, kwargs = run.call_args
        self.assertEqual(args[0], ["/tools/gradle", "jar"])
        self.assertEqual(kwargs["cwd"], runtime.ROOT / "bridge")
        self.assertEqual(kwargs["env"]["PZ_JAR"], str(game))
        self.assertEqual(kwargs["env"]["ZOMBIE_BUDDY_JAR"], str(agent))
