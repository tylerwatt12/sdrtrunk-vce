# Audio enhancement model bundles

`export_audio_enhancement_model.py` exports trusted PyTorch checkpoints into a data-only ZIP for the receiver's Java inference engine. Python, PyTorch and NumPy are export dependencies; the receiver does not require them. No trained weights or recordings are included in the repository.

Example with caller-supplied paths:

```sh
python tools/export_audio_enhancement_model.py \
  --model-id example-v1 --output model.zip \
  --imbe-8k imbe-8k.pt --imbe-bwe imbe-bwe.pt --imbe-calibration imbe-calibration.json \
  --ambe-8k ambe-8k.pt --ambe-bwe ambe-bwe.pt --ambe-calibration ambe-calibration.json
```

The exporter accepts the version-one `SpectralEnhancer` (`model_state`) and selected BWE (`generator_state`) checkpoint contracts. It checks both architectures, float32 tensor dimensions, finiteness, the fixed excitation mask and FIR, codec identity, and the BWE checkpoint's frozen-base hash. Calibration JSON must identify the codec (`imbe` or `ambe`) and its `fixed_decoder_gain`. Source hashes and checkpoint hashes are exported; training file paths and dataset metadata are omitted. A bypass BWE checkpoint is rejected. Export uses `torch.load(..., weights_only=True)` and should only be run against trusted checkpoints.

The Java API is `AudioEnhancementModelBundle.load(Path)` or `load(byte[])`, then `enhance(raw8, Codec.IMBE)` / `enhance(raw8, Codec.AMBE_PLUS_2)`. Instances and their metadata are immutable; each call creates its own histories and FFT workspaces. Input is finite decoder-level mono 8 kHz audio, with absolute sample values at most16 and length at most960,000 samples (120 seconds). A bundle may lower its input cap. Output is mono 16 kHz with exactly twice the input sample count. Processing applies the fixed training gain to the input and divides output by that same gain. It applies no round-trip alignment delay, crop, independent level normalization, headroom gain or clipping. Output can exceed full scale; presentation/encoding must handle headroom separately.

Bundle schema1 has `format: sdrtrunk-audio-enhancement`, architecture `spectral48-highband48-v1`, an ASCII model identifier, input/output rates, maximum input count, source hashes and codec records. Each codec record declares a bounded calibration gain, checkpoint hashes, weight-entry hash/length, and every tensor shape/byte offset. Payloads are contiguous little-endian float32. The only accepted ZIP entries are `manifest.json`, `weights/imbe.f32le` and `weights/ambe_plus_2.f32le`. Compressed and total expanded bytes are limited to4 MiB, manifest size to64 KiB, and every tensor value to a finite magnitude at most16. The loader checks declared hashes, shapes, offsets, canonical windows/FIR/mask, and duplicate or extra ZIP entries before publishing the model. It never extracts paths or executes bundled code. The receiver model-selection service requires both codec records.

Version-one inference runs the frozen 8 kHz spectral postfilter once, then bandlimited upsampling plus bounded harmonic/noise highband synthesis. Both causal CNNs retain their complete histories from call sample0. Noise indexing also starts at0 per call. Analysis, overlap-add and centered FIR behavior matches whole-call training; independent chunks are not equivalent. This API belongs on a bounded completed-call worker, not on a decoder callback. A future streaming engine requires explicit CNN, overlap-add, FIR and excitation-index state plus separate parity and latency validation.

Focused synthetic loader/inference tests include silence, local near-silence, identity postfilter, state reset/concurrency, corrupt digest/shape, unsupported schema, finite values and input limits. Optional private whole-call reference tests run when `SDRTRUNK_ML_REFERENCE_MANIFEST` and `SDRTRUNK_ML_REFERENCE_BUNDLE` are supplied. They pin input/reference/checkpoint hashes and require maximum sample error below1e-4 and relative RMS error below1e-4. No private fixtures are committed.
