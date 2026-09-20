# SpendWise mobile model research

This workspace compares ONNX quantization and compact local embedding models on
the unchanged 146-row SpendWise V2 dataset. It does not modify the Android app.

## Reproduce

The compact source models must already be available in the Hugging Face cache.
Generated ONNX files and tokenizer copies are written below the ignored
`ml/mobile/artifacts/` directory.

```powershell
python ml\mobile\quantize_e5_variants.py
python ml\mobile\export_compact_models.py
python ml\mobile\benchmark_mobile_candidates.py
```

Every neural configuration uses the V2 `HYBRID_PROTOTYPE` method with 50%
description and 50% clean-example centroid. The evaluated canonical product and
all of its OCR/language variants are excluded from its prototypes.

## E5 quantization

Static calibration uses all 146 benchmark item texts plus the 12 category
descriptions. The calibration inputs include English, Arabic, mixed-language,
and OCR-noisy text. Inputs use the original E5 `query: ` and `passage: `
prefixes and a 64-token calibration limit.

Tested configurations include:

- Dynamic QInt8 weights, per-tensor and per-channel.
- Dynamic per-channel quantization restricted to MatMul operations.
- Static QDQ QUInt8/QInt8 and QInt8/QInt8, per-tensor and per-channel.
- Static QDQ selective MatMul quantization.
- Static QOperator QUInt8/QInt8 per-channel quantization.

Dynamic per-channel quantization is the only full E5 configuration that nearly
preserves FP32 accuracy: 72.60% Top-1 versus 73.29%, with 96.55% accepted
accuracy at 39.73% coverage. Its 129.15 MiB deployment footprint remains above
the project's default-bundle limit. Static full-model configurations caused
large embedding drift and are rejected. Selective quantization retained large
FP32 embeddings, leaving artifacts around 404 MiB.

## Compact candidates

Two candidates were selected from their published model cards and then measured
locally:

- `alphaedge-ai/multilingual-e5-small-arb-32768`: Arabic-focused E5 vocabulary
  trimming to 32,768 tokens and 34.2M parameters.
  https://huggingface.co/alphaedge-ai/multilingual-e5-small-arb-32768
- `Ik45/fihris-embeddding-id-ar`: pruned multilingual MiniLM intended for
  Arabic, English, and Indonesian semantic retrieval.
  https://huggingface.co/Ik45/fihris-embeddding-id-ar

Both FP32 ONNX exports match their SentenceTransformer pipelines at effectively
1.0 cosine similarity on English, Arabic, and mixed validation inputs.

| Configuration | Assets | Top-1 | Top-3 | Arabic | Mixed | OCR noise | Accepted accuracy | Coverage | Warm single item |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| E5 ONNX FP32 | 464.77 MiB | 73.29% | 87.67% | 72.92% | 92.31% | 46.15% | 95.24% | 43.15% | 6.84 ms |
| E5 dynamic per-channel | 129.15 MiB | 72.60% | 87.67% | 72.92% | 92.31% | 42.31% | 96.55% | 39.73% | 4.67 ms |
| Arabic-trimmed E5 INT8 per-channel | **35.58 MiB** | 68.49% | 85.62% | 72.92% | 84.62% | 38.46% | **98.36%** | 41.78% | 5.10 ms |
| Fihris INT8 per-channel | 41.70 MiB | 66.44% | 83.56% | 68.75% | 76.92% | 34.62% | 90.63% | 43.84% | 4.03 ms |

The Arabic-trimmed E5 assets plus the app's existing approximate 15 MiB OCR
assets total about 50.58 MiB.

## Custom Books category

| Configuration | 0 examples | 1 example | 3 examples | 5 examples |
| --- | ---: | ---: | ---: | ---: |
| E5 FP32 | 25.00% | 100.00% | 100.00% | 100.00% |
| E5 dynamic per-channel | 25.00% | 98.61% | 100.00% | 100.00% |
| Arabic-trimmed E5 INT8 | 33.33% | 100.00% | 100.00% | 100.00% |
| Fihris INT8 | 25.00% | 75.00% | 81.25% | 83.33% |

## Lexical baseline and future cascade

The non-neural baseline normalizes Unicode, Arabic/Persian digits, punctuation,
whitespace, Arabic marks, and common OCR digit substitutions. It combines
category-description keywords, token overlap, clean-example similarity, and
`SequenceMatcher` fuzzy similarity while retaining canonical-ID exclusion.

- Overall Top-1: 30.14%.
- OCR-noise Top-1: 15.38%.
- At `score_gap >= 0.12`: 90.91% accepted accuracy, 15.07% coverage, two
  incorrect accepted suggestions.
- Python reference latency: 5.46 ms per item.

Using the lexical stage before Arabic-trimmed E5 INT8 gives 95.31% accepted
accuracy at 43.84% coverage. Thresholds were selected on this research set, so
this cascade estimate is exploratory. A user-learned exact-match stage cannot be
measured faithfully without user-history data and is left conceptually first in
the pipeline.

## Recommendation

For the first Android semantic prototype, use
`alphaedge-ai/multilingual-e5-small-arb-32768` exported to ONNX with dynamic
per-channel QInt8 weight quantization, ONNX Runtime default operation selection,
attention-mask-aware mean pooling, graph-level L2 normalization, required E5
prefixes, and a 128-token limit matching this benchmark.

Use `score_gap >= 0.01` as the prototype research threshold. It produced 98.36%
accepted accuracy at 41.78% coverage, but must be validated on more real receipt
data before becoming a production threshold.
