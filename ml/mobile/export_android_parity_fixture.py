from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

import numpy as np


ML_ROOT = Path(__file__).resolve().parents[1]
PROJECT_ROOT = ML_ROOT.parent
sys.path.insert(0, str(ML_ROOT / "src"))
sys.path.insert(0, str(ML_ROOT / "mobile"))

from benchmark import CATEGORY_DESCRIPTIONS
from benchmark_mobile_candidates import GenericOnnxEncoder


MODEL_NAME = "alphaedge-ai/multilingual-e5-small-arb-32768"
MODEL_ROOT = ML_ROOT / "mobile" / "artifacts" / "compact_models" / "arabic_trimmed_e5"
MODEL_PATH = MODEL_ROOT / "model-int8-per-channel.onnx"
TOKENIZER_ROOT = MODEL_ROOT / "tokenizer"
OUTPUT_PATH = (
    PROJECT_ROOT
    / "app"
    / "src"
    / "androidTest"
    / "assets"
    / "ml"
    / "category"
    / "python_semantic_parity.json"
)
PARITY_TEXTS = [
    "Panadol Extra",
    "Folic Acid 600 MCG",
    "IMMULANT PLUS 20 CAP",
    "Dettol Floor Cleaner",
    "Chicken Breast",
    "طماطم",
    "شامبو دوف",
    "Atomic Habits",
    "Clean Code",
]


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    encoder = GenericOnnxEncoder(
        MODEL_PATH,
        TOKENIZER_ROOT,
        query_prefix="query: ",
        passage_prefix="passage: ",
    )
    category_names = list(CATEGORY_DESCRIPTIONS)
    category_descriptions = list(CATEGORY_DESCRIPTIONS.values())
    # Dynamic activation quantization can make an embedding depend slightly on
    # the other rows in its batch. Production therefore defines parity in terms
    # of one ONNX invocation per semantic text.
    category_embeddings = np.vstack(
        [encoder.encode([description], categories=True)[0] for description in category_descriptions]
    )
    query_embeddings = np.vstack(
        [encoder.encode([text], categories=False)[0] for text in PARITY_TEXTS]
    )

    cases = []
    for text, query_embedding in zip(PARITY_TEXTS, query_embeddings, strict=True):
        scores = query_embedding @ category_embeddings.T
        order = np.argsort(-scores)
        cases.append(
            {
                "text": text,
                "expected_category": category_names[order[0]],
                "expected_top1_score": float(scores[order[0]]),
                "expected_top2_category": category_names[order[1]],
                "expected_top2_score": float(scores[order[1]]),
                "expected_gap": float(scores[order[0]] - scores[order[1]]),
                "query_embedding": query_embedding.astype(float).tolist(),
            }
        )

    fixture = {
        "model": MODEL_NAME,
        "model_sha256": sha256(MODEL_PATH),
        "tokenizer_sha256": sha256(TOKENIZER_ROOT / "tokenizer.json"),
        "quantization": "dynamic QInt8 per-channel",
        "query_prefix": "query: ",
        "passage_prefix": "passage: ",
        "max_sequence_length": 128,
        "prototype_strategy": "DESCRIPTION_ONLY_NO_CONFIRMED_EXAMPLES_SINGLE_ITEM",
        "inference_contract": "SINGLE_ITEM",
        "category_names": category_names,
        "category_descriptions": category_descriptions,
        "category_embeddings": category_embeddings.astype(float).tolist(),
        "cases": cases,
    }
    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(json.dumps(fixture, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Wrote {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
