"""Optional real, local-weight inpainting provider; no model download at runtime."""
from __future__ import annotations

import asyncio
from pathlib import Path
import threading

import numpy as np
from PIL import Image

from .store import digest


class DiffusersInpainter:
    """Serializes model access: diffusers pipelines are not assumed reentrant.

    MPS/CUDA/CPU selected from actual availability. A model directory is an
    explicit dependency; a missing one never disables geometry compilation.
    This provider was not model-tested by CPU-only compiler regression tests.
    """
    def __init__(self, model: Path, *, device: str = "auto", steps: int = 20, guidance: float = 5.0):
        if not model.is_dir() or not (model / "model_index.json").is_file():
            raise ValueError("expected a complete local diffusers inpainting model directory")
        if steps < 1 or guidance < 0:
            raise ValueError("invalid inference settings")
        self.model = model.resolve()
        self.device = device
        self.steps = steps
        self.guidance = guidance
        self._pipeline = None
        self._lock = threading.Lock()

    def fingerprint(self) -> str:
        import hashlib
        value = hashlib.sha256()
        for path in sorted(self.model.rglob("*")):
            if path.is_file():
                value.update(path.relative_to(self.model).as_posix().encode())
                with path.open("rb") as stream:
                    for chunk in iter(lambda: stream.read(4 * 1024 * 1024), b""):
                        value.update(chunk)
        value.update(f"{self.device}:{self.steps}:{self.guidance}".encode())
        return value.hexdigest()

    def __call__(self, rgb: np.ndarray, missing: np.ndarray, prompt: str, seed: int) -> np.ndarray:
        import torch
        from diffusers import AutoPipelineForInpainting
        if rgb.dtype != np.uint8 or rgb.ndim != 3 or rgb.shape[2] != 3 or missing.shape != rgb.shape[:2]:
            raise ValueError("invalid inpainting RGB or mask")
        with self._lock:
            if self._pipeline is None:
                device = self.device
                if device == "auto":
                    device = "mps" if torch.backends.mps.is_available() else ("cuda" if torch.cuda.is_available() else "cpu")
                dtype = torch.float32 if device == "cpu" else torch.float16
                self._pipeline = AutoPipelineForInpainting.from_pretrained(str(self.model), local_files_only=True, torch_dtype=dtype).to(device)
                self._pipeline.enable_attention_slicing()
            # The pipeline may require dimensions divisible by 8. Pad rather
            # than resize calibration; return the original pixel coordinates.
            height, width = rgb.shape[:2]
            padded = np.pad(rgb, ((0, -height % 8), (0, -width % 8), (0, 0)), mode="edge")
            mask = np.pad(missing, ((0, -height % 8), (0, -width % 8)), constant_values=False)
            generator = torch.Generator(device="cpu").manual_seed(seed)
            image = self._pipeline(prompt=prompt, image=Image.fromarray(padded), mask_image=Image.fromarray(mask.astype(np.uint8) * 255), width=padded.shape[1], height=padded.shape[0], num_inference_steps=self.steps, guidance_scale=self.guidance, generator=generator).images[0].convert("RGB")
            output = np.asarray(image, dtype=np.uint8)[:height, :width]
            if output.shape != rgb.shape:
                raise RuntimeError("model did not preserve requested dimensions")
            return np.where(missing[..., None], output, rgb)

    async def run(self, rgb: np.ndarray, missing: np.ndarray, prompt: str, seed: int) -> np.ndarray:
        return await asyncio.to_thread(self, rgb.copy(), missing.copy(), prompt, seed)
