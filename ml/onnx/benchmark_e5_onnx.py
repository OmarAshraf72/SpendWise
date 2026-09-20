from __future__ import annotations

import argparse
import gc
import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from transformers import AutoTokenizer

ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT / "src"))

from benchmark import CATEGORY_DESCRIPTIONS
from benchmark_v2 import (
    _books_simulation,
    _canonical_reference_vectors,
    _category_vectors_for_item,
    _prediction_row,
    threshold_analysis,
    validate_v2_dataset,
)
from e5_runtime import Encoder, OnnxEncoder, PyTorchEncoder, token_lengths
from export_e5 import export_models


RUNTIMES = ("PYTORCH", "ONNX_FP32", "ONNX_INT8")
PARITY_CASES = [
    ("english_item", "Panadol Extra", False),
    ("arabic_item", "بنادول إكسترا", False),
    ("mixed_item", "شامبو Dove 400ml", False),
    ("ocr_noise", "PANAD0L EX7RA", False),
    ("ocr_noise_arabic", "حليب جهينه 1L", False),
    ("english_category", CATEGORY_DESCRIPTIONS["Medicine"], True),
    ("arabic_category", CATEGORY_DESCRIPTIONS["Household"], True),
    ("books_category", CATEGORY_DESCRIPTIONS["Books"], True),
]


def create_encoder(runtime: str, artifacts: Path) -> Encoder:
    if runtime == "PYTORCH":
        return PyTorchEncoder()
    if runtime == "ONNX_FP32":
        model_path = artifacts / "fp32" / "multilingual-e5-small.onnx"
    elif runtime == "ONNX_INT8":
        model_path = artifacts / "int8" / "multilingual-e5-small-int8.onnx"
    else:
        raise ValueError(runtime)
    return OnnxEncoder(model_path, artifacts / "tokenizer", runtime)


def embedding_bundle(
    encoder: Encoder,
    data: pd.DataFrame,
    *,
    max_length: int,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    descriptions = encoder.encode(
        list(CATEGORY_DESCRIPTIONS.values()),
        categories=True,
        max_length=max_length,
    )
    clean = data[data.variant_type == "clean"].reset_index(drop=True)
    clean_embeddings = encoder.encode(
        clean.text.astype(str).tolist(),
        categories=True,
        max_length=max_length,
    )
    item_embeddings = encoder.encode(
        data.text.astype(str).tolist(),
        categories=False,
        max_length=max_length,
    )
    return descriptions, clean_embeddings, item_embeddings


def hybrid_predictions(
    runtime: str,
    data: pd.DataFrame,
    descriptions: np.ndarray,
    clean_embeddings: np.ndarray,
    item_embeddings: np.ndarray,
) -> pd.DataFrame:
    categories = list(CATEGORY_DESCRIPTIONS)
    clean = data[data.variant_type == "clean"].reset_index(drop=True)
    references = _canonical_reference_vectors(clean, clean_embeddings)
    rows = []
    for item_index, example in data.iterrows():
        prototypes = _category_vectors_for_item(
            categories,
            descriptions,
            references,
            example.canonical_id,
            "HYBRID_PROTOTYPE",
        )
        rows.append(
            _prediction_row(
                example,
                runtime,
                "HYBRID_PROTOTYPE",
                categories,
                item_embeddings[item_index] @ prototypes.T,
            )
        )
    return pd.DataFrame(rows)


def metric_row(runtime: str, predictions: pd.DataFrame) -> dict[str, Any]:
    row: dict[str, Any] = {
        "runtime": runtime,
        "strategy": "HYBRID_PROTOTYPE",
        "overall_top1_accuracy": predictions.top1_correct.mean(),
        "overall_top3_accuracy": predictions.top3_correct.mean(),
        "english_top1_accuracy": predictions.loc[predictions.language == "en", "top1_correct"].mean(),
        "arabic_top1_accuracy": predictions.loc[predictions.language == "ar", "top1_correct"].mean(),
        "mixed_top1_accuracy": predictions.loc[predictions.language == "mixed", "top1_correct"].mean(),
        "clean_top1_accuracy": predictions.loc[predictions.variant_type == "clean", "top1_correct"].mean(),
        "ocr_noise_top1_accuracy": predictions.loc[predictions.variant_type == "ocr_noise", "top1_correct"].mean(),
    }
    for category in ("Books", "Medicine", "Household", "Groceries", "Restaurants"):
        key = "category_top1__" + category.lower()
        row[key] = predictions.loc[predictions.category == category, "top1_correct"].mean()
    return row


def parity_embeddings(encoder: Encoder, max_length: int) -> np.ndarray:
    output = np.empty((len(PARITY_CASES), 384), dtype=np.float32)
    for categories in (False, True):
        indices = [index for index, (_, _, flag) in enumerate(PARITY_CASES) if flag == categories]
        values = encoder.encode(
            [PARITY_CASES[index][1] for index in indices],
            categories=categories,
            max_length=max_length,
        )
        output[indices] = values
    return output


def parity_rows(reference: np.ndarray, candidate: np.ndarray, runtime: str) -> list[dict[str, Any]]:
    rows = []
    for index, (case, text, categories) in enumerate(PARITY_CASES):
        difference = candidate[index] - reference[index]
        rows.append({
            "runtime": runtime,
            "case": case,
            "input_role": "passage" if categories else "query",
            "text": text,
            "cosine_similarity_to_pytorch": float(reference[index] @ candidate[index]),
            "mean_absolute_difference": float(np.abs(difference).mean()),
            "max_absolute_difference": float(np.abs(difference).max()),
        })
    return rows


def measure_performance(root: Path, repetitions: int = 3) -> pd.DataFrame:
    script = root / "onnx" / "measure_runtime.py"
    environment = os.environ.copy()
    environment["HF_HUB_OFFLINE"] = "1"
    environment["TRANSFORMERS_OFFLINE"] = "1"
    rows = []
    for runtime in RUNTIMES:
        measurements = []
        for _ in range(repetitions):
            completed = subprocess.run(
                [sys.executable, str(script), "--runtime", runtime, "--max-length", "128"],
                cwd=root.parent,
                env=environment,
                capture_output=True,
                text=True,
                check=True,
            )
            measurements.append(json.loads(completed.stdout.strip().splitlines()[-1]))
        row = {"runtime": runtime, "process_repetitions": repetitions}
        for key in (
            "creation_seconds",
            "warm_single_ms_per_item",
            "warm_single_stddev_ms",
            "warm_batch32_ms_per_item",
            "warm_batch32_stddev_ms",
            "model_session_memory_delta_mb",
            "approximate_peak_process_rss_mb",
        ):
            row[key] = float(np.mean([value[key] for value in measurements]))
        rows.append(row)
    return pd.DataFrame(rows)


def sequence_analysis(
    encoder: Encoder,
    tokenizer,
    data: pd.DataFrame,
    baseline_metrics: dict[str, Any],
) -> pd.DataFrame:
    clean = data[data.variant_type == "clean"].reset_index(drop=True)
    roles = [
        ("queries", data.text.astype(str).tolist(), False),
        ("clean_references", clean.text.astype(str).tolist(), True),
        ("category_descriptions", list(CATEGORY_DESCRIPTIONS.values()), True),
    ]
    all_lengths = np.concatenate(
        [token_lengths(tokenizer, texts, categories=categories) for _, texts, categories in roles]
    )
    rows = []
    for limit in (32, 64, 128):
        descriptions, clean_embeddings, item_embeddings = embedding_bundle(
            encoder, data, max_length=limit
        )
        predictions = hybrid_predictions(
            f"ONNX_INT8_MAX_{limit}", data, descriptions, clean_embeddings, item_embeddings
        )
        metrics = metric_row(f"ONNX_INT8_MAX_{limit}", predictions)
        truncated_by_role = {
            role: int((token_lengths(tokenizer, texts, categories=categories) > limit).sum())
            for role, texts, categories in roles
        }
        rows.append({
            "max_sequence_length": limit,
            "max_observed_tokens": int(all_lengths.max()),
            "p95_observed_tokens": float(np.percentile(all_lengths, 95)),
            "p99_observed_tokens": float(np.percentile(all_lengths, 99)),
            "truncated_total": sum(truncated_by_role.values()),
            **{f"truncated_{key}": value for key, value in truncated_by_role.items()},
            "overall_top1_accuracy": metrics["overall_top1_accuracy"],
            "overall_top3_accuracy": metrics["overall_top3_accuracy"],
            "ocr_noise_top1_accuracy": metrics["ocr_noise_top1_accuracy"],
            "top1_change_from_512": metrics["overall_top1_accuracy"] - baseline_metrics["overall_top1_accuracy"],
            "top3_change_from_512": metrics["overall_top3_accuracy"] - baseline_metrics["overall_top3_accuracy"],
            "ocr_change_from_512": metrics["ocr_noise_top1_accuracy"] - baseline_metrics["ocr_noise_top1_accuracy"],
        })
    return pd.DataFrame(rows)


def choose_max_length(sequence_results: pd.DataFrame) -> int:
    safe = sequence_results[
        (sequence_results.truncated_total == 0)
        & (sequence_results.top1_change_from_512 >= -0.005)
        & (sequence_results.top3_change_from_512 >= -0.005)
        & (sequence_results.ocr_change_from_512 >= -0.005)
    ]
    return int(safe.max_sequence_length.min()) if not safe.empty else 128


def main() -> None:
    root = ML_ROOT
    parser = argparse.ArgumentParser(description="Benchmark E5 PyTorch and ONNX deployment variants.")
    parser.add_argument("--cold-repetitions", type=int, default=3)
    args = parser.parse_args()
    onnx_root = root / "onnx"
    output = root / "data"
    artifacts = onnx_root / "artifacts"
    manifest = export_models(onnx_root)

    data = pd.read_csv(output / "categorization_benchmark_v2.csv").reset_index(drop=True)
    validate_v2_dataset(data)
    all_predictions = []
    metric_rows = []
    books_frames = []
    parity_frames = []
    pytorch_parity = None
    int8_encoder = None

    for runtime in RUNTIMES:
        print(f"Evaluating {runtime}...")
        encoder = create_encoder(runtime, artifacts)
        descriptions, clean_embeddings, item_embeddings = embedding_bundle(
            encoder, data, max_length=512
        )
        predictions = hybrid_predictions(
            runtime, data, descriptions, clean_embeddings, item_embeddings
        )
        all_predictions.append(predictions)
        metric_rows.append(metric_row(runtime, predictions))
        clean = data[data.variant_type == "clean"].reset_index(drop=True)
        references = _canonical_reference_vectors(clean, clean_embeddings)
        books_frames.append(pd.DataFrame(_books_simulation(
            runtime,
            data,
            item_embeddings,
            list(CATEGORY_DESCRIPTIONS),
            descriptions,
            references,
        )).rename(columns={"model": "runtime"}))
        parity = parity_embeddings(encoder, 512)
        if runtime == "PYTORCH":
            pytorch_parity = parity
        else:
            parity_frames.append(pd.DataFrame(parity_rows(pytorch_parity, parity, runtime)))
        if runtime == "ONNX_INT8":
            int8_encoder = encoder
        else:
            del encoder
            gc.collect()

    predictions = pd.concat(all_predictions, ignore_index=True)
    metrics = pd.DataFrame(metric_rows)
    thresholds = threshold_analysis(predictions).rename(columns={"model": "runtime"})
    thresholds["is_score_gap_0_01"] = (
        thresholds.threshold_type.eq("GAP_ONLY") & thresholds.minimum_gap.eq(0.01)
    )
    thresholds["is_best_safe_threshold"] = False
    for runtime, group in thresholds.groupby("runtime"):
        candidates = group[(group.accepted_accuracy >= 0.90) & (group.accepted_suggestions > 0)]
        if not candidates.empty:
            best_index = candidates.sort_values(
                ["coverage_percentage", "accepted_accuracy"], ascending=False
            ).index[0]
            thresholds.loc[best_index, "is_best_safe_threshold"] = True

    performance = measure_performance(root, repetitions=args.cold_repetitions)
    metrics = metrics.merge(performance, on="runtime", how="left")
    tokenizer = AutoTokenizer.from_pretrained(artifacts / "tokenizer", local_files_only=True)
    int8_baseline = next(row for row in metric_rows if row["runtime"] == "ONNX_INT8")
    sequence_results = sequence_analysis(int8_encoder, tokenizer, data, int8_baseline)
    recommended_max_length = choose_max_length(sequence_results)

    metrics.to_csv(output / "onnx_benchmark_results.csv", index=False)
    pd.concat(parity_frames, ignore_index=True).to_csv(
        output / "onnx_embedding_parity.csv", index=False
    )
    thresholds.to_csv(output / "onnx_threshold_analysis.csv", index=False)
    pd.concat(books_frames, ignore_index=True).to_csv(
        output / "onnx_custom_category_simulation.csv", index=False
    )
    predictions.to_csv(output / "onnx_predictions.csv", index=False)
    performance.to_csv(output / "onnx_performance_results.csv", index=False)
    sequence_results.to_csv(output / "onnx_sequence_length_analysis.csv", index=False)

    manifest["benchmark"] = {
        "dataset_rows": len(data),
        "canonical_products": int(data.canonical_id.nunique()),
        "prototype_strategy": "HYBRID_PROTOTYPE",
        "prototype_weights": {"description": 0.5, "clean_example_centroid": 0.5},
        "canonical_id_exclusion": True,
        "evaluated_max_sequence_length": 512,
        "recommended_max_sequence_length": recommended_max_length,
        "recommendation_rule": "smallest tested limit with no truncation and <=0.5 percentage-point loss",
    }
    manifest["results_files"] = [
        "../data/onnx_benchmark_results.csv",
        "../data/onnx_embedding_parity.csv",
        "../data/onnx_threshold_analysis.csv",
        "../data/onnx_custom_category_simulation.csv",
        "../data/onnx_performance_results.csv",
        "../data/onnx_sequence_length_analysis.csv",
        "../data/onnx_artifact_sizes.csv",
    ]
    manifest_path = onnx_root / "deployment_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    tokenizer_files = manifest["artifacts"]["tokenizer_files"]
    tokenizer_model_bytes = sum(
        int(value["bytes"]) for value in tokenizer_files if value["path"].endswith("tokenizer.json")
    )
    tokenizer_config_bytes = sum(
        int(value["bytes"]) for value in tokenizer_files if not value["path"].endswith("tokenizer.json")
    )
    tokenizer_bytes = tokenizer_model_bytes + tokenizer_config_bytes
    manifest_bytes = manifest_path.stat().st_size
    size_rows = []
    for runtime, key in (("ONNX_FP32", "fp32_model"), ("ONNX_INT8", "int8_model")):
        model_bytes = int(manifest["artifacts"][key]["bytes"])
        size_rows.append({
            "runtime": runtime,
            "model_bytes": model_bytes,
            "model_mib": model_bytes / (1024 * 1024),
            "tokenizer_bytes": tokenizer_bytes,
            "tokenizer_mib": tokenizer_bytes / (1024 * 1024),
            "tokenizer_model_bytes": tokenizer_model_bytes,
            "tokenizer_config_bytes": tokenizer_config_bytes,
            "deployment_manifest_bytes": manifest_bytes,
            "estimated_total_bytes": model_bytes + tokenizer_bytes + manifest_bytes,
            "estimated_total_mib": (model_bytes + tokenizer_bytes + manifest_bytes) / (1024 * 1024),
        })
    pd.DataFrame(size_rows).to_csv(output / "onnx_artifact_sizes.csv", index=False)

    print("\nRuntime results")
    print(metrics.to_string(index=False))
    print("\nEmbedding parity")
    print(pd.concat(parity_frames).groupby("runtime").agg(
        mean_cosine=("cosine_similarity_to_pytorch", "mean"),
        worst_cosine=("cosine_similarity_to_pytorch", "min"),
        mean_absolute_difference=("mean_absolute_difference", "mean"),
    ).to_string())
    print("\nRecommended max sequence length:", recommended_max_length)


if __name__ == "__main__":
    main()
