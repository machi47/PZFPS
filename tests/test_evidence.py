from __future__ import annotations

import csv
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from pzfps import evidence


class EvidenceTests(unittest.TestCase):
    def test_preserves_source_and_enhanced_and_accepts_measured_live_run(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / ".local"
            with patch.object(evidence, "LOCAL", local):
                run = evidence.create_run("House Route", {"readiness": {}})
                source = Path(directory) / "source.mov"
                enhanced = Path(directory) / "enhanced.mov"
                source.write_bytes(b"source")
                enhanced.write_bytes(b"enhanced")
                evidence.add_evidence(run.name, "first-person", "source", source)
                evidence.add_evidence(run.name, "neural", "enhanced", enhanced)
                evidence.add_metric(
                    run.name,
                    {
                        "baseline": "neural",
                        "phase": "steady",
                        "completed_neural_fps": 30.0,
                        "gameplay_controllable": True,
                        "important_state_preserved": True,
                        "method": "fixture",
                    },
                )
                manifest = evidence.finalize_run(run.name, "LIVE FIRST-PERSON ACCEPTED", True)
                self.assertEqual(manifest["checkpoint"], "LIVE FIRST-PERSON ACCEPTED")
                with (run / "metrics" / "frames.csv").open(encoding="utf-8") as handle:
                    rows = list(csv.DictReader(handle))
                self.assertEqual(rows[0]["completed_neural_fps"], "30.0")

    def test_live_checkpoint_rejects_configured_rate_without_measurement(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            local = Path(directory) / ".local"
            with patch.object(evidence, "LOCAL", local):
                run = evidence.create_run("unmeasured", {})
                with self.assertRaisesRegex(ValueError, "source and enhanced"):
                    evidence.finalize_run(run.name, "LIVE OVERLAY WORKING", False)


if __name__ == "__main__":
    unittest.main()
