from __future__ import annotations

import argparse
import json
import statistics
import sys
import time
from pathlib import Path

import pandas as pd
import psutil

ML_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ML_ROOT / "src"))

from benchmark import CATEGORY_DESCRIPTIONS
from e5_runtime import OnnxEncoder, PyTorchEncoder


def rss_mb() -> float:
    return psutil.Process().memory_info().rss / (1024 * 1024)


def main() -> None:
    root = ML_ROOT
    parser = argparse.ArgumentParser()
    parser.add_argument("--runtime", choices=["PYTORCH", "ONNX_FP32", "ONNX_INT8"], required=True)
    parser.add_argument("--repetitions", type=int, default=3)
    parser.add_argument("--max-length", type=int, default=128)
    args = parser.parse_args()

    artifacts = root / "onnx" / "artifacts"
    before_load = rss_mb()
    started = time.perf_counter()
    if args.runtime == "PYTORCH":
        encoder = PyTorchEncoder()
    else:
        filename = (
            "fp32/multilingual-e5-small.onnx"
            if args.runtime == "ONNX_FP32"
            else "int8/multilingual-e5-small-int8.onnx"
        )
        encoder = OnnxEncoder(
            artifacts / filename,
            artifacts / "tokenizer",
            args.runtime,
        )
    creation_seconds = time.perf_counter() - started
    after_load = rss_mb()

    data = pd.read_csv(root / "data" / "categorization_benchmark_v2.csv")
    item_texts = data.text.astype(str).tolist()
    category_texts = list(CATEGORY_DESCRIPTIONS.values())
    encoder.encode(item_texts[:8], categories=False, max_length=args.max_length, batch_size=8)

    single_times = []
    batch_times = []
    peak_rss = max(before_load, after_load, rss_mb())
    single_sample = item_texts[:32]
    for _ in range(args.repetitions):
        started = time.perf_counter()
        for text in single_sample:
            encoder.encode([text], categories=False, max_length=args.max_length, batch_size=1)
        single_times.append((time.perf_counter() - started) * 1000.0 / len(single_sample))
        peak_rss = max(peak_rss, rss_mb())

        started = time.perf_counter()
        encoder.encode(item_texts, categories=False, max_length=args.max_length, batch_size=32)
        encoder.encode(category_texts, categories=True, max_length=args.max_length, batch_size=32)
        count = len(item_texts) + len(category_texts)
        batch_times.append((time.perf_counter() - started) * 1000.0 / count)
        peak_rss = max(peak_rss, rss_mb())

    print(json.dumps({
        "runtime": args.runtime,
        "max_length": args.max_length,
        "creation_seconds": creation_seconds,
        "warm_single_ms_per_item": statistics.mean(single_times),
        "warm_single_stddev_ms": statistics.pstdev(single_times),
        "warm_batch32_ms_per_item": statistics.mean(batch_times),
        "warm_batch32_stddev_ms": statistics.pstdev(batch_times),
        "model_session_memory_delta_mb": max(0.0, after_load - before_load),
        "approximate_peak_process_rss_mb": peak_rss,
    }))


if __name__ == "__main__":
    main()
