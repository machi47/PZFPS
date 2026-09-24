import json
import math
import struct
import tempfile
import unittest
from pathlib import Path
from unittest import mock
import zlib

from pzfps.depth_surfaces import Plane3, PngPixels, point_on_implicit_plane
from pzfps.prop_surfaces import audit_prop_surfaces, compile_planar_prop_surfaces


def png_gray_alpha(width: int, height: int, pixels: list[tuple[int, int]]) -> bytes:
    raw = b"".join(
        b"\x00"
        + bytes(channel for pixel in pixels[y * width:(y + 1) * width] for channel in pixel)
        for y in range(height)
    )

    def chunk(kind: bytes, payload: bytes) -> bytes:
        body = kind + payload
        return struct.pack(">I", len(payload)) + body + struct.pack(">I", zlib.crc32(body))

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 4, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw))
        + chunk(b"IEND", b"")
    )


class PropSurfaceTests(unittest.TestCase):
    def test_compiler_intersects_oversized_depth_support_with_exact_sprite_alpha(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            definitions = root / "definitions.json"
            textures = root / "textures.json"
            usage = root / "usage.json"
            assignments = root / "assignments.txt"
            depthmaps = root / "depthmaps"
            output = root / "prop-surfaces.json"
            depthmaps.mkdir()
            identity = "furniture_storage_test_0"
            definitions.write_text(json.dumps({
                "game_version": "42.20",
                "tiles": {
                    identity: {
                        "tileset": "furniture_storage_test",
                        "xy": [0, 0],
                        "properties": {
                            "MoveType": "WallObject",
                            "attachedW": "",
                        },
                    },
                },
            }))
            textures.write_text(json.dumps({
                "game_version": "42.20",
                "source_sha256": "fixture-pack",
                "textures": {
                    identity: {
                        "page": "fixture_page", "x": 0, "y": 0,
                        "width": 128, "height": 256,
                        "offset_x": 0, "offset_y": 0,
                        "original_width": 128, "original_height": 256,
                    },
                },
            }))
            usage.write_text(json.dumps({
                "game_version": "42.20",
                "identities": {identity: {"header_count": 1}},
            }))
            assignments.write_text("tileDepthTextureAssignments\n{\nVERSION = 1,\n}\n")

            source_alpha = bytearray(128 * 256)
            source_pixels = {
                (x, y)
                for y in range(104, 154)
                for x in range(42, 86)
            }
            for x, y in source_pixels:
                source_alpha[y * 128 + x] = 255
            source_page = PngPixels(
                128,
                256,
                bytes(128 * 256),
                bytes(source_alpha),
            )

            # The depth support covers the whole tile. Only exact sprite-alpha pixels may
            # become visible geometry; this is the guard against the giant switch slab bug.
            plane = Plane3((0.0, 1.0, 0.0), -0.8, 0.0, 0.0)
            depth_pixels: list[tuple[int, int]] = []
            for y in range(256):
                for x in range(128):
                    world_x, world_y, world_z = point_on_implicit_plane(
                        x + 0.5, y + 0.5, plane
                    )
                    depth = (
                        0.75
                        - 0.25 * (world_x + world_z)
                        - world_y / (2.0 * math.sqrt(6.0))
                    )
                    depth_pixels.append((max(0, min(255, round(depth * 255))), 255))
            (depthmaps / "DEPTH_furniture_storage_test.png").write_bytes(
                png_gray_alpha(128, 256, depth_pixels)
            )

            with (
                mock.patch(
                    "pzfps.prop_surfaces.read_indexed_pages",
                    return_value={"fixture_page": b"source-page"},
                ),
                mock.patch(
                    "pzfps.prop_surfaces.decode_png",
                    return_value=source_page,
                ),
            ):
                report = compile_planar_prop_surfaces(
                    definitions,
                    assignments,
                    depthmaps,
                    textures,
                    usage,
                    output,
                    game_version="42.20",
                    map_referenced_only=True,
                )

            self.assertEqual(report["candidate_count"], 1)
            self.assertEqual(report["tile_count"], 1)
            self.assertEqual(report["wall_attachment_tile_count"], 1)
            compiled = report["tiles"][identity]
            properties = compiled["properties"]
            self.assertTrue(properties["replace_authored_geometry"])
            self.assertEqual(properties["source_alpha_pixels"], len(source_pixels))
            self.assertEqual(properties["source_mask_depth_pixels"], len(source_pixels))
            self.assertGreaterEqual(properties["fitted_source_coverage"], 0.98)
            self.assertEqual(properties["wall_attachment_edge"], "W")
            self.assertEqual(
                properties["wall_attachment_evidence"],
                "MoveType=WallObject+attachedW",
            )
            self.assertEqual(properties["wall_attachment_target_clearance"], 0.002)
            self.assertIn("wall_attachment_source_clearance", properties)
            self.assertIn("wall_attachment_translation", properties)
            self.assertTrue(compiled["geometry"])
            self.assertTrue(all(
                primitive["kind"] == "quad" for primitive in compiled["geometry"]
            ))
            self.assertTrue(all(
                primitive["mesh_method"] == "opaque_mask_rectangles"
                for primitive in compiled["geometry"]
            ))
            self.assertEqual(audit_prop_surfaces(report), report["audit"])


if __name__ == "__main__":
    unittest.main()
