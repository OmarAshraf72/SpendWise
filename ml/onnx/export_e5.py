from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from typing import Any

import onnx
import onnxruntime as ort
import torch
import torch.nn.functional as functional
from onnxruntime.quantization import QuantType, quantize_dynamic
from sentence_transformers import SentenceTransformer


MODEL_NAME = "intfloat/multilingual-e5-small"
MODEL_REVISION = "614241f622f53c4eeff9890bdc4f31cfecc418b3"
ONNX_OPSET = 17


class E5SentenceEmbedding(torch.nn.Module):
    """Exportable E5 transformer with SentenceTransformer-equivalent pooling."""

    def __init__(self, transformer: torch.nn.Module) -> None:
        super().__init__()
        self.transformer = transformer

    def forward(self, input_ids: torch.Tensor, attention_mask: torch.Tensor) -> torch.Tensor:
        token_embeddings = self.transformer(
            input_ids=input_ids,
            attention_mask=attention_mask,
            return_dict=False,
        )[0]
        expanded_mask = attention_mask.unsqueeze(-1).to(token_embeddings.dtype)
        pooled = (token_embeddings * expanded_mask).sum(dim=1)
        pooled = pooled / expanded_mask.sum(dim=1).clamp(min=1e-9)
        return functional.normalize(pooled, p=2, dim=1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def file_record(path: Path, root: Path) -> dict[str, Any]:
    return {
        "path": path.relative_to(root).as_posix(),
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def export_models(output_root: Path, *, force: bool = False) -> dict[str, Any]:
    artifacts = output_root / "artifacts"
    fp32_directory = artifacts / "fp32"
    int8_directory = artifacts / "int8"
    tokenizer_directory = artifacts / "tokenizer"
    for directory in (fp32_directory, int8_directory, tokenizer_directory):
        directory.mkdir(parents=True, exist_ok=True)

    fp32_path = fp32_directory / "multilingual-e5-small.onnx"
    int8_path = int8_directory / "multilingual-e5-small-int8.onnx"

    sentence_model = SentenceTransformer(MODEL_NAME, device="cpu", local_files_only=True)
    sentence_model.eval()
    sentence_model.tokenizer.save_pretrained(tokenizer_directory)

    if force or not fp32_path.exists():
        wrapper = E5SentenceEmbedding(sentence_model[0].auto_model).eval()
        sample = sentence_model.tokenizer(
            ["query: Panadol Extra", "passage: medicines, أدوية"],
            padding=True,
            truncation=True,
            max_length=128,
            return_tensors="pt",
        )
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
                opset_version=ONNX_OPSET,
                do_constant_folding=True,
                dynamo=False,
            )
        onnx.checker.check_model(onnx.load(fp32_path))

    if force or not int8_path.exists():
        quantize_dynamic(
            model_input=fp32_path,
            model_output=int8_path,
            weight_type=QuantType.QInt8,
            per_channel=False,
            reduce_range=False,
        )
        onnx.checker.check_model(onnx.load(int8_path))

    fp32_session = ort.InferenceSession(str(fp32_path), providers=["CPUExecutionProvider"])
    manifest = {
        "schema_version": 1,
        "source_model": MODEL_NAME,
        "source_revision": MODEL_REVISION,
        "task": "normalized_sentence_embedding",
        "embedding_dimension": 384,
        "onnx_opset": ONNX_OPSET,
        "graph_contract": {
            "inputs": [
                {"name": value.name, "dtype": value.type, "shape": value.shape}
                for value in fp32_session.get_inputs()
            ],
            "outputs": [
                {"name": value.name, "dtype": value.type, "shape": value.shape}
                for value in fp32_session.get_outputs()
            ],
            "dynamic_batch": True,
            "dynamic_sequence_length": True,
        },
        "text_contract": {
            "query_prefix": "query: ",
            "category_and_reference_prefix": "passage: ",
            "prefixes_are_required": True,
            "model_max_sequence_length": 512,
            "padding": "right, to longest sequence in batch",
            "truncation": "right",
        },
        "tokenizer": {
            "class": type(sentence_model.tokenizer).__name__,
            "algorithm": "SentencePiece / XLM-RoBERTa",
            "model_max_length": sentence_model.tokenizer.model_max_length,
            "add_prefix_space": bool(sentence_model.tokenizer.init_kwargs.get("add_prefix_space", False)),
            "special_token_ids": {
                "bos": sentence_model.tokenizer.bos_token_id,
                "pad": sentence_model.tokenizer.pad_token_id,
                "eos": sentence_model.tokenizer.eos_token_id,
                "unk": sentence_model.tokenizer.unk_token_id,
                "mask": sentence_model.tokenizer.mask_token_id,
            },
        },
        "pooling": {
            "type": "attention_mask_aware_mean",
            "formula": "sum(token_embeddings * attention_mask) / clamp(sum(attention_mask), 1e-9)",
            "includes_prefix_tokens": True,
        },
        "normalization": {"type": "L2", "axis": 1, "included_in_onnx_graph": True},
        "quantization": {
            "fp32": "unquantized ONNX weights",
            "int8": "ONNX Runtime dynamic QInt8 weight quantization",
        },
        "artifacts": {
            "fp32_model": file_record(fp32_path, output_root),
            "int8_model": file_record(int8_path, output_root),
            "tokenizer_files": [
                file_record(path, output_root)
                for path in sorted(tokenizer_directory.iterdir())
                if path.is_file()
            ],
        },
    }
    manifest["artifacts"]["tokenizer_total_bytes"] = sum(
        value["bytes"] for value in manifest["artifacts"]["tokenizer_files"]
    )
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description="Export multilingual-e5-small for SpendWise.")
    parser.add_argument("--output-root", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--force", action="store_true")
    args = parser.parse_args()
    manifest = export_models(args.output_root, force=args.force)
    manifest_path = args.output_root / "deployment_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps({
        "manifest": str(manifest_path),
        "fp32_bytes": manifest["artifacts"]["fp32_model"]["bytes"],
        "int8_bytes": manifest["artifacts"]["int8_model"]["bytes"],
        "tokenizer_bytes": manifest["artifacts"]["tokenizer_total_bytes"],
    }, indent=2))


if __name__ == "__main__":
    main()
