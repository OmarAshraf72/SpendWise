from __future__ import annotations

from pathlib import Path
import sys
from typing import Protocol

import numpy as np
import onnxruntime as ort
from sentence_transformers import SentenceTransformer
from transformers import AutoTokenizer

ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT / "src"))

from benchmark import _l2_normalize, _model_inputs


MODEL_NAME = "intfloat/multilingual-e5-small"


class Encoder(Protocol):
    runtime_name: str

    def encode(
        self,
        texts: list[str],
        *,
        categories: bool,
        max_length: int = 512,
        batch_size: int = 32,
    ) -> np.ndarray: ...


class PyTorchEncoder:
    runtime_name = "PYTORCH"

    def __init__(self) -> None:
        self.model = SentenceTransformer(MODEL_NAME, device="cpu", local_files_only=True)

    @property
    def tokenizer(self):
        return self.model.tokenizer

    def encode(
        self,
        texts: list[str],
        *,
        categories: bool,
        max_length: int = 512,
        batch_size: int = 32,
    ) -> np.ndarray:
        self.model.max_seq_length = max_length
        values = self.model.encode(
            _model_inputs(MODEL_NAME, texts, categories=categories),
            batch_size=batch_size,
            convert_to_numpy=True,
            show_progress_bar=False,
        )
        return _l2_normalize(np.asarray(values, dtype=np.float32))


class OnnxEncoder:
    def __init__(self, model_path: Path, tokenizer_path: Path, runtime_name: str) -> None:
        self.runtime_name = runtime_name
        self.tokenizer = AutoTokenizer.from_pretrained(tokenizer_path, local_files_only=True)
        self.session = ort.InferenceSession(
            str(model_path),
            providers=["CPUExecutionProvider"],
        )

    def encode(
        self,
        texts: list[str],
        *,
        categories: bool,
        max_length: int = 512,
        batch_size: int = 32,
    ) -> np.ndarray:
        prefixed = _model_inputs(MODEL_NAME, texts, categories=categories)
        batches = []
        for start in range(0, len(prefixed), batch_size):
            encoded = self.tokenizer(
                prefixed[start : start + batch_size],
                padding=True,
                truncation=True,
                max_length=max_length,
                return_tensors="np",
            )
            inputs = {
                "input_ids": np.asarray(encoded["input_ids"], dtype=np.int64),
                "attention_mask": np.asarray(encoded["attention_mask"], dtype=np.int64),
            }
            batches.append(self.session.run(["sentence_embedding"], inputs)[0])
        return _l2_normalize(np.concatenate(batches).astype(np.float32, copy=False))


def token_lengths(tokenizer, texts: list[str], *, categories: bool) -> np.ndarray:
    prefixed = _model_inputs(MODEL_NAME, texts, categories=categories)
    encoded = tokenizer(prefixed, padding=False, truncation=False, add_special_tokens=True)
    return np.asarray([len(value) for value in encoded["input_ids"]], dtype=np.int32)
