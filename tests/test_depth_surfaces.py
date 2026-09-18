import json
import struct
import tempfile
import unittest
from pathlib import Path
import zlib

from pzfps.depth_surfaces import (
    compile_planar_roof_surfaces,
    audit_roof_surfaces,
    depth_point,
    fit_planar_surface,
    fit_piecewise_planar_surfaces,
    parse_depth_assignments,
    Plane3,
    point_on_implicit_plane,
    point_on_plane,
    polygon_area,
    read_png,
    split_tile_identity,
)


def png_gray_alpha(width: int, height: int, pixels: list[tuple[int, int]]) -> bytes:
    raw = b"".join(b"\x00" + bytes(channel for pixel in pixels[y * width:(y + 1) * width] for channel in pixel)
                   for y in range(height))
    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body))
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 4, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


class DepthSurfaceTests(unittest.TestCase):
    def test_depth_helper_identity_resolves_without_tile_definition(self) -> None:
        self.assertEqual(split_tile_identity("preset_depthmaps_01_5"), ("preset_depthmaps_01", 5))

    def test_polygon_area_reports_convex_hull_coverage(self) -> None:
        self.assertEqual(polygon_area([(0, 0), (3, 0), (3, 2), (0, 2)]), 6)

    def test_reads_depth_and_alpha_without_external_image_package(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "depth.png"
            path.write_bytes(png_gray_alpha(2, 1, [(17, 255), (99, 0)]))
            image = read_png(path)
            self.assertEqual((image.width, image.height), (2, 1))
            self.assertEqual(list(image.depth), [17, 99])
            self.assertEqual(list(image.alpha), [255, 0])

    def test_projection_depth_round_trip_fits_source_plane(self) -> None:
        # y=.8*x+.2*z+1.1, sampled through PZ's documented source projection/depth equations.
        samples = []
        for x in (-.5, 0, .5):
            for z in (-.5, 0, .5):
                y = .8 * x + .2 * z + 1.1
                u = 64 + (x - z) * 64
                v = 224 + (x + z) * 32 - y * 64 * (1.5 ** .5)
                depth = .75 - .25 * (x + z) - y / (2 * (6 ** .5))
                samples.append((u, v, depth))
                actual = depth_point(u, v, depth)
                self.assertAlmostEqual(actual[0], x, places=6)
                self.assertAlmostEqual(actual[1], y, places=6)
                self.assertAlmostEqual(actual[2], z, places=6)
        plane = fit_planar_surface(samples)
        self.assertIsNotNone(plane)
        assert plane is not None
        point = point_on_plane(64, 224 - 1.1 * 64 * (1.5 ** .5), plane)
        self.assertAlmostEqual(point[1], 1.1, places=5)

    def test_piecewise_fit_recovers_horizontal_and_vertical_depth_planes(self) -> None:
        horizontal = Plane3((0.0, 1.0, 0.0), -0.8, 0.0, 0.0)
        vertical = Plane3((0.0, 0.0, 1.0), -0.2, 0.0, 0.0)
        samples = []
        for v in range(48, 209):
            for u in range(8, 121):
                plane = horizontal if u < 64 else vertical
                x, y, z = point_on_implicit_plane(u + 0.5, v + 0.5, plane)
                depth = 0.75 - 0.25 * (x + z) - y / (2 * (6 ** 0.5))
                samples.append((u + 0.5, v + 0.5, depth))
        patches = fit_piecewise_planar_surfaces(samples)
        self.assertGreaterEqual(len(patches), 2)
        self.assertGreaterEqual(sum(len(patch.samples) for patch in patches), len(samples) * 0.95)

    def test_compiles_only_planar_roof_depth_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            definitions = root / "definitions.json"
            assignments = root / "assignments.txt"
            depthmaps = root / "depthmaps"
            depthmaps.mkdir()
            definitions.write_text(json.dumps({
                "game_version": "42.20",
                "tiles": {
                    "roofs_color_0": {"tileset": "roofs_color", "xy": [0, 0], "properties": {"RoofGroup": "3"}},
                    "roofs_depth_0": {"tileset": "roofs_depth", "xy": [0, 0], "properties": {"RoofGroup": "1"}},
                    "walls_exterior_roofs_color_0": {
                        "tileset": "walls_exterior_roofs_color", "xy": [0, 0], "properties": {}
                    },
                },
            }))
            assignments.write_text(
                "tileDepthTextureAssignments\n{\nVERSION = 1,\n"
                "roofs_color_0 = roofs_depth_0,\n"
                "walls_exterior_roofs_color_0 = preset_depthmaps_01_0,\n}\n"
            )
            pixels = []
            for y in range(256):
                for x in range(128):
                    # A constant-y plane encoded through the installed normalisation.
                    u, v = x + .5, y + .5
                    difference = (u - 64) / 64
                    total = (v - 224 + 64 * (1.5 ** .5)) / 32
                    world_x = (total + difference) / 2
                    world_z = (total - difference) / 2
                    depth = .75 - .25 * (world_x + world_z) - 1 / (2 * (6 ** .5))
                    visible = 8 <= x < 120 and 40 <= y < 216 and 0 < depth < 1
                    pixels.append((max(0, min(255, round(depth * 255))), 255 if visible else 0))
            (depthmaps / "DEPTH_roofs_depth.png").write_bytes(png_gray_alpha(128, 256, pixels))
            (depthmaps / "DEPTH_preset_depthmaps_01.png").write_bytes(
                png_gray_alpha(128, 256, pixels)
            )
            output = root / "surfaces.json"
            report = compile_planar_roof_surfaces(
                definitions, assignments, depthmaps, output, game_version="42.20")
            self.assertIn("roofs_color_0", report["tiles"])
            self.assertIn("walls_exterior_roofs_color_0", report["tiles"])
            self.assertEqual(
                report["tiles"]["walls_exterior_roofs_color_0"]["properties"]["depth_target"],
                "preset_depthmaps_01_0",
            )
            self.assertGreaterEqual(len(report["tiles"]["roofs_color_0"]["geometry"]), 2)
            self.assertEqual(parse_depth_assignments(assignments)["roofs_color_0"], "roofs_depth_0")
            self.assertGreater(report["audit"]["finite_points"], 0)
            self.assertEqual(audit_roof_surfaces(report), report["audit"])


if __name__ == "__main__":
    unittest.main()
