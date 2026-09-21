from __future__ import annotations

import csv
import json
import sys
from pathlib import Path

import numpy as np


ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT / "src"))
sys.path.insert(0, str(ML_ROOT / "mobile"))

from benchmark import CATEGORY_DESCRIPTIONS
from benchmark_mobile_candidates import GenericOnnxEncoder


MODEL_ROOT = ML_ROOT / "mobile" / "artifacts" / "compact_models" / "arabic_trimmed_e5"
MODEL_PATH = MODEL_ROOT / "model-int8-per-channel.onnx"
TOKENIZER_ROOT = MODEL_ROOT / "tokenizer"
OUTPUT_CSV = ML_ROOT / "data" / "android_batch_stability.csv"
OUTPUT_SUMMARY = ML_ROOT / "data" / "android_batch_stability_summary.json"
MINIMUM_GAP = 0.01
TARGETS = [
    "Folic Acid 600 MCG",
    "Panadol Extra",
    "IMMULANT PLUS 20 CAP",
    "Dettol Floor Cleaner",
    "Chicken Breast",
    "طماطم",
    "شامبو دوف",
    "Atomic Habits",
    "Clean Code",
]
FILLERS = [
    "Sparkling Water 1L",
    "USB-C Cable",
    "Taxi Ride",
    "Laundry Detergent",
    "Cinema Ticket",
    "Electricity Bill",
    "Orange Juice",
    "Notebook A5",
]


def independent_embeddings(encoder: GenericOnnxEncoder, texts: list[str], *, categories: bool) -> np.ndarray:
    return np.vstack([encoder.encode([text], categories=categories)[0] for text in texts])


def rank(embedding: np.ndarray, prototypes: np.ndarray, category_names: list[str]) -> dict[str, object]:
    scores = embedding @ prototypes.T
    order = np.argsort(-scores)
    top1_score = float(scores[order[0]])
    top2_score = float(scores[order[1]])
    gap = top1_score - top2_score
    return {
        "top1_category": category_names[order[0]],
        "top1_score": top1_score,
        "top2_category": category_names[order[1]],
        "top2_score": top2_score,
        "score_gap": gap,
        "accepted": gap >= MINIMUM_GAP,
    }


def compositions(target: str, target_index: int) -> list[tuple[str, list[str]]]:
    others = [value for value in TARGETS if value != target]
    rotated_fillers = FILLERS[target_index % len(FILLERS) :] + FILLERS[: target_index % len(FILLERS)]
    return [
        ("single", [target]),
        ("pair_target", [target, others[0]]),
        ("pair_filler", [target, rotated_fillers[0]]),
        ("four_targets", [target, *others[:3]]),
        ("four_fillers", [target, *rotated_fillers[:3]]),
        ("nine_targets", TARGETS.copy()),
        ("nine_fillers", [target, *rotated_fillers]),
    ]


def main() -> None:
    encoder = GenericOnnxEncoder(
        MODEL_PATH,
        TOKENIZER_ROOT,
        query_prefix="query: ",
        passage_prefix="passage: ",
    )
    category_names = list(CATEGORY_DESCRIPTIONS)
    # Stable prototypes isolate query batch drift from category batch drift.
    prototypes = independent_embeddings(
        encoder,
        list(CATEGORY_DESCRIPTIONS.values()),
        categories=True,
    )
    references = {
        text: independent_embeddings(encoder, [text], categories=False)[0]
        for text in TARGETS
    }
    reference_rankings = {
        text: rank(embedding, prototypes, category_names)
        for text, embedding in references.items()
    }

    rows: list[dict[str, object]] = []
    for target_index, target in enumerate(TARGETS):
        reference = references[target]
        reference_ranking = reference_rankings[target]
        for composition_name, texts in compositions(target, target_index):
            embeddings = encoder.encode(texts, categories=False)
            embedding = embeddings[texts.index(target)]
            ranking = rank(embedding, prototypes, category_names)
            cosine = float(np.dot(reference, embedding) / (np.linalg.norm(reference) * np.linalg.norm(embedding)))
            top1_changed = ranking["top1_category"] != reference_ranking["top1_category"]
            top2_changed = ranking["top2_category"] != reference_ranking["top2_category"]
            threshold_changed = ranking["accepted"] != reference_ranking["accepted"]
            numerical_changed = any(
                abs(float(ranking[key]) - float(reference_ranking[key])) > 1e-8
                for key in ("top1_score", "top2_score", "score_gap")
            )
            rows.append(
                {
                    "target": target,
                    "composition": composition_name,
                    "batch_size": len(texts),
                    "batch_texts": json.dumps(texts, ensure_ascii=False),
                    "embedding_cosine_vs_single": cosine,
                    **ranking,
                    "top1_changed": top1_changed,
                    "top2_changed": top2_changed,
                    "threshold_changed": threshold_changed,
                    "only_numerical_values_changed": numerical_changed
                    and not top1_changed
                    and not top2_changed
                    and not threshold_changed,
                }
            )

    OUTPUT_CSV.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT_CSV.open("w", newline="", encoding="utf-8-sig") as destination:
        writer = csv.DictWriter(destination, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)

    non_single = [row for row in rows if row["composition"] != "single"]
    summary = {
        "model": "alphaedge-ai/multilingual-e5-small-arb-32768",
        "quantization": "dynamic QInt8 per-channel",
        "minimum_score_gap": MINIMUM_GAP,
        "target_count": len(TARGETS),
        "case_count": len(rows),
        "non_single_case_count": len(non_single),
        "minimum_embedding_cosine_vs_single": min(float(row["embedding_cosine_vs_single"]) for row in non_single),
        "maximum_top1_score_drift": max(
            abs(float(row["top1_score"]) - float(reference_rankings[str(row["target"])]["top1_score"]))
            for row in non_single
        ),
        "maximum_top2_score_drift": max(
            abs(float(row["top2_score"]) - float(reference_rankings[str(row["target"])]["top2_score"]))
            for row in non_single
        ),
        "maximum_score_gap_drift": max(
            abs(float(row["score_gap"]) - float(reference_rankings[str(row["target"])]["score_gap"]))
            for row in non_single
        ),
        "top1_change_cases": sum(bool(row["top1_changed"]) for row in non_single),
        "top2_change_cases": sum(bool(row["top2_changed"]) for row in non_single),
        "threshold_change_cases": sum(bool(row["threshold_changed"]) for row in non_single),
        "targets_with_top1_change": sorted({str(row["target"]) for row in non_single if row["top1_changed"]}),
        "targets_with_top2_change": sorted({str(row["target"]) for row in non_single if row["top2_changed"]}),
        "targets_with_threshold_change": sorted({str(row["target"]) for row in non_single if row["threshold_changed"]}),
    }
    OUTPUT_SUMMARY.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=True, indent=2))
    print(f"Wrote {OUTPUT_CSV}")
    print(f"Wrote {OUTPUT_SUMMARY}")


if __name__ == "__main__":
    main()
