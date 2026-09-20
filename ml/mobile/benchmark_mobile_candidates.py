from __future__ import annotations

import difflib
import gc
import itertools
import json
import re
import statistics
import sys
import time
import unicodedata
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
import pandas as pd
from transformers import AutoTokenizer


ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT / "src"))
from benchmark import CATEGORY_DESCRIPTIONS, _l2_normalize
from benchmark_v2 import (
    _books_simulation,
    _canonical_reference_vectors,
    _category_vectors_for_item,
    _prediction_row,
    threshold_analysis,
    validate_v2_dataset,
)


MAX_LENGTH = 128
FOCUS_CATEGORIES = ("Medicine", "Household", "Groceries", "Restaurants", "Books")
DIGIT_TRANSLATION = str.maketrans("٠١٢٣٤٥٦٧٨٩۰۱۲۳۴۵۶۷۸۹", "01234567890123456789")
ARABIC_MARKS = re.compile(r"[\u0610-\u061a\u064b-\u065f\u0670\u06d6-\u06ed]")
NON_WORD = re.compile(r"[^\w\u0600-\u06ff]+", re.UNICODE)


class GenericOnnxEncoder:
    def __init__(
        self,
        model_path: Path,
        tokenizer_path: Path,
        query_prefix: str,
        passage_prefix: str,
    ) -> None:
        self.session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        self.tokenizer = AutoTokenizer.from_pretrained(tokenizer_path, local_files_only=True)
        self.query_prefix = query_prefix
        self.passage_prefix = passage_prefix

    def encode(
        self,
        texts: list[str],
        *,
        categories: bool,
        batch_size: int = 32,
        max_length: int = MAX_LENGTH,
    ) -> np.ndarray:
        prefix = self.passage_prefix if categories else self.query_prefix
        values = []
        for start in range(0, len(texts), batch_size):
            encoded = self.tokenizer(
                [prefix + text for text in texts[start : start + batch_size]],
                padding=True,
                truncation=True,
                max_length=max_length,
                return_tensors="np",
            )
            values.append(self.session.run(["sentence_embedding"], {
                "input_ids": np.asarray(encoded["input_ids"], dtype=np.int64),
                "attention_mask": np.asarray(encoded["attention_mask"], dtype=np.int64),
            })[0])
        return _l2_normalize(np.concatenate(values).astype(np.float32, copy=False))


def model_configurations() -> list[dict[str, Any]]:
    e5_tokenizer = ML_ROOT / "onnx" / "artifacts" / "tokenizer"
    e5_fp32 = ML_ROOT / "onnx" / "artifacts" / "fp32" / "multilingual-e5-small.onnx"
    configurations = [{
        "approach": "E5_ONNX_FP32",
        "family": "multilingual-e5-small",
        "quantization": "FP32",
        "model_path": e5_fp32,
        "tokenizer_path": e5_tokenizer,
        "query_prefix": "query: ",
        "passage_prefix": "passage: ",
        "quantization_metadata": {},
        "parity_reference": "E5_ONNX_FP32",
    }]
    quantized = json.loads((ML_ROOT / "mobile" / "quantization_variants.json").read_text())
    for record in quantized:
        if record["status"] != "valid":
            continue
        configurations.append({
            "approach": record["name"],
            "family": "multilingual-e5-small",
            "quantization": record["quantization"],
            "model_path": Path(record["path"]),
            "tokenizer_path": e5_tokenizer,
            "query_prefix": "query: ",
            "passage_prefix": "passage: ",
            "quantization_metadata": record,
            "parity_reference": "E5_ONNX_FP32",
        })
    compact = json.loads((ML_ROOT / "mobile" / "compact_models.json").read_text())
    for record in compact:
        for precision, path_key in (("FP32", "fp32_path"), ("DYNAMIC_INT8_PER_CHANNEL", "int8_path")):
            approach = f"{record['key']}_{precision}"
            configurations.append({
                "approach": approach,
                "family": record["model_name"],
                "quantization": precision,
                "model_path": Path(record[path_key]),
                "tokenizer_path": Path(record["tokenizer_path"]),
                "query_prefix": record["query_prefix"],
                "passage_prefix": record["passage_prefix"],
            "quantization_metadata": {
                "per_channel": precision == "DYNAMIC_INT8_PER_CHANNEL",
                "activation_type": "runtime dynamic" if precision != "FP32" else "FP32",
                "weight_type": "QInt8" if precision != "FP32" else "FP32",
                "op_scope": "ONNX Runtime defaults" if precision != "FP32" else "none",
            },
                "parity_reference": f"{record['key']}_FP32",
            })
    return configurations


def embedding_bundle(encoder: GenericOnnxEncoder, data: pd.DataFrame):
    descriptions = encoder.encode(list(CATEGORY_DESCRIPTIONS.values()), categories=True)
    clean = data[data.variant_type == "clean"].reset_index(drop=True)
    clean_embeddings = encoder.encode(clean.text.astype(str).tolist(), categories=True)
    item_embeddings = encoder.encode(data.text.astype(str).tolist(), categories=False)
    return descriptions, clean_embeddings, item_embeddings


def hybrid_predictions(
    approach: str,
    data: pd.DataFrame,
    descriptions: np.ndarray,
    clean_embeddings: np.ndarray,
    item_embeddings: np.ndarray,
) -> pd.DataFrame:
    categories = list(CATEGORY_DESCRIPTIONS)
    clean = data[data.variant_type == "clean"].reset_index(drop=True)
    references = _canonical_reference_vectors(clean, clean_embeddings)
    rows = []
    for index, example in data.iterrows():
        prototypes = _category_vectors_for_item(
            categories,
            descriptions,
            references,
            example.canonical_id,
            "HYBRID_PROTOTYPE",
        )
        rows.append(_prediction_row(
            example,
            approach,
            "HYBRID_PROTOTYPE",
            categories,
            item_embeddings[index] @ prototypes.T,
        ))
    return pd.DataFrame(rows)


def normalize_text(value: str) -> str:
    value = unicodedata.normalize("NFKC", value).translate(DIGIT_TRANSLATION).casefold()
    value = ARABIC_MARKS.sub("", value)
    value = value.replace("ـ", "")
    value = NON_WORD.sub(" ", value)
    return " ".join(value.split())


def ocr_fold(value: str) -> str:
    normalized = normalize_text(value)
    return normalized.translate(str.maketrans({"0": "o", "1": "l", "5": "s", "7": "t"}))


def tokens(value: str) -> set[str]:
    return {token for token in normalize_text(value).split() if len(token) > 1}


def lexical_score(item: str, description: str, references: list[str]) -> float:
    item_normal = normalize_text(item)
    item_ocr = ocr_fold(item)
    item_tokens = tokens(item)
    description_tokens = tokens(description)
    keyword_overlap = len(item_tokens & description_tokens) / max(1, min(3, len(item_tokens)))
    best_reference = 0.0
    for reference in references:
        reference_normal = normalize_text(reference)
        fuzzy = max(
            difflib.SequenceMatcher(None, item_normal, reference_normal).ratio(),
            difflib.SequenceMatcher(None, item_ocr, ocr_fold(reference)).ratio(),
        )
        reference_tokens = tokens(reference)
        union = item_tokens | reference_tokens
        jaccard = len(item_tokens & reference_tokens) / len(union) if union else 0.0
        best_reference = max(best_reference, 0.75 * fuzzy + 0.25 * jaccard)
    return max(best_reference, min(1.0, 0.85 * keyword_overlap))


def lexical_reference_map(data: pd.DataFrame) -> dict[str, dict[str, str]]:
    clean = data[data.variant_type == "clean"]
    references: dict[str, dict[str, str]] = {}
    for _, row in clean.iterrows():
        references.setdefault(row.category, {}).setdefault(row.canonical_id, row.text)
    return references


def lexical_prediction(
    example: pd.Series,
    references: dict[str, dict[str, str]],
    *,
    book_reference_ids: tuple[str, ...] | None = None,
) -> tuple[list[str], list[float]]:
    scored = []
    for category, description in CATEGORY_DESCRIPTIONS.items():
        available = references.get(category, {})
        if category == "Books" and book_reference_ids is not None:
            ids = book_reference_ids
        else:
            ids = tuple(key for key in available if key != example.canonical_id)
        values = [available[key] for key in ids if key in available and key != example.canonical_id]
        scored.append((category, lexical_score(example.text, description, values)))
    scored.sort(key=lambda value: (-value[1], value[0]))
    return [value[0] for value in scored[:3]], [value[1] for value in scored[:3]]


def lexical_predictions(data: pd.DataFrame) -> tuple[pd.DataFrame, float]:
    references = lexical_reference_map(data)
    rows = []
    timings = []
    for _ in range(5):
        started = time.perf_counter()
        trial = []
        for _, example in data.iterrows():
            categories, scores = lexical_prediction(example, references)
            trial.append({
                **example.to_dict(),
                "model": "LEXICAL_FUZZY_BASELINE",
                "strategy": "LEXICAL_KEYWORD_FUZZY",
                "predicted_category": categories[0],
                "top1_score": scores[0],
                "top2_category": categories[1],
                "top2_score": scores[1],
                "score_gap": scores[0] - scores[1],
                "top3_categories": json.dumps(categories, ensure_ascii=False),
                "top3_scores": json.dumps(scores),
                "top1_correct": categories[0] == example.category,
                "top3_correct": example.category in categories,
            })
        timings.append((time.perf_counter() - started) * 1000.0 / len(data))
        rows = trial
    return pd.DataFrame(rows), statistics.mean(timings)


def lexical_books_simulation(data: pd.DataFrame) -> pd.DataFrame:
    references = lexical_reference_map(data)
    rows = []
    books = data[data.category == "Books"]
    book_ids = sorted(references["Books"])
    for confirmed in (0, 1, 3, 5):
        correct = 0
        trials = 0
        for _, example in books.iterrows():
            eligible = [value for value in book_ids if value != example.canonical_id]
            combinations = [tuple()] if confirmed == 0 else itertools.combinations(eligible, confirmed)
            for selected in combinations:
                categories, _ = lexical_prediction(
                    example, references, book_reference_ids=tuple(selected)
                )
                correct += int(categories[0] == "Books")
                trials += 1
        rows.append({
            "approach": "LEXICAL_FUZZY_BASELINE",
            "state": f"BOOKS_{confirmed}_EXAMPLES",
            "confirmed_examples": confirmed,
            "books_top1_accuracy": correct / trials if trials else np.nan,
            "evaluation_trials": trials,
            "evaluation_method": "all eligible leave-one-canonical-out reference combinations",
        })
    return pd.DataFrame(rows)


def metric_row(approach: str, predictions: pd.DataFrame) -> dict[str, Any]:
    row: dict[str, Any] = {
        "approach": approach,
        "overall_top1_accuracy": predictions.top1_correct.mean(),
        "overall_top3_accuracy": predictions.top3_correct.mean(),
        "english_top1_accuracy": predictions.loc[predictions.language == "en", "top1_correct"].mean(),
        "arabic_top1_accuracy": predictions.loc[predictions.language == "ar", "top1_correct"].mean(),
        "mixed_top1_accuracy": predictions.loc[predictions.language == "mixed", "top1_correct"].mean(),
        "clean_top1_accuracy": predictions.loc[predictions.variant_type == "clean", "top1_correct"].mean(),
        "ocr_noise_top1_accuracy": predictions.loc[predictions.variant_type == "ocr_noise", "top1_correct"].mean(),
    }
    for category in FOCUS_CATEGORIES:
        row[f"category_top1__{category.lower()}"] = predictions.loc[
            predictions.category == category, "top1_correct"
        ].mean()
    return row


def warm_latencies(encoder: GenericOnnxEncoder, data: pd.DataFrame) -> tuple[float, float]:
    item_texts = data.text.astype(str).tolist()
    descriptions = list(CATEGORY_DESCRIPTIONS.values())
    encoder.encode(item_texts[:8], categories=False, batch_size=8)
    batch_values = []
    for _ in range(5):
        started = time.perf_counter()
        encoder.encode(item_texts, categories=False)
        encoder.encode(descriptions, categories=True)
        batch_values.append((time.perf_counter() - started) * 1000.0 / (len(item_texts) + len(descriptions)))
    single_values = []
    for _ in range(3):
        started = time.perf_counter()
        for text in item_texts[:32]:
            encoder.encode([text], categories=False, batch_size=1)
        single_values.append((time.perf_counter() - started) * 1000.0 / 32)
    return statistics.mean(single_values), statistics.mean(batch_values)


def size_guidance(total_mib: float) -> str:
    if total_mib <= 50:
        return "Excellent"
    if total_mib <= 80:
        return "Good"
    if total_mib <= 120:
        return "Borderline"
    return "Too large"


def best_safe_thresholds(thresholds: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for approach, group in thresholds.groupby("approach"):
        candidates = group[(group.accepted_accuracy >= 0.90) & (group.accepted_suggestions > 0)]
        if candidates.empty:
            continue
        rows.append(candidates.sort_values(
            ["coverage_percentage", "accepted_accuracy"], ascending=False
        ).iloc[0])
    return pd.DataFrame(rows).reset_index(drop=True)


def pipeline_simulation(
    predictions: pd.DataFrame,
    thresholds: pd.DataFrame,
) -> pd.DataFrame:
    best = best_safe_thresholds(thresholds).set_index("approach")
    lexical = predictions[predictions.model == "LEXICAL_FUZZY_BASELINE"].reset_index(drop=True)
    rows = []
    if "LEXICAL_FUZZY_BASELINE" not in best.index:
        return pd.DataFrame()
    lexical_threshold = best.loc["LEXICAL_FUZZY_BASELINE"]

    def accepted(frame: pd.DataFrame, threshold: pd.Series) -> pd.Series:
        mask = frame.score_gap >= threshold.minimum_gap
        if not pd.isna(threshold.minimum_score):
            mask &= frame.top1_score >= threshold.minimum_score
        return mask

    lexical_accepts = accepted(lexical, lexical_threshold)
    for approach in predictions.model.unique():
        if approach == "LEXICAL_FUZZY_BASELINE" or approach not in best.index:
            continue
        semantic = predictions[predictions.model == approach].reset_index(drop=True)
        semantic_accepts = accepted(semantic, best.loc[approach]) & ~lexical_accepts
        combined_accepts = lexical_accepts | semantic_accepts
        correct = (
            (lexical_accepts & lexical.top1_correct)
            | (semantic_accepts & semantic.top1_correct)
        )
        rows.append({
            "pipeline": f"USER_LEARNED -> LEXICAL -> {approach} -> ABSTAIN",
            "semantic_approach": approach,
            "accepted_suggestions": int(combined_accepts.sum()),
            "coverage_percentage": float(combined_accepts.mean() * 100),
            "accepted_accuracy": float(correct.sum() / combined_accepts.sum()),
            "incorrect_accepted_suggestions": int(combined_accepts.sum() - correct.sum()),
            "lexical_accepted": int(lexical_accepts.sum()),
            "semantic_accepted_after_lexical": int(semantic_accepts.sum()),
            "note": "USER_LEARNED exact matches unavailable in this static benchmark",
        })
    return pd.DataFrame(rows)


def main() -> None:
    data = pd.read_csv(ML_ROOT / "data" / "categorization_benchmark_v2.csv").reset_index(drop=True)
    validate_v2_dataset(data)
    configurations = model_configurations()
    prediction_frames = []
    metric_rows = []
    book_frames = []
    bundles: dict[str, tuple[np.ndarray, np.ndarray, np.ndarray]] = {}

    for configuration in configurations:
        approach = configuration["approach"]
        print(f"Benchmarking {approach}...")
        encoder = GenericOnnxEncoder(
            configuration["model_path"],
            configuration["tokenizer_path"],
            configuration["query_prefix"],
            configuration["passage_prefix"],
        )
        bundle = embedding_bundle(encoder, data)
        bundles[approach] = bundle
        predictions = hybrid_predictions(approach, data, *bundle)
        prediction_frames.append(predictions)
        metrics = metric_row(approach, predictions)
        model_bytes = configuration["model_path"].stat().st_size
        tokenizer_bytes = sum(
            path.stat().st_size for path in configuration["tokenizer_path"].iterdir() if path.is_file()
        )
        total_mib = (model_bytes + tokenizer_bytes) / (1024 * 1024)
        single_latency, batch_latency = warm_latencies(encoder, data)
        quantization_metadata = configuration["quantization_metadata"]
        metrics.update({
            "family": configuration["family"],
            "quantization": configuration["quantization"],
            "model_bytes": model_bytes,
            "tokenizer_bytes": tokenizer_bytes,
            "total_deployable_mib": total_mib,
            "total_with_existing_ocr_mib": total_mib + 15.0,
            "size_guidance": size_guidance(total_mib),
            "warm_single_ms_per_item": single_latency,
            "warm_batch32_ms_per_item": batch_latency,
            "parity_reference": configuration["parity_reference"],
            "quantization_per_channel": quantization_metadata.get("per_channel"),
            "quantization_activation_type": quantization_metadata.get("activation_type"),
            "quantization_weight_type": quantization_metadata.get("weight_type"),
            "quantization_op_scope": str(quantization_metadata.get("op_scope", "")),
        })
        metric_rows.append(metrics)
        clean = data[data.variant_type == "clean"].reset_index(drop=True)
        references = _canonical_reference_vectors(clean, bundle[1])
        book_frames.append(pd.DataFrame(_books_simulation(
            approach,
            data,
            bundle[2],
            list(CATEGORY_DESCRIPTIONS),
            bundle[0],
            references,
        )).rename(columns={"model": "approach"}))
        del encoder
        gc.collect()

    lexical, lexical_latency = lexical_predictions(data)
    prediction_frames.append(lexical)
    lexical_metrics = metric_row("LEXICAL_FUZZY_BASELINE", lexical)
    lexical_metrics.update({
        "family": "non-neural lexical",
        "quantization": "none",
        "model_bytes": 0,
        "tokenizer_bytes": 0,
        "total_deployable_mib": 0.0,
        "total_with_existing_ocr_mib": 15.0,
        "size_guidance": "Excellent",
        "warm_single_ms_per_item": lexical_latency,
        "warm_batch32_ms_per_item": lexical_latency,
        "parity_reference": "not applicable",
        "quantization_per_channel": np.nan,
        "quantization_activation_type": "none",
        "quantization_weight_type": "none",
        "quantization_op_scope": "none",
        "mean_embedding_cosine_vs_reference": np.nan,
        "worst_embedding_cosine_vs_reference": np.nan,
    })
    metric_rows.append(lexical_metrics)
    book_frames.append(lexical_books_simulation(data))

    metrics = pd.DataFrame(metric_rows)
    for index, row in metrics.iterrows():
        approach = row.approach
        reference = row.parity_reference
        if approach == "LEXICAL_FUZZY_BASELINE":
            continue
        candidate = np.concatenate(bundles[approach])
        expected = np.concatenate(bundles[reference])
        cosine = np.sum(candidate * expected, axis=1)
        metrics.loc[index, "mean_embedding_cosine_vs_reference"] = cosine.mean()
        metrics.loc[index, "worst_embedding_cosine_vs_reference"] = cosine.min()

    predictions = pd.concat(prediction_frames, ignore_index=True)
    thresholds = threshold_analysis(predictions).rename(columns={"model": "approach"})
    best = best_safe_thresholds(thresholds)
    books = pd.concat(book_frames, ignore_index=True)
    book_pivot = books.pivot(index="approach", columns="confirmed_examples", values="books_top1_accuracy")
    book_pivot.columns = [f"books_{value}_examples_accuracy" for value in book_pivot.columns]
    metrics = metrics.merge(book_pivot.reset_index(), on="approach", how="left")
    safe_columns = best[[
        "approach", "threshold_type", "minimum_score", "minimum_gap",
        "accepted_accuracy", "coverage_percentage", "incorrect_accepted_suggestions",
    ]].rename(columns={
        "threshold_type": "safe_threshold_type",
        "minimum_score": "safe_minimum_score",
        "minimum_gap": "safe_minimum_gap",
        "accepted_accuracy": "safe_accepted_accuracy",
        "coverage_percentage": "safe_coverage_percentage",
        "incorrect_accepted_suggestions": "safe_incorrect_accepted_suggestions",
    })
    metrics = metrics.merge(safe_columns, on="approach", how="left")

    quantization_names = {
        value["name"] for value in json.loads(
            (ML_ROOT / "mobile" / "quantization_variants.json").read_text()
        ) if value["status"] == "valid"
    }
    quantization = metrics[
        metrics.approach.isin(quantization_names | {"E5_ONNX_FP32"})
    ].copy()
    pipeline = pipeline_simulation(predictions, thresholds)

    output = ML_ROOT / "data"
    metrics.to_csv(output / "mobile_model_comparison.csv", index=False)
    quantization.to_csv(output / "quantization_comparison.csv", index=False)
    thresholds.to_csv(output / "mobile_threshold_analysis.csv", index=False)
    books.to_csv(output / "mobile_custom_category_results.csv", index=False)
    pipeline.to_csv(output / "mobile_pipeline_simulation.csv", index=False)
    predictions.to_csv(output / "mobile_candidate_predictions.csv", index=False)
    print(metrics.sort_values("overall_top1_accuracy", ascending=False).to_string(index=False))


if __name__ == "__main__":
    main()
