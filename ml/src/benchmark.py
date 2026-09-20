from __future__ import annotations

import argparse
import json
import time
from pathlib import Path
from typing import Any

import numpy as np
import pandas as pd
from sentence_transformers import SentenceTransformer
from sklearn.metrics import accuracy_score


MODEL_NAMES = [
    "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2",
    "intfloat/multilingual-e5-small",
]

CATEGORY_DESCRIPTIONS = {
    "Groceries": (
        "groceries, supermarket food, milk, rice, meat, chicken, pantry staples, packaged food, "
        "بقالة، سوبر ماركت، لبن، أرز، لحوم، فراخ، مواد غذائية"
    ),
    "Fruits & Vegetables": (
        "fresh fruit, vegetables, produce, apples, bananas, tomatoes, potatoes, "
        "فواكه، خضروات، خضار، تفاح، موز، طماطم، بطاطس"
    ),
    "Restaurants": (
        "restaurants, cafes, takeaway meals, fast food, coffee shops, dining, "
        "مطاعم، كافيهات، وجبات، أكل جاهز، قهوة"
    ),
    "Transport": (
        "transport, taxi, ride hailing, metro, bus, train, fuel, parking, "
        "مواصلات، تاكسي، أوبر، كريم، مترو، أتوبيس، بنزين، ركن"
    ),
    "Household": (
        "household supplies, cleaning products, detergent, dish soap, disinfectant, home maintenance, "
        "مستلزمات منزل، منظفات، مسحوق غسيل، صابون أطباق، مطهرات"
    ),
    "Medicine": (
        "medicines, pharmacy products, vitamins, medical supplies, drugs, supplements, "
        "أدوية، صيدلية، فيتامينات، مستلزمات طبية، مكملات غذائية"
    ),
    "Shopping": (
        "clothing, shoes, electronics, accessories, general retail shopping, online shopping, "
        "ملابس، أحذية، إلكترونيات، إكسسوارات، تسوق، مشتريات"
    ),
    "Bills": (
        "utility bills, electricity, water, gas, internet, mobile plan, subscriptions due, "
        "فواتير، كهرباء، مياه، غاز، إنترنت، موبايل"
    ),
    "Entertainment": (
        "movies, cinema, streaming, games, concerts, amusement, leisure activities, "
        "ترفيه، سينما، أفلام، ألعاب، حفلات، اشتراكات مشاهدة"
    ),
    "Personal Care": (
        "personal care, shampoo, soap, skincare, cosmetics, grooming, barber, salon, "
        "عناية شخصية، شامبو، صابون، عناية بالبشرة، مستحضرات تجميل، حلاق"
    ),
    "Other": (
        "miscellaneous items, uncategorized purchases, donations, fees, services, other expenses, "
        "مصاريف أخرى، متفرقات، رسوم، خدمات، تبرعات، غير مصنف"
    ),
    "Books": (
        "books, novels, textbooks, reading, literature, educational books, technical books, "
        "كتب، روايات، مراجع، كتب دراسية، قراءة، أدب"
    ),
}


def _l2_normalize(values: np.ndarray) -> np.ndarray:
    norms = np.linalg.norm(values, axis=1, keepdims=True)
    return values / np.clip(norms, 1e-12, None)


def _model_inputs(model_name: str, texts: list[str], *, categories: bool) -> list[str]:
    if model_name == "intfloat/multilingual-e5-small":
        prefix = "passage: " if categories else "query: "
        return [prefix + text for text in texts]
    return texts


def _cached_model_size_bytes(model_name: str) -> int | None:
    """Return snapshot size only when Hugging Face can resolve an already-downloaded cache."""
    try:
        from huggingface_hub import snapshot_download

        snapshot = Path(snapshot_download(repo_id=model_name, local_files_only=True))
        files = [path for path in snapshot.rglob("*") if path.is_file()]
        return sum(path.stat().st_size for path in files)
    except Exception:
        return None


def validate_dataset(data: pd.DataFrame) -> None:
    required = {"text", "category", "language", "variant_type"}
    missing = required.difference(data.columns)
    if missing:
        raise ValueError(f"Dataset is missing columns: {sorted(missing)}")
    unknown = set(data["category"]).difference(CATEGORY_DESCRIPTIONS)
    if unknown:
        raise ValueError(f"Dataset has categories without descriptions: {sorted(unknown)}")
    missing_examples = set(CATEGORY_DESCRIPTIONS).difference(data["category"])
    if missing_examples:
        raise ValueError(f"Descriptions have no benchmark examples: {sorted(missing_examples)}")


def benchmark_model(
    model_name: str,
    data: pd.DataFrame,
    *,
    batch_size: int = 32,
    device: str | None = None,
) -> tuple[pd.DataFrame, dict[str, Any], pd.DataFrame]:
    validate_dataset(data)
    load_started = time.perf_counter()
    model = SentenceTransformer(model_name, device=device)
    load_seconds = time.perf_counter() - load_started

    categories = list(CATEGORY_DESCRIPTIONS)
    category_inputs = _model_inputs(
        model_name,
        [CATEGORY_DESCRIPTIONS[category] for category in categories],
        categories=True,
    )
    category_embeddings = model.encode(
        category_inputs,
        batch_size=batch_size,
        convert_to_numpy=True,
        show_progress_bar=False,
    )
    category_embeddings = _l2_normalize(np.asarray(category_embeddings, dtype=np.float32))

    item_inputs = _model_inputs(model_name, data["text"].astype(str).tolist(), categories=False)
    inference_started = time.perf_counter()
    item_embeddings = model.encode(
        item_inputs,
        batch_size=batch_size,
        convert_to_numpy=True,
        show_progress_bar=True,
    )
    inference_seconds = time.perf_counter() - inference_started
    item_embeddings = _l2_normalize(np.asarray(item_embeddings, dtype=np.float32))

    similarities = item_embeddings @ category_embeddings.T
    ranked_indices = np.argsort(-similarities, axis=1)
    rows: list[dict[str, Any]] = []
    for row_index, (_, example) in enumerate(data.reset_index(drop=True).iterrows()):
        ranking = ranked_indices[row_index]
        top_indices = ranking[:3]
        top_categories = [categories[index] for index in top_indices]
        top_scores = [float(similarities[row_index, index]) for index in top_indices]
        rows.append(
            {
                **example.to_dict(),
                "model": model_name,
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
        )
    predictions = pd.DataFrame(rows)
    correct_gaps = predictions.loc[predictions["top1_correct"], "score_gap"]
    incorrect_gaps = predictions.loc[~predictions["top1_correct"], "score_gap"]
    correlation = predictions["score_gap"].corr(predictions["top1_correct"].astype(float))
    metrics: dict[str, Any] = {
        "model": model_name,
        "overall_top1_accuracy": float(
            accuracy_score(predictions["category"], predictions["predicted_category"])
        ),
        "overall_top3_accuracy": float(predictions["top3_correct"].mean()),
        "average_inference_ms_per_item": inference_seconds * 1000.0 / len(predictions),
        "model_load_seconds": load_seconds,
        "embedding_dimension": int(item_embeddings.shape[1]),
        "cached_model_size_bytes": _cached_model_size_bytes(model_name),
        "mean_score_gap_correct": float(correct_gaps.mean()) if not correct_gaps.empty else None,
        "mean_score_gap_incorrect": float(incorrect_gaps.mean()) if not incorrect_gaps.empty else None,
        "score_gap_correctness_correlation": None if pd.isna(correlation) else float(correlation),
    }
    grouped_frames = []
    for dimension in ("language", "variant_type", "category"):
        grouped = predictions.groupby(dimension, dropna=False).agg(
            examples=("text", "size"),
            top1_accuracy=("top1_correct", "mean"),
            top3_accuracy=("top3_correct", "mean"),
            average_score_gap=("score_gap", "mean"),
        ).reset_index(names="group")
        grouped.insert(0, "dimension", dimension)
        grouped.insert(0, "model", model_name)
        grouped_frames.append(grouped)
    return predictions, metrics, pd.concat(grouped_frames, ignore_index=True)


def error_frame(predictions: pd.DataFrame) -> pd.DataFrame:
    errors = predictions.loc[~predictions["top1_correct"]].copy()
    return errors.rename(
        columns={
            "category": "expected_category",
            "top1_score": "predicted_score",
            "top2_category": "second_best_category",
            "top2_score": "second_best_score",
        }
    )[
        [
            "text",
            "expected_category",
            "predicted_category",
            "predicted_score",
            "second_best_category",
            "second_best_score",
            "language",
            "variant_type",
            "model",
        ]
    ]


def run_benchmark(
    dataset_path: Path,
    output_directory: Path,
    *,
    model_names: list[str] | None = None,
    batch_size: int = 32,
    device: str | None = None,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    data = pd.read_csv(dataset_path)
    validate_dataset(data)
    output_directory.mkdir(parents=True, exist_ok=True)
    all_predictions = []
    all_metrics = []
    all_group_metrics = []
    failures = []
    for model_name in model_names or MODEL_NAMES:
        print(f"Benchmarking {model_name}...")
        try:
            predictions, metrics, groups = benchmark_model(
                model_name,
                data,
                batch_size=batch_size,
                device=device,
            )
        except Exception as error:
            failures.append({"model": model_name, "error": repr(error)})
            print(f"Failed to benchmark {model_name}: {error}")
            continue
        all_predictions.append(predictions)
        all_metrics.append(metrics)
        all_group_metrics.append(groups)

    if not all_predictions:
        failure_path = output_directory / "benchmark_failures.json"
        failure_path.write_text(json.dumps(failures, indent=2), encoding="utf-8")
        raise RuntimeError(
            "No model completed. Check network access or pre-download the models. "
            f"Details were written to {failure_path}."
        )

    predictions = pd.concat(all_predictions, ignore_index=True)
    metrics = pd.DataFrame(all_metrics)
    group_metrics = pd.concat(all_group_metrics, ignore_index=True)
    errors = error_frame(predictions)
    predictions.to_csv(output_directory / "benchmark_predictions.csv", index=False)
    metrics.to_csv(output_directory / "benchmark_results.csv", index=False)
    group_metrics.to_csv(output_directory / "benchmark_group_metrics.csv", index=False)
    errors.to_csv(output_directory / "benchmark_errors.csv", index=False)
    if failures:
        (output_directory / "benchmark_failures.json").write_text(
            json.dumps(failures, indent=2), encoding="utf-8"
        )
    return predictions, metrics, group_metrics, errors


def parse_args() -> argparse.Namespace:
    ml_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description="Benchmark multilingual category embeddings.")
    parser.add_argument(
        "--dataset",
        type=Path,
        default=ml_root / "data" / "categorization_benchmark.csv",
    )
    parser.add_argument("--output-dir", type=Path, default=ml_root / "data")
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--device", default=None, help="Optional sentence-transformers device, such as cpu or cuda.")
    parser.add_argument("--models", nargs="+", default=MODEL_NAMES)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    _, metrics, group_metrics, errors = run_benchmark(
        args.dataset,
        args.output_dir,
        model_names=args.models,
        batch_size=args.batch_size,
        device=args.device,
    )
    print("\nModel comparison")
    print(metrics.to_string(index=False))
    print("\nAccuracy breakdown")
    print(group_metrics.to_string(index=False))
    print(f"\nMisclassified examples: {len(errors)}")


if __name__ == "__main__":
    main()
