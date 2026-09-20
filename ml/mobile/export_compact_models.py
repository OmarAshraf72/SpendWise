from __future__ import annotations

import json
from pathlib import Path

import onnx
import onnxruntime as ort
import torch
import torch.nn.functional as functional
from onnxruntime.quantization import QuantType, quantize_dynamic
from sentence_transformers import SentenceTransformer


ML_ROOT = Path(__file__).resolve().parents[1]
MODELS = [
    {
        "key": "ARABIC_TRIMMED_E5",
        "model_name": "alphaedge-ai/multilingual-e5-small-arb-32768",
        "query_prefix": "query: ",
        "passage_prefix": "passage: ",
    },
    {
        "key": "FIHRIS_MINILM",
        "model_name": "Ik45/fihris-embeddding-id-ar",
        "query_prefix": "",
        "passage_prefix": "",
    },
]


class MeanPooledEmbedding(torch.nn.Module):
    def __init__(self, transformer: torch.nn.Module) -> None:
        super().__init__()
        self.transformer = transformer

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        token_embeddings = self.transformer(
            input_ids=input_ids,
            attention_mask=attention_mask,
            return_dict=False,
        )[0]
        mask = attention_mask.unsqueeze(-1).to(token_embeddings.dtype)
        pooled = (token_embeddings * mask).sum(dim=1) / mask.sum(dim=1).clamp(min=1e-9)
        return functional.normalize(pooled, p=2, dim=1)


def export_model(configuration: dict[str, str], root: Path) -> dict[str, object]:
    directory = root / configuration["key"].lower()
    tokenizer_directory = directory / "tokenizer"
    directory.mkdir(parents=True, exist_ok=True)
    tokenizer_directory.mkdir(parents=True, exist_ok=True)
    fp32_path = directory / "model-fp32.onnx"
    int8_path = directory / "model-int8-per-channel.onnx"

    model = SentenceTransformer(
        configuration["model_name"], device="cpu", local_files_only=True
    ).eval()
    model.tokenizer.save_pretrained(tokenizer_directory)
    wrapper = MeanPooledEmbedding(model[0].auto_model).eval()
    query = configuration["query_prefix"] + "Panadol Extra"
    passage = configuration["passage_prefix"] + "medicines, أدوية"
    sample = model.tokenizer(
        [query, passage], padding=True, truncation=True, max_length=64, return_tensors="pt"
    )
    if not fp32_path.exists():
        with torch.inference_mode():
            torch.onnx.export(
                wrapper,
                (sample["input_ids"], sample["attention_mask"]),
                fp32_path,
                input_names=["input_ids", "attention_mask"],
                output_names=["sentence_embedding"],
                dynamic_axes={
                    "input_ids": {0: "batch", 1: "sequence"},
                    "attention_mask": {0: "batch", 1: "sequence"},
                    "sentence_embedding": {0: "batch"},
                },
                opset_version=17,
                do_constant_folding=True,
                dynamo=False,
            )
    onnx.checker.check_model(onnx.load(fp32_path))
    if not int8_path.exists():
        quantize_dynamic(
            fp32_path,
            int8_path,
            per_channel=True,
            weight_type=QuantType.QInt8,
        )
    onnx.checker.check_model(onnx.load(int8_path))
    session = ort.InferenceSession(str(int8_path), providers=["CPUExecutionProvider"])
    session.run(None, {
        "input_ids": sample["input_ids"].numpy(),
        "attention_mask": sample["attention_mask"].numpy(),
    })

    tokenizer_files = [path for path in tokenizer_directory.iterdir() if path.is_file()]
    return {
        **configuration,
        "embedding_dimension": int(model.get_sentence_embedding_dimension()),
        "source_max_sequence_length": int(model.max_seq_length),
        "pooling": "attention_mask_aware_mean",
        "normalization": "L2 in graph",
        "fp32_path": str(fp32_path.resolve()),
        "int8_path": str(int8_path.resolve()),
        "fp32_bytes": fp32_path.stat().st_size,
        "int8_bytes": int8_path.stat().st_size,
        "tokenizer_path": str(tokenizer_directory.resolve()),
        "tokenizer_bytes": sum(path.stat().st_size for path in tokenizer_files),
        "tokenizer_files": [path.name for path in sorted(tokenizer_files)],
    }


def main() -> None:
    output = ML_ROOT / "mobile" / "artifacts" / "compact_models"
    output.mkdir(parents=True, exist_ok=True)
    records = []
    for configuration in MODELS:
        print(f"Exporting {configuration['model_name']}...")
        records.append(export_model(configuration, output))
    manifest = ML_ROOT / "mobile" / "compact_models.json"
    manifest.write_text(json.dumps(records, indent=2), encoding="utf-8")
    print(json.dumps(records, indent=2))


if __name__ == "__main__":
    main()
