from __future__ import annotations

import argparse
import asyncio
import json
import logging
from pathlib import Path
import sys

import numpy as np
from PIL import Image

from .appearance import Atlas, Bake, linear_to_srgb, render
from .geometry import Camera, Mesh, rasterize
from .inference import DiffusersInpainter
from .pipeline import Compiler, read_json
from .store import Store, canonical_json


def make_fixture(directory: Path) -> Path:
    """Original calibration fixture, explicitly not an extracted PZ asset."""
    directory.mkdir(parents=True, exist_ok=True)
    mesh = Mesh.box(np.array([[-.5, 0, -.3], [.5, 1.5, .3]]))
    specs = []
    # Only two observed views. Rear surface must be completed, not textured with
    # the front handles. Source RGB is procedurally authored for regression.
    for index, direction in enumerate(([3, 1, 3], [-3, 1, 3])):
        camera = Camera.look_at(direction, [0, .75, 0], 100, 224)
        raster = rasterize(mesh, camera)
        valid = raster.face >= 0
        point = np.einsum("ni,nij->nj", raster.barycentric[valid], mesh.vertices[mesh.faces[raster.face[valid]]])
        rgb = np.zeros((*valid.shape, 4), dtype=np.uint8)
        wood = .40 + .04 * np.sin(point[:, 1] * 130 + np.sin(point[:, 0] * 11))
        color = np.column_stack([wood * 1.15, wood * .79, wood * .46])
        front = point[:, 2] > .299
        seams = front & ((np.abs(point[:, 0]) < .012) | (np.abs(point[:, 1] - .48) < .012))
        handles = front & (np.abs(np.abs(point[:, 0]) - .07) < .016) & (point[:, 1] > .7) & (point[:, 1] < .94)
        color[seams] *= .35
        color[handles] = [.12, .13, .13]
        rgb[valid, :3] = (color * 255).astype(np.uint8)
        rgb[valid, 3] = 255
        name = f"source-{index}.png"
        Image.fromarray(rgb).save(directory / name)
        specs.append({"name": f"original-test-view-{index}", "image": name, "matrix": camera.matrix.tolist()})
    job = {"schema_version": 1, "prototype": "fixture:cupboard-NOT-PZ", "source_revision": "original-procedural-regression-1", "bounds": mesh.bounds.tolist(), "geometry": {"vertices": mesh.vertices.tolist(), "faces": mesh.faces.tolist()}, "views": specs, "atlas_size": 192, "padding": 5, "roughness": .82, "source_is_unlit": True, "completion": {"mode": "harmonic"}}
    path = directory / "job.json"
    path.write_bytes(canonical_json(job))
    return path


def orbit(asset: Path, output: Path, frames: int = 24) -> dict:
    if not 4 <= frames <= 240:
        raise ValueError("orbit frames must be in [4,240]")
    output.mkdir(parents=True, exist_ok=True)
    with np.load(asset / "surface.npz", allow_pickle=False) as data:
        mesh = Mesh(data["vertices"], data["faces"])
        size = data["color"].shape[0]
        atlas = Atlas(mesh, data["uv"], np.zeros((size, size), dtype=np.int32), np.zeros((size, size, 3)), np.zeros((size, size, 3)), data["valid"], size)
        baked = Bake(atlas, data["color"], data["confidence"], data["observed"], data["generated"], np.zeros((size, size), dtype=np.int32), np.zeros((size, size)))
    center = mesh.bounds.mean(axis=0)
    scale = 180 / np.linalg.norm(mesh.bounds[1] - mesh.bounds[0])
    images = []
    for index in range(frames):
        angle = 2 * np.pi * index / frames
        camera = Camera.look_at([np.sin(angle), .22, np.cos(angle)], center, scale, 256)
        rgba, _ = render(baked, camera, lit=True)
        image = Image.fromarray(rgba)
        image.save(output / f"orbit-{index:03}.png")
        background = Image.new("RGBA", image.size, (30, 33, 37, 255))
        images.append(Image.alpha_composite(background, image).convert("RGB"))
    images[0].save(output / "orbit.gif", save_all=True, append_images=images[1:], duration=80, loop=0)
    # Exact repeat of one camera tests retained representation, not stochastic
    # image generation. It is not a claim that the renderer is artifact-free.
    first, _ = render(baked, Camera.look_at([0, .22, 1], center, scale, 256), lit=True)
    repeat, _ = render(baked, Camera.look_at([0, .22, 1], center, scale, 256), lit=True)
    return {"frames": frames, "repeat_camera_identical": bool(np.array_equal(first, repeat)), "kind": "offline-canonical-orbit-not-gameplay"}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="pzcanonical")
    parser.add_argument("--verbose", action="store_true")
    commands = parser.add_subparsers(dest="command", required=True)
    compile_parser = commands.add_parser("compile")
    compile_parser.add_argument("manifest", type=Path, nargs="+")
    compile_parser.add_argument("--store", type=Path, default=Path(".local/canonical"))
    compile_parser.add_argument("--model", type=Path)
    compile_parser.add_argument("--concurrency", type=int, default=2)
    fixture_parser = commands.add_parser("fixture")
    fixture_parser.add_argument("--output", type=Path, default=Path(".local/canonical-fixture"))
    orbit_parser = commands.add_parser("orbit")
    orbit_parser.add_argument("asset", type=Path)
    orbit_parser.add_argument("--output", type=Path, default=Path(".local/canonical-orbit"))
    orbit_parser.add_argument("--frames", type=int, default=24)
    arguments = parser.parse_args(argv)
    logging.basicConfig(level=logging.DEBUG if arguments.verbose else logging.INFO, format="%(levelname)s %(message)s")
    try:
        if arguments.command == "fixture":
            manifest = make_fixture(arguments.output)
            result = Compiler(Store(arguments.output / "store")).compile(manifest)
            proof = orbit(result.path, arguments.output / "orbit")
            print(json.dumps({"manifest": str(manifest), "asset": str(result.path), "report": result.report, "orbit": proof}, indent=2))
        elif arguments.command == "compile":
            provider = DiffusersInpainter(arguments.model) if arguments.model else None
            results = asyncio.run(Compiler(Store(arguments.store), provider).compile_many(arguments.manifest, arguments.concurrency))
            print(json.dumps([{"key": r.key, "path": str(r.path), "cache_hit": r.cache_hit, "report": r.report} for r in results], indent=2))
        else:
            print(json.dumps(orbit(arguments.asset, arguments.output, arguments.frames), indent=2))
        return 0
    except (OSError, ValueError, RuntimeError, ImportError, KeyError) as error:
        logging.error("%s: %s", type(error).__name__, error)
        return 2


if __name__ == "__main__":
    sys.exit(main())
