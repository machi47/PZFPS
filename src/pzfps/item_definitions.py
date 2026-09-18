from __future__ import annotations

from collections import Counter
import json
from pathlib import Path
import re
from typing import Any

from .assets import tokenize
from .common import now_utc, sha256_file, write_json
from .model_assets import ModelScriptParseError, ModelScriptParser, _walk


ITEM_DEFINITION = re.compile(r"(?m)^\s*item\s+[A-Za-z0-9_]+\s*$")


def compile_item_definitions(
    scripts_root: Path,
    model_index_path: Path,
    output: Path,
    *,
    game_version: str,
) -> dict[str, Any]:
    if not scripts_root.is_dir():
        raise FileNotFoundError(f"PZ scripts directory does not exist: {scripts_root}")
    if not model_index_path.is_file():
        raise FileNotFoundError(f"PZ model index does not exist: {model_index_path}")

    model_index = json.loads(model_index_path.read_text(encoding="utf-8"))
    if str(model_index.get("game_version", "")) != game_version:
        raise ValueError("item definitions and model index describe different game versions")
    models = model_index.get("models", {})
    aliases = model_index.get("aliases", {})

    items: dict[str, dict[str, Any]] = {}
    sources: list[dict[str, Any]] = []
    definition_count = 0
    script_paths: list[Path] = []
    for path in sorted(scripts_root.rglob("*.txt")):
        text = path.read_text(encoding="utf-8", errors="replace")
        if ITEM_DEFINITION.search(text):
            script_paths.append(path)

    for path in script_paths:
        text = path.read_text(encoding="utf-8", errors="replace")
        try:
            documents = ModelScriptParser(tokenize(text)).parse()
        except ValueError as error:
            raise ModelScriptParseError(f"{path}: {error}") from error
        sources.append({"path": str(path), "sha256": sha256_file(path)})
        for document in documents:
            module_name = document.identifier if document.kind == "module" else ""
            for block in _walk(document):
                if block.kind != "item" or not block.identifier:
                    continue
                definition_count += 1
                identity = f"{module_name}.{block.identifier}" if module_name else block.identifier
                world_model = _world_model(block.values)
                resolved_model = _resolve_model(world_model, models, aliases)
                item_type = block.values.get("ItemType", block.values.get("Type", "unknown"))
                selected = {
                    "name": block.identifier,
                    "module": module_name,
                    "source": str(path),
                    "item_type": item_type,
                    "icon": block.values.get("Icon", ""),
                    "world_model": world_model,
                    "resolved_model_identity": resolved_model,
                    "world_model_resolved": bool(resolved_model),
                    "properties": dict(sorted(block.values.items())),
                }
                prior = items.get(identity)
                definitions = list(prior.get("definitions", [])) if prior else []
                definitions.append(selected)
                items[identity] = selected | {
                    "definition_count": len(definitions),
                    "definitions": definitions,
                    "selection_rule": "last definition in sorted installed source order",
                }

    item_types = Counter(str(item["item_type"]) for item in items.values())
    with_model = sum(bool(item["world_model"]) for item in items.values())
    resolved = sum(bool(item["resolved_model_identity"]) for item in items.values())
    document = {
        "schema_version": 1,
        "generated_at": now_utc(),
        "game_version": game_version,
        "scripts_root": str(scripts_root),
        "model_index": str(model_index_path),
        "source_count": len(sources),
        "sources": sources,
        "definition_count": definition_count,
        "item_count": len(items),
        "duplicate_identity_count": sum(
            int(item["definition_count"]) > 1 for item in items.values()),
        "items_with_world_model": with_model,
        "world_models_resolved": resolved,
        "world_models_unresolved": with_model - resolved,
        "item_types": dict(sorted(item_types.items())),
        "items": items,
    }
    write_json(output, document)
    return document


def _world_model(values: dict[str, str]) -> str:
    return values.get("WorldStaticModel", values.get("StaticModel", ""))


def _resolve_model(
    identity: str,
    models: dict[str, Any],
    aliases: dict[str, str],
) -> str:
    if not identity:
        return ""
    if identity in models:
        return identity
    return str(aliases.get(identity, ""))
