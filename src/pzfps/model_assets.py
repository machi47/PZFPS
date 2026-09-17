from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from .assets import tokenize
from .common import now_utc, sha256_file, write_json


class ModelScriptParseError(ValueError):
    pass


@dataclass
class ScriptBlock:
    kind: str
    identifier: str = ""
    values: dict[str, str] = field(default_factory=dict)
    children: list["ScriptBlock"] = field(default_factory=list)


class ModelScriptParser:
    """Parser for the named blocks used by PZ module/model scripts."""

    def __init__(self, tokens: list[str]) -> None:
        self.tokens = tokens
        self.index = 0

    def parse(self) -> list[ScriptBlock]:
        result: list[ScriptBlock] = []
        while self.index < len(self.tokens):
            result.append(self._block())
        return result

    def _block(self) -> ScriptBlock:
        kind = self._atom()
        header: list[str] = []
        while self._peek() != "{":
            header.append(self._atom())
        self._expect("{")
        block = ScriptBlock(kind, " ".join(header))
        while self._peek() != "}":
            key = self._atom()
            child_header: list[str] = []
            while self._peek() not in {"{", "="}:
                child_header.append(self._atom())
            if self._peek() == "{":
                self._expect("{")
                child = self._block_body(key, " ".join(child_header))
                block.children.append(child)
                continue
            if child_header:
                raise ModelScriptParseError(
                    f"unexpected header before value {key!r}: {child_header!r}"
                )
            self._expect("=")
            value: list[str] = []
            while self._peek() != ",":
                if self._peek() in {"{", "}", "="}:
                    raise ModelScriptParseError(
                        f"unexpected {self._peek()!r} in value for {key!r}"
                    )
                value.append(self._take())
            self._expect(",")
            block.values[key] = " ".join(value)
        self._expect("}")
        return block

    def _block_body(self, kind: str, identifier: str) -> ScriptBlock:
        block = ScriptBlock(kind, identifier)
        while self._peek() != "}":
            key = self._atom()
            child_header: list[str] = []
            while self._peek() not in {"{", "="}:
                child_header.append(self._atom())
            if self._peek() == "{":
                self._expect("{")
                block.children.append(self._block_body(key, " ".join(child_header)))
                continue
            if child_header:
                raise ModelScriptParseError(
                    f"unexpected header before value {key!r}: {child_header!r}"
                )
            self._expect("=")
            value: list[str] = []
            while self._peek() != ",":
                if self._peek() in {"{", "}", "="}:
                    raise ModelScriptParseError(
                        f"unexpected {self._peek()!r} in value for {key!r}"
                    )
                value.append(self._take())
            self._expect(",")
            block.values[key] = " ".join(value)
        self._expect("}")
        return block

    def _atom(self) -> str:
        value = self._take()
        if value in {"{", "}", "=", ","}:
            raise ModelScriptParseError(f"expected identifier, found {value!r}")
        return value

    def _take(self) -> str:
        if self.index >= len(self.tokens):
            raise ModelScriptParseError("unexpected end of input")
        result = self.tokens[self.index]
        self.index += 1
        return result

    def _peek(self) -> str:
        if self.index >= len(self.tokens):
            raise ModelScriptParseError("unexpected end of input")
        return self.tokens[self.index]

    def _expect(self, expected: str) -> None:
        found = self._take()
        if found != expected:
            raise ModelScriptParseError(f"expected {expected!r}, found {found!r}")


def compile_model_index(
    scripts_root: Path,
    models_root: Path,
    textures_root: Path,
    output: Path,
    *,
    game_version: str,
) -> dict[str, Any]:
    for label, root in (
        ("scripts", scripts_root),
        ("models", models_root),
        ("textures", textures_root),
    ):
        if not root.is_dir():
            raise FileNotFoundError(f"PZ {label} directory does not exist: {root}")

    model_files = _asset_files(models_root, {".fbx", ".x"})
    texture_files = _asset_files(textures_root, {".png"})
    models: dict[str, dict[str, Any]] = {}
    sources: list[dict[str, Any]] = []

    script_paths = sorted(
        path
        for path in scripts_root.rglob("*.txt")
        if re.search(r"(?m)^\s*model\s+", path.read_text(encoding="utf-8", errors="replace"))
    )
    for path in script_paths:
        text = path.read_text(encoding="utf-8")
        try:
            documents = ModelScriptParser(tokenize(text)).parse()
        except ValueError as error:
            raise ModelScriptParseError(f"{path}: {error}") from error
        sources.append(
            {
                "path": str(path),
                "sha256": sha256_file(path),
            }
        )
        for document in documents:
            module_name = document.identifier if document.kind == "module" else ""
            for block in _walk(document):
                if block.kind != "model" or not block.identifier:
                    continue
                qualified = (
                    f"{module_name}.{block.identifier}" if module_name else block.identifier
                )
                mesh = _unquote(block.values.get("mesh", ""))
                texture = _unquote(block.values.get("texture", ""))
                mesh_path = _resolve_asset(model_files, mesh)
                explicit_texture_path = _resolve_asset(texture_files, texture)
                inferred_texture_path = (
                    None
                    if explicit_texture_path or not mesh
                    else _resolve_asset(texture_files, mesh)
                )
                world_attachment = next(
                    (
                        child
                        for child in block.children
                        if child.kind == "attachment" and child.identifier == "world"
                    ),
                    None,
                )
                models[qualified] = {
                    "name": block.identifier,
                    "module": module_name,
                    "source": str(path),
                    "mesh": mesh,
                    "mesh_path": str(mesh_path) if mesh_path else "",
                    "mesh_size": mesh_path.stat().st_size if mesh_path else 0,
                    "texture": texture,
                    "texture_path": str(explicit_texture_path or inferred_texture_path or ""),
                    "texture_inferred": bool(inferred_texture_path and not explicit_texture_path),
                    "scale": _float(block.values.get("scale", "1"), 1.0),
                    "world_attachment": _attachment(world_attachment),
                }

    aliases: dict[str, str] = {}
    collisions: set[str] = set()
    for qualified, model in models.items():
        name = str(model["name"])
        if name in aliases:
            collisions.add(name)
        else:
            aliases[name] = qualified
    for name in collisions:
        aliases.pop(name, None)

    resolved_meshes = sum(bool(model["mesh_path"]) for model in models.values())
    resolved_textures = sum(bool(model["texture_path"]) for model in models.values())
    document = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "scripts_root": str(scripts_root),
        "models_root": str(models_root),
        "textures_root": str(textures_root),
        "source_count": len(sources),
        "sources": sources,
        "mesh_file_count": len(model_files),
        "texture_file_count": len(texture_files),
        "model_count": len(models),
        "resolved_mesh_count": resolved_meshes,
        "unresolved_mesh_count": len(models) - resolved_meshes,
        "resolved_texture_count": resolved_textures,
        "unresolved_texture_count": len(models) - resolved_textures,
        "alias_count": len(aliases),
        "aliases": aliases,
        "models": models,
    }
    write_json(output, document)
    return document


def _walk(block: ScriptBlock):
    yield block
    for child in block.children:
        yield from _walk(child)


def _asset_files(root: Path, suffixes: set[str]) -> dict[str, Path]:
    result: dict[str, Path] = {}
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix.lower() not in suffixes:
            continue
        relative = path.relative_to(root).with_suffix("").as_posix().lower()
        result.setdefault(relative, path)
    return result


def _resolve_asset(index: dict[str, Path], identity: str) -> Path | None:
    if not identity:
        return None
    normalized = identity.replace("\\", "/").strip("/").lower()
    return index.get(normalized)


def _attachment(block: ScriptBlock | None) -> dict[str, Any]:
    if block is None:
        return {"present": False, "offset": [0.0, 0.0, 0.0], "rotate": [0.0, 0.0, 0.0], "scale": 1.0}
    return {
        "present": True,
        "offset": _vector(block.values.get("offset", ""), [0.0, 0.0, 0.0]),
        "rotate": _vector(block.values.get("rotate", ""), [0.0, 0.0, 0.0]),
        "scale": _float(block.values.get("scale", "1"), 1.0),
    }


def _vector(value: str, fallback: list[float]) -> list[float]:
    if not value:
        return list(fallback)
    try:
        result = [float(part) for part in value.split()]
    except ValueError:
        return list(fallback)
    return result if len(result) == len(fallback) else list(fallback)


def _float(value: str, fallback: float) -> float:
    try:
        return float(value)
    except ValueError:
        return fallback


def _unquote(value: str) -> str:
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'"', "'"}:
        return value[1:-1]
    return value
