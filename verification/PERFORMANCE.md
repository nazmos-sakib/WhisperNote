# Pixel 6a performance verification — 2026-09-16

Same 11-second JFK sample, explicit English, four native CPU threads. Two runs per configuration. Timed sections exclude model downloads and PCM decoding; total includes model/context loading, inference, and cleanup. Models are freshly loaded on each run, with filesystem caches potentially warm.

| Build | Model | Mean load | Mean inference | Mean total |
| --- | --- | ---: | ---: | ---: |
| unoptimized | tiny | 0.592 s | 70.881 s | 71.472 s |
| optimized | tiny | 0.189 s | 4.886 s | 5.074 s |
| optimized | tiny-q5_1 | 0.089 s | 4.164 s | 4.253 s |
| optimized | base-q5_1 | 0.117 s | 9.280 s | 9.396 s |
| optimized | small-q5_1 | 0.262 s | 32.815 s | 33.076 s |

Compiler optimization improved this short-sample Tiny result by **14.1×**. PCM decoding took 0.307 s before and 0.315 s after. Android reported thermal status 0 at the end of each measured run.

The engine code and inference settings were unchanged between baseline and optimized Tiny measurements; the native optimization flag was the performance change. Quantized rows use the same optimized engine with different weights.

These are functional short-sample measurements, not long-recording throughput or multilingual accuracy benchmarks. They should not be multiplied directly to predict 20-minute recordings. Larger models are slower; quantization may change recognition.

## Validation

- Four model configurations completed real inference with valid timestamps and a basic recognized-content assertion.
- Quantized Tiny interruption/resume passed with preserved text and edits.
- 14 JVM tests passed; Android lint reported no errors.
- The first combined device run had three UI failures because the keyguard was showing. Native benchmarks and resume passed. All three UI checks passed on rerun with a test-only visible window; see `ui-tests.txt`.

Raw files: `benchmark-before.txt`, `benchmark-after.txt`, `benchmark-results.json`. Native compiler validation: `python3 scripts/check_native_optimization.py`.

## Model provenance

Files are pinned to Hugging Face repository revision `5359861c739e955e79d9a303bcbc70fb988958b1`. Exact sizes and SHA-256 hashes come from its LFS metadata: https://huggingface.co/api/models/ggerganov/whisper.cpp/tree/5359861c739e955e79d9a303bcbc70fb988958b1?recursive=false&expand=false . Quantization documentation: https://github.com/ggml-org/whisper.cpp#quantization .
