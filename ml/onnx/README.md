# SpendWise E5 ONNX feasibility workspace

This directory contains reproducible export and benchmarking code for
`intfloat/multilingual-e5-small`. Generated model and tokenizer artifacts are
written to the ignored `artifacts/` directory.

From the repository root, with the ML virtual environment active:

```powershell
python ml\onnx\export_e5.py
python ml\onnx\benchmark_e5_onnx.py
```

The ONNX graph accepts `input_ids` and `attention_mask` as `int64` tensors and
returns a normalized 384-dimensional `sentence_embedding`. It includes
attention-mask-aware mean pooling and L2 normalization. Receipt items require
the `query: ` prefix; category descriptions and clean prototype examples
require the `passage: ` prefix.

## Deployment contract

- Tokenizer: XLM-RoBERTa SentencePiece behavior represented by
  `artifacts/tokenizer/tokenizer.json` and `tokenizer_config.json`.
- Special token IDs: BOS `0`, PAD `1`, EOS `2`, UNK `3`, MASK `250001`.
- Padding and truncation are on the right. Pad only to the longest sequence in
  the current batch.
- Model inputs: `input_ids` and `attention_mask`, both `int64`, shaped
  `[batch, sequence]`.
- Model output: `sentence_embedding`, `float32`, shaped `[batch, 384]`.
- Pooling: attention-mask-aware mean over token embeddings, including E5 prefix
  tokens.
- Normalization: L2 normalization over the 384 embedding components is included
  in the ONNX graph.
- Model graph supports dynamic batches and sequence lengths. The source model
  limit is 512; the benchmark recommends a deployment limit of 64 for the
  currently measured SpendWise inputs.

The machine-readable version is in `deployment_manifest.json`.

## Measured Windows CPU results

| Runtime | Top-1 | Top-3 | OCR noise | Warm single item | Warm batch-32 per item |
| --- | ---: | ---: | ---: | ---: | ---: |
| PyTorch | 73.29% | 87.67% | 46.15% | 20.87 ms | 4.55 ms |
| ONNX FP32 | 73.29% | 87.67% | 46.15% | 7.05 ms | 3.67 ms |
| ONNX dynamic INT8 | 67.81% | 87.67% | 53.85% | 4.68 ms | 2.84 ms |

FP32 ONNX reproduces every V2 classification metric and has a worst measured
embedding cosine similarity of effectively 1.0 against PyTorch. Dynamic INT8
has mean embedding cosine similarity 0.990768 and worst similarity 0.986418.
Its 5.48 percentage-point overall Top-1 regression is material despite its
OCR-noise improvement.

At `score_gap >= 0.01`, FP32 accepts 63/146 suggestions at 95.24% accuracy.
INT8 accepts 61/146 at 96.72% accuracy. The high-confidence subset remains
strong, but threshold behavior alone does not offset INT8's lower unfiltered
categorization accuracy.

## Artifact footprint

| Variant | Model | Tokenizer | Estimated total with manifest |
| --- | ---: | ---: | ---: |
| FP32 | 448.48 MiB | 16.29 MiB | 464.78 MiB |
| Dynamic INT8 | 112.66 MiB | 16.29 MiB | 128.95 MiB |

INT8 reduces model size by 74.88%. Based on the current regression, it should
not be the default Android prototype solely for that reduction. FP32 confirms
pipeline feasibility but its footprint is large for a mobile application.
Further quantization strategies or a smaller model should be evaluated before
choosing the production artifact.

## Sequence-length result

The longest measured prefixed input is 52 tokens. A 32-token limit truncates all
12 bilingual category descriptions and reduces overall, Top-3, and OCR-noise
accuracy. Limits 64 and 128 produce the same results as 512 with no truncation,
so 64 is the smallest safe limit supported by this dataset.
