from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
import pandas as pd
from onnxruntime.quantization import (
    CalibrationDataReader,
    CalibrationMethod,
    QuantFormat,
    QuantType,
    quantize_dynamic,
    quantize_static,
)
from transformers import AutoTokenizer

import sys


ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT / "src"))
from benchmark import CATEGORY_DESCRIPTIONS, _model_inputs


class RepresentativeCalibrationReader(CalibrationDataReader):
    def __init__(self, tokenizer, batch_size: int = 8, max_length: int = 64) -> None:
        data = pd.read_csv(ML_ROOT / "data" / "categorization_benchmark_v2.csv")
        groups = [
            _model_inputs(
                "intfloat/multilingual-e5-small",
                data.text.astype(str).tolist(),
                categories=False,
            ),
            _model_inputs(
                "intfloat/multilingual-e5-small",
                list(CATEGORY_DESCRIPTIONS.values()),
                categories=True,
            ),
        ]
        self.samples: list[dict[str, np.ndarray]] = []
        for texts in groups:
            for start in range(0, len(texts), batch_size):
                encoded = tokenizer(
                    texts[start : start + batch_size],
                    padding=True,
                    truncation=True,
                    max_length=max_length,
                    return_tensors="np",
                )
                self.samples.append({
                    "input_ids": np.asarray(encoded["input_ids"], dtype=np.int64),
                    "attention_mask": np.asarray(encoded["attention_mask"], dtype=np.int64),
                })
        self.iterator = iter(self.samples)

    def get_next(self) -> dict[str, np.ndarray] | None:
        return next(self.iterator, None)

    def rewind(self) -> None:
        self.iterator = iter(self.samples)


def validate_runtime(path: Path, tokenizer) -> None:
    session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    encoded = tokenizer(
        ["query: Panadol Extra", "query: بنادول إكسترا"],
        padding=True,
        return_tensors="np",
    )
    output = session.run(None, {
        "input_ids": np.asarray(encoded["input_ids"], dtype=np.int64),
        "attention_mask": np.asarray(encoded["attention_mask"], dtype=np.int64),
    })[0]
    if output.shape != (2, 384) or not np.isfinite(output).all():
        raise RuntimeError(f"Invalid output from {path}: {output.shape}")


def main() -> None:
    source = ML_ROOT / "onnx" / "artifacts" / "fp32" / "multilingual-e5-small.onnx"
    existing_dynamic = (
        ML_ROOT / "onnx" / "artifacts" / "int8" / "multilingual-e5-small-int8.onnx"
    )
    tokenizer_path = ML_ROOT / "onnx" / "artifacts" / "tokenizer"
    output = ML_ROOT / "mobile" / "artifacts" / "e5_quant"
    output.mkdir(parents=True, exist_ok=True)
    tokenizer = AutoTokenizer.from_pretrained(tokenizer_path, local_files_only=True)

    variants: list[dict[str, Any]] = [{
        "name": "E5_DYNAMIC_INT8_PER_TENSOR",
        "path": str(existing_dynamic.resolve()),
        "quantization": "dynamic",
        "per_channel": False,
        "activation_type": "runtime dynamic",
        "weight_type": "QInt8",
        "op_scope": "ONNX Runtime defaults",
        "status": "valid",
        "error": None,
    }]

    configurations = [
        {
            "name": "E5_DYNAMIC_INT8_PER_CHANNEL",
            "kind": "dynamic",
            "per_channel": True,
            "op_types": None,
        },
        {
            "name": "E5_DYNAMIC_INT8_MATMUL_PER_CHANNEL",
            "kind": "dynamic",
            "per_channel": True,
            "op_types": ["MatMul"],
        },
        {
            "name": "E5_STATIC_QDQ_U8S8_PER_TENSOR",
            "kind": "static",
            "format": QuantFormat.QDQ,
            "per_channel": False,
            "op_types": None,
            "activation": QuantType.QUInt8,
        },
        {
            "name": "E5_STATIC_QDQ_U8S8_PER_CHANNEL",
            "kind": "static",
            "format": QuantFormat.QDQ,
            "per_channel": True,
            "op_types": None,
            "activation": QuantType.QUInt8,
        },
        {
            "name": "E5_STATIC_QDQ_MATMUL_U8S8_PER_CHANNEL",
            "kind": "static",
            "format": QuantFormat.QDQ,
            "per_channel": True,
            "op_types": ["MatMul"],
            "activation": QuantType.QUInt8,
        },
        {
            "name": "E5_STATIC_QOP_U8S8_PER_CHANNEL",
            "kind": "static",
            "format": QuantFormat.QOperator,
            "per_channel": True,
            "op_types": None,
            "activation": QuantType.QUInt8,
        },
        {
            "name": "E5_STATIC_QDQ_S8S8_PER_TENSOR",
            "kind": "static",
            "format": QuantFormat.QDQ,
            "per_channel": False,
            "op_types": None,
            "activation": QuantType.QInt8,
        },
        {
            "name": "E5_STATIC_QDQ_S8S8_PER_CHANNEL",
            "kind": "static",
            "format": QuantFormat.QDQ,
            "per_channel": True,
            "op_types": None,
            "activation": QuantType.QInt8,
        },
        {
            "name": "E5_STATIC_QDQ_MATMUL_S8S8_PER_CHANNEL",
            "kind": "static",
            "format": QuantFormat.QDQ,
            "per_channel": True,
            "op_types": ["MatMul"],
            "activation": QuantType.QInt8,
        },
    ]

    for configuration in configurations:
        path = output / f"{configuration['name'].lower()}.onnx"
        record = {
            "name": configuration["name"],
            "path": str(path.resolve()),
            "quantization": configuration["kind"],
            "per_channel": configuration["per_channel"],
            "activation_type": (
                configuration.get("activation", "runtime dynamic").name
                if configuration["kind"] == "static"
                else "runtime dynamic"
            ),
            "weight_type": "QInt8",
            "op_scope": configuration.get("op_types") or "ONNX Runtime defaults",
            "status": "invalid",
            "error": None,
        }
        try:
            if not path.exists():
                if configuration["kind"] == "dynamic":
                    quantize_dynamic(
                        source,
                        path,
                        op_types_to_quantize=configuration.get("op_types"),
                        per_channel=configuration["per_channel"],
                        weight_type=QuantType.QInt8,
                    )
                else:
                    reader = RepresentativeCalibrationReader(tokenizer)
                    quantize_static(
                        source,
                        path,
                        reader,
                        quant_format=configuration["format"],
                        op_types_to_quantize=configuration.get("op_types"),
                        per_channel=configuration["per_channel"],
                        activation_type=configuration["activation"],
                        weight_type=QuantType.QInt8,
                        calibrate_method=CalibrationMethod.MinMax,
                        extra_options={
                            "ActivationSymmetric": False,
                            "WeightSymmetric": True,
                        },
                    )
            validate_runtime(path, tokenizer)
            record["status"] = "valid"
        except Exception as error:
            record["error"] = repr(error)
            if path.exists():
                path.unlink()
        variants.append(record)
        print(record["name"], record["status"], record["error"] or "")

    manifest = ML_ROOT / "mobile" / "quantization_variants.json"
    manifest.write_text(json.dumps(variants, indent=2), encoding="utf-8")
    print(f"Wrote {manifest}")


if __name__ == "__main__":
    main()
