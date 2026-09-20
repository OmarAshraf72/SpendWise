# SpendWise multilingual categorization benchmark

This workspace evaluates multilingual sentence embeddings for matching receipt item text to SpendWise categories. It is an offline research tool and does not modify the Android application, export ONNX models, quantize models, or train a classifier.

## Dataset

`data/categorization_benchmark.csv` contains labeled English, Arabic, and mixed Arabic/English receipt items. It includes clean text and realistic OCR corruption such as `PANAD0L EXTRA`, `DettoI Floor C1eaner`, and `شامبو D0VE`.

Columns:

- `text`: receipt item text
- `category`: expected SpendWise category
- `language`: `en`, `ar`, or `mixed`
- `variant_type`: `clean`, `ocr_noise`, or `mixed_language`

## Models

The default benchmark compares:

- `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2`
- `intfloat/multilingual-e5-small`

The model list is centralized in `src/benchmark.py` and can be extended through code or the `--models` command-line argument. E5 inputs use `query:` for receipt items and `passage:` for category descriptions.

## Setup

From the repository root:

```powershell
python -m venv ml\.venv
ml\.venv\Scripts\Activate.ps1
python -m pip install -r ml\requirements.txt
```

On macOS or Linux, activate with `source ml/.venv/bin/activate`.

The first run downloads model weights from Hugging Face. Subsequent runs use the local model cache.

## Run from the command line

```powershell
python ml\src\benchmark.py
```

Optional examples:

```powershell
python ml\src\benchmark.py --device cpu --batch-size 16
python ml\src\benchmark.py --models sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2
```

Generated files are written under `ml/data/`:

- `benchmark_results.csv`: overall accuracy, timing, embedding dimension, confidence-gap statistics, and cached model size when available
- `benchmark_group_metrics.csv`: Top-1 and Top-3 accuracy by language, variant type, and category
- `benchmark_predictions.csv`: every prediction, Top-3 result, scores, and confidence gap
- `benchmark_errors.csv`: requested misclassification analysis
- `benchmark_failures.json`: model loading failures, when applicable

If downloads are blocked, rerun the same command on a machine with internet access or pre-download both model repositories into the Hugging Face cache.

## Notebook

Start Jupyter from the repository root:

```powershell
jupyter notebook ml\notebooks\categorization_benchmark.ipynb
```

The notebook shows category distribution, runs both models, compares aggregate and grouped metrics, displays errors and low-gap predictions, and checks the custom `Books` category.

## Matching method

Each category has one bilingual semantic description. The benchmark:

1. Embeds category descriptions and receipt items.
2. L2-normalizes both embedding matrices.
3. Computes cosine similarity through a matrix product.
4. Uses the highest score as Top-1 and retains the three highest scores as Top-3.
5. Records `score_gap = top1_score - top2_score` to study a future “No confident suggestion” threshold.

Scores are similarity values, not calibrated probabilities.

## User-created categories

`Books` is treated exactly like built-in categories and is added only by supplying its bilingual description. The embedding models are not retrained and no classifier output layer changes. The notebook reports Books-only accuracy and examples explicitly, demonstrating the same mechanism that can later support user-created category descriptions.

This benchmark does not yet select an Android model or change SpendWise runtime categorization.

## Baseline results

The benchmark was run on the included 146-example dataset using CPU inference and a warm local model cache:

| Model | Top-1 | Top-3 | OCR-noise Top-1 | Avg. item inference | Warm load | Dimension | Cached size |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| paraphrase-multilingual-MiniLM-L12-v2 | 63.70% | 77.40% | 23.08% | 3.09 ms | 7.36 s | 384 | about 480 MB |
| multilingual-e5-small | 68.49% | 82.88% | 42.31% | 3.71 ms | 11.56 s | 384 | about 493 MB |

These timings are machine-specific and are intended for relative comparison. E5 produced the stronger overall and OCR-noise results, while MiniLM was substantially better on the Books examples in this dataset: 66.67% versus 25.00% Top-1. This confirms that Books can participate through a category description alone, while also showing that description quality and model choice materially affect a custom category's accuracy.

## V2 prototype benchmark

V2 preserves the original dataset, scripts, and result files. `data/categorization_benchmark_v2.csv` adds a `canonical_id` that groups clean, multilingual, and OCR-corrupted forms of the same product. The included dataset has 146 rows representing 58 canonical products.

Generate the canonical dataset and run all six model/strategy configurations:

```powershell
python ml\src\prepare_v2_dataset.py
python ml\src\benchmark_v2.py --device cpu
```

V2 compares three category representations:

- `DESCRIPTION_ONLY`: one bilingual category description.
- `EXAMPLE_CENTROID`: the normalized centroid of clean confirmed examples.
- `HYBRID_PROTOTYPE`: an equal-weight normalized combination of the description and clean-example centroid.

For centroid and hybrid evaluation, the evaluated product's `canonical_id` is excluded from the prototype. This excludes its clean text as well as every OCR or language variant derived from it. Clean references are first averaged per canonical product so products with several variants do not receive extra weight.

Generated V2 files:

- `benchmark_v2_results.csv`: aggregate, language, variant, category, and timing metrics for all six configurations.
- `benchmark_v2_predictions.csv`: row-level scores, gaps, predictions, and correctness.
- `benchmark_v2_errors.csv`: incorrect Top-1 predictions.
- `threshold_analysis.csv`: gap-only and combined minimum-score/minimum-gap abstention results.
- `custom_category_simulation.csv`: Books accuracy with 0, 1, 3, and 5 confirmed examples.

### Measured V2 results

| Model | Strategy | Top-1 | Top-3 | OCR-noise Top-1 | Avg. item inference |
| --- | --- | ---: | ---: | ---: | ---: |
| multilingual-e5-small | HYBRID_PROTOTYPE | **73.29%** | **87.67%** | **46.15%** | 3.68 ms |
| multilingual-e5-small | DESCRIPTION_ONLY | 68.49% | 82.88% | 42.31% | 3.68 ms |
| multilingual-e5-small | EXAMPLE_CENTROID | 48.63% | 71.92% | 19.23% | 3.68 ms |
| paraphrase-multilingual-MiniLM-L12-v2 | HYBRID_PROTOTYPE | 68.49% | 80.14% | 26.92% | 3.29 ms |
| paraphrase-multilingual-MiniLM-L12-v2 | DESCRIPTION_ONLY | 63.70% | 77.40% | 23.08% | 3.29 ms |
| paraphrase-multilingual-MiniLM-L12-v2 | EXAMPLE_CENTROID | 54.11% | 71.92% | 23.08% | 3.29 ms |

Pure example centroids are weak on this small and uneven reference set. The hybrid representation improves both models while retaining the semantic category description.

### Confidence and abstention

Scores remain cosine similarities rather than calibrated probabilities. On this dataset, E5 with the hybrid prototype and `score_gap >= 0.01` accepts 63 of 146 suggestions, reaches 43.15% coverage, and has 95.24% accuracy among accepted suggestions. E5 description-only with the same gap reaches 54.11% coverage at 92.41% accepted accuracy. These are benchmark candidates, not production thresholds; a larger held-out set of real receipt OCR is needed before selecting a runtime policy.

The intended decision order is an exact user-learned mapping first, followed by a semantic prototype suggestion. The app should leave the category unselected when the semantic suggestion does not pass the chosen confidence policy.

### Books learning simulation

The simulation uses every eligible combination of confirmed Books canonical products and excludes the evaluated product and all of its variants from its own prototype.

| Model | 0 examples | 1 example | 3 examples | 5 examples |
| --- | ---: | ---: | ---: | ---: |
| multilingual-e5-small | 25.00% | 100.00% | 100.00% | 100.00% |
| paraphrase-multilingual-MiniLM-L12-v2 | 66.67% | 76.39% | 81.67% | 83.33% |

The results support testing E5 with the hybrid prototype in the next device feasibility phase. Model conversion, quantization, Android integration, and production threshold selection remain outside this benchmark.

## ONNX device-feasibility benchmark

The follow-up feasibility workspace is under `ml/onnx/`. It exports the E5
transformer with attention-mask-aware mean pooling and L2 normalization included
in the graph, preserves the E5 query/passage prefixes and tokenizer contract,
and compares PyTorch, ONNX FP32, and ONNX dynamic INT8 on the complete V2
benchmark. See `ml/onnx/README.md` and `ml/onnx/deployment_manifest.json` for the
measured results and deployment contract. No Android application files are
modified by this workflow.

## Mobile candidate research

The `ml/mobile/` workspace extends the feasibility study with alternative E5
quantization, compact Arabic/English models, custom-category regression, a
non-neural lexical baseline, and a simulated lexical-to-semantic cascade. The
recommended first prototype candidate is the Arabic-trimmed E5 model with
dynamic per-channel INT8 quantization: 35.58 MiB of model and tokenizer assets,
68.49% overall Top-1, 72.92% Arabic Top-1, and 98.36% accepted accuracy at
41.78% coverage using `score_gap >= 0.01`. See `ml/mobile/README.md` and the
`mobile_*.csv` reports under `ml/data/` for the complete comparison.
