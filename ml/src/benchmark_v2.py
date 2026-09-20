from __future__ import annotations

import argparse
import itertools
import json
import time
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from sentence_transformers import SentenceTransformer

from benchmark import CATEGORY_DESCRIPTIONS, MODEL_NAMES, _l2_normalize, _model_inputs


STRATEGIES = ["DESCRIPTION_ONLY", "EXAMPLE_CENTROID", "HYBRID_PROTOTYPE"]
GAP_THRESHOLDS = [0.01, 0.02, 0.03, 0.04, 0.05, 0.06, 0.08, 0.10, 0.12, 0.15, 0.20]
MINIMUM_SCORE_THRESHOLDS = [round(value, 2) for value in np.arange(0.20, 0.91, 0.05)]
FOCUS_CATEGORIES = ["Medicine", "Household", "Groceries", "Restaurants", "Books"]


def validate_v2_dataset(data: pd.DataFrame) -> None:
    required = {"canonical_id", "text", "category", "language", "variant_type"}
    missing = required.difference(data.columns)
    if missing:
        raise ValueError(f"V2 dataset is missing columns: {sorted(missing)}")
    if data["canonical_id"].isna().any() or data["canonical_id"].astype(str).str.strip().eq("").any():
        raise ValueError("Every benchmark row needs a canonical_id.")
    categories_per_product = data.groupby("canonical_id")["category"].nunique()
    leaking = categories_per_product[categories_per_product > 1]
    if not leaking.empty:
        raise ValueError(f"Canonical products span multiple categories: {leaking.index.tolist()}")


def _normalize_vector(vector: np.ndarray) -> np.ndarray:
    return vector / max(float(np.linalg.norm(vector)), 1e-12)


def _encode(
    model: SentenceTransformer,
    model_name: str,
    texts: list[str],
    *,
    categories: bool,
    batch_size: int,
    progress: bool = False,
) -> np.ndarray:
    values = model.encode(
        _model_inputs(model_name, texts, categories=categories),
        batch_size=batch_size,
        convert_to_numpy=True,
        show_progress_bar=progress,
    )
    return _l2_normalize(np.asarray(values, dtype=np.float32))


def _canonical_reference_vectors(
    clean_data: pd.DataFrame,
    clean_embeddings: np.ndarray,
) -> dict[str, dict[str, np.ndarray]]:
    references: dict[str, dict[str, np.ndarray]] = {}
    indexed = clean_data.reset_index(drop=True)
    for (category, canonical_id), indices in indexed.groupby(["category", "canonical_id"]).groups.items():
        vector = _normalize_vector(clean_embeddings[list(indices)].mean(axis=0))
        references.setdefault(category, {})[canonical_id] = vector
    return references


def _category_vectors_for_item(
    categories: list[str],
    descriptions: np.ndarray,
    references: dict[str, dict[str, np.ndarray]],
    excluded_canonical_id: str,
    strategy: str,
) -> np.ndarray:
    vectors = []
    for category_index, category in enumerate(categories):
        description = descriptions[category_index]
        eligible = [
            vector
            for canonical_id, vector in references.get(category, {}).items()
            if canonical_id != excluded_canonical_id
        ]
        centroid = _normalize_vector(np.mean(eligible, axis=0)) if eligible else description
        if strategy == "DESCRIPTION_ONLY":
            vector = description
        elif strategy == "EXAMPLE_CENTROID":
            vector = centroid
        elif strategy == "HYBRID_PROTOTYPE":
            vector = _normalize_vector(0.5 * description + 0.5 * centroid)
        else:
            raise ValueError(f"Unknown strategy: {strategy}")
        vectors.append(vector)
    return np.asarray(vectors, dtype=np.float32)


def _prediction_row(
    example: pd.Series,
    model_name: str,
    strategy: str,
    categories: list[str],
    similarities: np.ndarray,
) -> dict[str, Any]:
    ranking = np.argsort(-similarities)
    top_indices = ranking[:3]
    top_categories = [categories[index] for index in top_indices]
    top_scores = [float(similarities[index]) for index in top_indices]
    return {
        **example.to_dict(),
        "model": model_name,
        "strategy": strategy,
        "predicted_category": top_categories[0],
        "top1_score": top_scores[0],
        "top2_category": top_categories[1],
        "top2_score": top_scores[1],
        "score_gap": top_scores[0] - top_scores[1],
        "top3_categories": json.dumps(top_categories, ensure_ascii=False),
        "top3_scores": json.dumps(top_scores),
        "top1_correct": top_categories[0] == example["category"],
        "top3_correct": example["category"] in top_categories,
    }


def _configuration_metrics(
    predictions: pd.DataFrame,
    inference_ms_per_item: float,
) -> dict[str, Any]:
    row: dict[str, Any] = {
        "model": predictions["model"].iat[0],
        "strategy": predictions["strategy"].iat[0],
        "overall_top1_accuracy": predictions["top1_correct"].mean(),
        "overall_top3_accuracy": predictions["top3_correct"].mean(),
        "english_top1_accuracy": predictions.loc[predictions.language == "en", "top1_correct"].mean(),
        "arabic_top1_accuracy": predictions.loc[predictions.language == "ar", "top1_correct"].mean(),
        "mixed_top1_accuracy": predictions.loc[predictions.language == "mixed", "top1_correct"].mean(),
        "clean_top1_accuracy": predictions.loc[predictions.variant_type == "clean", "top1_correct"].mean(),
        "ocr_noise_top1_accuracy": predictions.loc[predictions.variant_type == "ocr_noise", "top1_correct"].mean(),
        "average_inference_ms_per_item": inference_ms_per_item,
    }
    for category in CATEGORY_DESCRIPTIONS:
        key = "category_top1__" + category.lower().replace(" & ", "_").replace(" ", "_")
        row[key] = predictions.loc[predictions.category == category, "top1_correct"].mean()
    return row


def _threshold_row(
    predictions: pd.DataFrame,
    *,
    threshold_type: str,
    minimum_gap: float,
    minimum_score: float | None,
) -> dict[str, Any]:
    accepted = predictions["score_gap"] >= minimum_gap
    if minimum_score is not None:
        accepted &= predictions["top1_score"] >= minimum_score
    accepted_count = int(accepted.sum())
    correct_accepted = int(predictions.loc[accepted, "top1_correct"].sum())
    incorrect_accepted = accepted_count - correct_accepted
    return {
        "model": predictions["model"].iat[0],
        "strategy": predictions["strategy"].iat[0],
        "threshold_type": threshold_type,
        "minimum_score": minimum_score,
        "minimum_gap": minimum_gap,
        "accepted_suggestions": accepted_count,
        "abstained": len(predictions) - accepted_count,
        "coverage_percentage": accepted_count * 100.0 / len(predictions),
        "accepted_accuracy": correct_accepted / accepted_count if accepted_count else np.nan,
        "incorrect_accepted_suggestions": incorrect_accepted,
    }


def threshold_analysis(predictions: pd.DataFrame) -> pd.DataFrame:
    rows = []
    for (_, _), configuration in predictions.groupby(["model", "strategy"], sort=False):
        for gap in GAP_THRESHOLDS:
            rows.append(
                _threshold_row(
                    configuration,
                    threshold_type="GAP_ONLY",
                    minimum_gap=gap,
                    minimum_score=None,
                )
            )
        for score in MINIMUM_SCORE_THRESHOLDS:
            for gap in GAP_THRESHOLDS:
                rows.append(
                    _threshold_row(
                        configuration,
                        threshold_type="SCORE_AND_GAP",
                        minimum_gap=gap,
                        minimum_score=score,
                    )
                )
    return pd.DataFrame(rows)


def _books_simulation(
    model_name: str,
    data: pd.DataFrame,
    item_embeddings: np.ndarray,
    categories: list[str],
    description_embeddings: np.ndarray,
    references: dict[str, dict[str, np.ndarray]],
) -> list[dict[str, Any]]:
    books_index = categories.index("Books")
    book_references = references["Books"]
    rows = []
    for confirmed_examples in (0, 1, 3, 5):
        correct = 0
        trials = 0
        for item_index, example in data.iterrows():
            if example["category"] != "Books":
                continue
            eligible_ids = sorted(
                canonical_id
                for canonical_id in book_references
                if canonical_id != example["canonical_id"]
            )
            if confirmed_examples == 0:
                reference_sets = [tuple()]
            elif len(eligible_ids) >= confirmed_examples:
                reference_sets = itertools.combinations(eligible_ids, confirmed_examples)
            else:
                continue
            for reference_ids in reference_sets:
                prototypes = description_embeddings.copy()
                if reference_ids:
                    centroid = _normalize_vector(
                        np.mean([book_references[canonical_id] for canonical_id in reference_ids], axis=0)
                    )
                    prototypes[books_index] = _normalize_vector(
                        0.5 * description_embeddings[books_index] + 0.5 * centroid
                    )
                prediction = categories[int(np.argmax(item_embeddings[item_index] @ prototypes.T))]
                correct += int(prediction == "Books")
                trials += 1
        rows.append(
            {
                "model": model_name,
                "state": f"BOOKS_{confirmed_examples}_EXAMPLES",
                "confirmed_examples": confirmed_examples,
                "books_top1_accuracy": correct / trials if trials else np.nan,
                "evaluation_trials": trials,
                "evaluation_method": "all eligible leave-one-canonical-out reference combinations",
            }
        )
    return rows


def benchmark_model_v2(
    model_name: str,
    data: pd.DataFrame,
    *,
    batch_size: int,
    device: str | None,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    model = SentenceTransformer(model_name, device=device)
    categories = list(CATEGORY_DESCRIPTIONS)
    description_embeddings = _encode(
        model,
        model_name,
        [CATEGORY_DESCRIPTIONS[category] for category in categories],
        categories=True,
        batch_size=batch_size,
    )
    clean_data = data[data.variant_type == "clean"].reset_index(drop=True)
    clean_embeddings = _encode(
        model,
        model_name,
        clean_data.text.astype(str).tolist(),
        categories=True,
        batch_size=batch_size,
    )
    references = _canonical_reference_vectors(clean_data, clean_embeddings)
    inference_started = time.perf_counter()
    item_embeddings = _encode(
        model,
        model_name,
        data.text.astype(str).tolist(),
        categories=False,
        batch_size=batch_size,
        progress=True,
    )
    inference_ms_per_item = (time.perf_counter() - inference_started) * 1000.0 / len(data)

    prediction_rows = []
    for item_index, example in data.iterrows():
        for strategy in STRATEGIES:
            prototypes = _category_vectors_for_item(
                categories,
                description_embeddings,
                references,
                example["canonical_id"],
                strategy,
            )
            similarities = item_embeddings[item_index] @ prototypes.T
            prediction_rows.append(
                _prediction_row(example, model_name, strategy, categories, similarities)
            )
    predictions = pd.DataFrame(prediction_rows)
    metrics = pd.DataFrame(
        [
            _configuration_metrics(configuration, inference_ms_per_item)
            for _, configuration in predictions.groupby("strategy", sort=False)
        ]
    )
    books = pd.DataFrame(
        _books_simulation(
            model_name,
            data,
            item_embeddings,
            categories,
            description_embeddings,
            references,
        )
    )
    return predictions, metrics, books


def error_frame(predictions: pd.DataFrame) -> pd.DataFrame:
    errors = predictions.loc[~predictions.top1_correct].copy()
    return errors.rename(
        columns={
            "category": "expected_category",
            "top1_score": "predicted_score",
            "top2_category": "second_best_category",
            "top2_score": "second_best_score",
        }
    )[
        [
            "canonical_id",
            "text",
            "expected_category",
            "predicted_category",
            "predicted_score",
            "second_best_category",
            "second_best_score",
            "score_gap",
            "language",
            "variant_type",
            "model",
            "strategy",
        ]
    ]


def run_v2_benchmark(
    dataset_path: Path,
    output_directory: Path,
    *,
    model_names: list[str] | None = None,
    batch_size: int = 32,
    device: str | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    data = pd.read_csv(dataset_path)
    validate_v2_dataset(data)
    all_predictions = []
    all_results = []
    all_books = []
    for model_name in model_names or MODEL_NAMES:
        print(f"Benchmarking {model_name} with leakage-safe prototypes...")
        predictions, results, books = benchmark_model_v2(
            model_name,
            data,
            batch_size=batch_size,
            device=device,
        )
        all_predictions.append(predictions)
        all_results.append(results)
        all_books.append(books)

    predictions = pd.concat(all_predictions, ignore_index=True)
    results = pd.concat(all_results, ignore_index=True)
    thresholds = threshold_analysis(predictions)
    books = pd.concat(all_books, ignore_index=True)
    errors = error_frame(predictions)
    output_directory.mkdir(parents=True, exist_ok=True)
    results.to_csv(output_directory / "benchmark_v2_results.csv", index=False)
    thresholds.to_csv(output_directory / "threshold_analysis.csv", index=False)
    books.to_csv(output_directory / "custom_category_simulation.csv", index=False)
    predictions.to_csv(output_directory / "benchmark_v2_predictions.csv", index=False)
    errors.to_csv(output_directory / "benchmark_v2_errors.csv", index=False)
    return predictions, results, thresholds, books, errors


def _print_summary(results: pd.DataFrame, thresholds: pd.DataFrame, books: pd.DataFrame) -> None:
    columns = [
        "model",
        "strategy",
        "overall_top1_accuracy",
        "overall_top3_accuracy",
        "ocr_noise_top1_accuracy",
        "average_inference_ms_per_item",
    ]
    print("\nSix-configuration comparison")
    print(results[columns].sort_values("overall_top1_accuracy", ascending=False).to_string(index=False))
    high_precision = thresholds[
        (thresholds.accepted_accuracy >= 0.90) & (thresholds.accepted_suggestions > 0)
    ].sort_values(["coverage_percentage", "accepted_accuracy"], ascending=False)
    print("\nHighest-coverage thresholds with at least 90% accepted accuracy")
    print(high_precision.head(12).to_string(index=False))
    print("\nBooks custom-category simulation")
    print(books.to_string(index=False))


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="SpendWise leakage-safe prototype benchmark V2.")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=root / "data" / "categorization_benchmark_v2.csv",
    )
    parser.add_argument("--output-dir", type=Path, default=root / "data")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--device", default=None)
    parser.add_argument("--models", nargs="+", default=MODEL_NAMES)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    _, results, thresholds, books, _ = run_v2_benchmark(
        args.dataset,
        args.output_dir,
        model_names=args.models,
        batch_size=args.batch_size,
        device=args.device,
    )
    _print_summary(results, thresholds, books)


if __name__ == "__main__":
    main()
