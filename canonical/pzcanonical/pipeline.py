"""Manifest-to-canonical-GLB pipeline, using the installed source manifest format."""
from __future__ import annotations

import asyncio
from dataclasses import dataclass
import hashlib
import json
import logging
from pathlib import Path

import numpy as np
from PIL import Image
import trimesh

from . import __version__
from .appearance import View, bake_views, complete_harmonic, complete_novel_views, unwrap
from .geometry import Camera, Mesh, finite, visual_hull
from .inference import DiffusersInpainter
from .store import Store, canonical_json, digest

LOG = logging.getLogger(__name__)


def read_json(path: Path) -> dict:
    if path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("manifest too large")
    value = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(value, dict):
        raise ValueError("manifest root must be an object")
    return value


def isolated_sprite(path: Path) -> np.ndarray:
    """Consume the local session's extract-sprite manifest; prevent atlas bleed."""
    manifest = read_json(path)
    if manifest.get("schema_version") != 1:
        raise ValueError("unsupported sprite manifest")
    page = Path(manifest["page_path"])
    if not page.is_absolute():
        page = path.parent / page
    data = page.read_bytes()
    if manifest.get("page_sha256") and digest(data) != manifest["page_sha256"]:
        raise ValueError("source atlas checksum differs from extraction manifest")
    region = manifest["region"]
    required = ("x", "y", "width", "height", "offset_x", "offset_y", "original_width", "original_height")
    if any(not isinstance(region.get(k), int) for k in required):
        raise ValueError("sprite region requires integer crop/offset/original dimensions")
    x, y, w, h, ox, oy, ow, oh = (region[k] for k in required)
    with Image.open(page) as image:
        if min(x, y, ox, oy) < 0 or min(w, h, ow, oh) <= 0 or max(ow, oh) > 16384:
            raise ValueError("invalid crop or original sprite bounds")
        if x + w > image.width or y + h > image.height or ox + w > ow or oy + h > oh:
            raise ValueError("sprite crop lies outside atlas or calibrated original bounds")
        canvas = Image.new("RGBA", (ow, oh), (0, 0, 0, 0))
        canvas.paste(image.convert("RGBA").crop((x, y, x + w, y + h)), (ox, oy))
    return np.asarray(canvas).copy()


def load_view(specification: dict, root: Path) -> View:
    if ("sprite_manifest" in specification) == ("image" in specification):
        raise ValueError("view needs exactly one image or extracted sprite_manifest")
    if "sprite_manifest" in specification:
        rgba = isolated_sprite(root / specification["sprite_manifest"])
    else:
        with Image.open(root / specification["image"]) as image:
            rgba = np.asarray(image.convert("RGBA")).copy()
    camera = Camera(specification["matrix"], rgba.shape[1], rgba.shape[0])
    depth = np.load(root / specification["depth"], allow_pickle=False) if "depth" in specification else None
    return View(str(specification["name"]), camera, rgba, depth, float(specification.get("weight", 1)))


def load_job(path: Path) -> tuple[dict, Mesh, list[View]]:
    job = read_json(path)
    if job.get("schema_version") != 1 or not job.get("prototype") or not job.get("source_revision"):
        raise ValueError("job requires schema_version=1, prototype and source_revision")
    views = [load_view(view, path.parent) for view in job["views"]]
    geometry = job["geometry"]
    if "file" in geometry:
        mesh = Mesh.load(path.parent / geometry["file"])
    elif "vertices" in geometry:
        mesh = Mesh(geometry["vertices"], np.asarray(geometry["faces"]))
    elif "primitives" in geometry:
        meshes = []
        for primitive in geometry["primitives"]:
            if primitive["kind"] != "box":
                raise ValueError("primitive adapter currently accepts explicit boxes only; supply complete meshes for other geometry")
            part = Mesh.box(primitive["bounds"])
            if "transform" in primitive:
                part = part.transformed(primitive["transform"])
            meshes.append(part)
        if not meshes:
            raise ValueError("no geometry primitives")
        offsets = np.cumsum([0] + [len(m.vertices) for m in meshes[:-1]])
        mesh = Mesh(np.concatenate([m.vertices for m in meshes]), np.concatenate([m.faces + offset for m, offset in zip(meshes, offsets)]))
    elif geometry.get("method") == "visual_hull":
        mesh = visual_hull(job["bounds"], [(v.camera, v.rgba[..., 3] > 127) for v in views], int(geometry.get("resolution", 48)))
    else:
        raise ValueError("geometry requires file, vertices/faces, primitives or calibrated visual_hull")
    mesh.validate_bounds(job["bounds"])
    return job, mesh, views


def asset_key(job: dict, mesh: Mesh, views: list[View], model_fingerprint: str = "none") -> str:
    state = hashlib.sha256()
    settings = {key: job.get(key) for key in ("prototype", "source_revision", "bounds", "atlas_size", "padding", "depth_tolerance", "roughness", "metallic", "material_groups", "completion", "source_is_unlit")}
    state.update(canonical_json({"compiler": __version__, "settings": settings, "model": model_fingerprint}))
    for array in (mesh.vertices.astype("<f8"), mesh.faces.astype("<i8")):
        state.update(str(array.shape).encode()); state.update(array.tobytes())
    for view in views:
        state.update(canonical_json([view.name, view.weight, view.camera.width, view.camera.height]))
        state.update(view.camera.matrix.astype("<f8").tobytes())
        state.update(view.rgba.tobytes())
        state.update(b"depth:none" if view.depth is None else np.asarray(view.depth, dtype="<f8").tobytes())
    return state.hexdigest()


@dataclass(frozen=True)
class Result:
    key: str
    path: Path
    cache_hit: bool
    report: dict


class Compiler:
    def __init__(self, store: Store, inpainter: DiffusersInpainter | None = None):
        self.store = store
        self.inpainter = inpainter

    def compile(self, manifest: Path) -> Result:
        job, mesh, views = load_job(manifest)
        completion = job.get("completion", {})
        use_neural = completion.get("mode", "harmonic") == "diffusers"
        if completion.get("mode", "harmonic") not in ("harmonic", "diffusers"):
            raise ValueError("unsupported completion mode")
        if use_neural and self.inpainter is None:
            raise ValueError("diffusers mode requires an explicitly supplied local model provider")
        fingerprint = self.inpainter.fingerprint() if use_neural else "none"
        key = asset_key(job, mesh, views, fingerprint)

        def build(directory: Path) -> None:
            atlas = unwrap(mesh, int(job.get("atlas_size", 256)), int(job.get("padding", 4)))
            baked = bake_views(atlas, views, float(job.get("depth_tolerance", .002)))
            before = baked.linear.copy()
            groups = np.asarray(job["material_groups"], dtype=np.int32) if "material_groups" in job else None
            complete_harmonic(baked, material_groups=groups)
            if use_neural:
                cameras = [Camera(v["matrix"], int(v["width"]), int(v["height"])) for v in completion["cameras"]]
                complete_novel_views(baked, cameras, self.inpainter, str(completion["prompt"]), int(completion.get("seed", 0)))
                complete_harmonic(baked, material_groups=groups)
            if not np.array_equal(before[baked.observed], baked.linear[baked.observed]):
                raise RuntimeError("completion changed source-observed texels")
            image = Image.fromarray(baked.rgba())
            roughness, metallic = float(job.get("roughness", .8)), float(job.get("metallic", 0))
            if not 0 <= roughness <= 1 or not 0 <= metallic <= 1:
                raise ValueError("PBR scalar channels must be in [0, 1]")
            material = trimesh.visual.material.PBRMaterial(name=job["prototype"], baseColorTexture=image, metallicFactor=metallic, roughnessFactor=roughness, doubleSided=False)
            visual = trimesh.visual.texture.TextureVisuals(uv=atlas.uv, material=material)
            value = trimesh.Trimesh(vertices=atlas.mesh.vertices, faces=atlas.mesh.faces, visual=visual, process=False)
            value.metadata.update({"prototype": job["prototype"], "canonical_key": key, "source_revision": job["source_revision"]})
            (directory / "asset.glb").write_bytes(value.export(file_type="glb"))
            image.save(directory / "basecolor.png")
            Image.fromarray(baked.observed.astype(np.uint8) * 255).save(directory / "observed.png")
            Image.fromarray(baked.generated.astype(np.uint8) * 255).save(directory / "generated.png")
            Image.fromarray((np.clip(baked.disagreement * 4, 0, 1) * 255).astype(np.uint8)).save(directory / "disagreement.png")
            np.savez_compressed(directory / "surface.npz", vertices=atlas.mesh.vertices, faces=atlas.mesh.faces, uv=atlas.uv, color=baked.linear, valid=atlas.valid, observed=baked.observed, generated=baked.generated, confidence=baked.confidence)
            count = int(atlas.valid.sum())
            report = {"schema_version": 1, "key": key, "prototype": job["prototype"], "source_revision": job["source_revision"], "triangles": len(atlas.mesh.faces), "atlas_size": atlas.size, "surface_texels": count, "observed_texels": int(baked.observed.sum()), "generated_texels": int(baked.generated.sum()), "prior_only_texels": int((atlas.valid & ~baked.observed & ~baked.generated).sum()), "observed_fraction": float(baked.observed.sum() / count), "max_source_disagreement_linear": float(baked.disagreement.max()), "completion": completion.get("mode", "harmonic"), "source_is_unlit": bool(job.get("source_is_unlit", False)), "reflectance_recovered": False, "constraints": {"bounds": mesh.bounds.tolist(), "observations_unchanged": True}, "runtime_game_tested": False}
            (directory / "report.json").write_bytes(canonical_json(report))
        path, cached = self.store.materialize(key, build)
        report = read_json(path / "report.json")
        LOG.info("canonical asset %s %s", key, "reused" if cached else "compiled")
        return Result(key, path, cached, report)

    async def compile_many(self, manifests: list[Path], concurrency: int = 2) -> list[Result]:
        if not 1 <= concurrency <= 16:
            raise ValueError("compiler concurrency must be in [1,16]")
        semaphore = asyncio.Semaphore(concurrency)
        async def run(path: Path) -> Result:
            async with semaphore:
                return await asyncio.to_thread(self.compile, path)
        return await asyncio.gather(*(run(path) for path in manifests))
