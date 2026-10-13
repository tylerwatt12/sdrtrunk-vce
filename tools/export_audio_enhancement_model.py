#!/usr/bin/env python3
"""Export trusted PyTorch checkpoints as a bounded, data-only Java model bundle.

Python/PyTorch are needed only for this export, never by the receiver. The ZIP
contains no Python, pickle, class files, training data or host-specific paths.
"""
import argparse
import hashlib
import io
import json
import math
import os
from pathlib import Path
import re
import tempfile
import zipfile

import numpy as np
import torch

LOW_CONFIG = {"n_fft": 256, "hop": 128, "hidden": 48, "dilations": [1, 2, 4, 8],
              "min_gain": .25, "max_gain": 2., "silence_rms": 1e-5}
HIGH_CONFIG = {"hidden": 48, "dilations": [1, 2, 4, 8], "n_fft": 512, "hop": 256,
               "high_min_hz": 4250, "high_max_hz": 7500,
               "max_high_rms_ratio": .35, "silence_rms": 1e-5,
               "noise_seed": 1729, "bandpass_taps": 257}
MAX_BYTES = 4 * 1024 * 1024


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def shapes():
    expected = {}
    for prefix in ("low", "high"):
        expected[prefix + ".input.weight"] = [48, 129, 1]
        expected[prefix + ".input.bias"] = [48]
        for block in range(4):
            base = f"{prefix}.blocks.{block}"
            expected[base + ".conv.weight"] = [48, 48, 3]
            expected[base + ".conv.bias"] = [48]
            expected[base + ".mix.weight"] = [48, 48, 1]
            expected[base + ".mix.bias"] = [48]
        outputs = 129 if prefix == "low" else 514
        expected[prefix + ".output.weight"] = [outputs, 48, 1]
        expected[prefix + ".output.bias"] = [outputs]
    expected["high.mask"] = [1, 257, 1]
    expected["high.bandpass"] = [257]
    expected["constants.hann256"] = [256]
    expected["constants.hann512"] = [512]
    expected["constants.upsample"] = [129]
    return expected


def fir(cutoff, taps):
    index = np.arange(taps) - (taps - 1) / 2
    values = 2 * cutoff / 16000 * np.sinc(2 * cutoff / 16000 * index) * np.kaiser(taps, 8.6)
    return torch.tensor(values, dtype=torch.float32)


def codec_record(name, low_path, bwe_path, calibration_path):
    low_sha, bwe_sha = sha(low_path), sha(bwe_path)
    # Export accepts trusted files only; weights_only prevents arbitrary pickle globals.
    low = torch.load(low_path, map_location="cpu", weights_only=True)
    high = torch.load(bwe_path, map_location="cpu", weights_only=True)
    if low["config"]["model"] != LOW_CONFIG or low["config"]["sample_rate"] != 8000:
        raise ValueError("Unsupported 8 kHz architecture")
    if high["config"]["model"] != HIGH_CONFIG or high["config"]["sample_rate"] != 8000 or high["config"]["output_sample_rate"] != 16000:
        raise ValueError("Unsupported BWE architecture")
    if high.get("bypass", False):
        raise ValueError("A bypass checkpoint is not a full-BWE model")
    source_hashes = high["config"]["sources"]
    if not source_hashes or any(not re.fullmatch(r"[0-9a-f]{64}", value) for value in source_hashes.values()):
        raise ValueError("Invalid source provenance")
    if high["config"]["pretrained"]["checkpoints"]["8k"] != low_sha:
        raise ValueError("BWE checkpoint does not identify this frozen 8 kHz model")
    calibration = json.loads(Path(calibration_path).read_text(encoding="utf-8"))
    expected_codec = "imbe" if name == "IMBE" else "ambe"
    if calibration.get("codec") != expected_codec or high["config"]["codec"] != expected_codec:
        raise ValueError("Codec provenance mismatch")
    gain = float(np.float32(calibration["fixed_decoder_gain"]))
    if not math.isfinite(gain) or not .05 <= gain <= 4:
        raise ValueError("Invalid fixed training gain")
    low_state, high_state = low["model_state"], high["generator_state"]
    # Avoid exporting an unnoticed changed/unused tensor or a bundled frozen model twice.
    expected = shapes()
    if set(low_state) != {key[4:] for key in expected if key.startswith("low.")}:
        raise ValueError("Unexpected 8 kHz state tensors")
    if set(high_state) != {key[5:] for key in expected if key.startswith("high.")}:
        raise ValueError("Unexpected highband state tensors")
    tensors = {"low." + key: value for key, value in low_state.items()}
    tensors.update({"high." + key: value for key, value in high_state.items()})
    tensors["constants.hann256"] = torch.hann_window(256)
    tensors["constants.hann512"] = torch.hann_window(512)
    coefficients = fir(3900, 129)
    tensors["constants.upsample"] = coefficients / coefficients.sum() * 2
    mask = ((torch.fft.rfftfreq(512, 1 / 16000) >= 4250) &
            (torch.fft.rfftfreq(512, 1 / 16000) <= 7500)).float().view(1, 257, 1)
    if not torch.equal(tensors["high.mask"], mask) or not torch.equal(tensors["high.bandpass"], fir(7500, 257) - fir(4250, 257)):
        raise ValueError("Unexpected excitation mask or bandpass filter")
    blob = bytearray()
    table = {}
    for key, shape in expected.items():
        tensor = tensors[key].detach().cpu().contiguous()
        if list(tensor.shape) != shape or tensor.dtype != torch.float32 or not torch.isfinite(tensor).all() or tensor.abs().max() > 16:
            raise ValueError("Invalid model tensor: " + key)
        table[key] = {"shape": shape, "offset_bytes": len(blob)}
        blob.extend(tensor.numpy().astype("<f4", copy=False).tobytes())
    # Recheck immutable source files after deserialization/export.
    if sha(low_path) != low_sha or sha(bwe_path) != bwe_sha:
        raise ValueError("Checkpoint changed during export")
    entry = "weights/" + name.lower() + ".f32le"
    record = {"input_gain": gain, "enhancer_checkpoint_sha256": low_sha,
              "bwe_checkpoint_sha256": bwe_sha, "weights_entry": entry,
              "weights_sha256": hashlib.sha256(blob).hexdigest(),
              "weights_bytes": len(blob), "tensors": table}
    return record, bytes(blob), source_hashes


def export(args):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}", args.model_id):
        raise ValueError("Invalid model identifier")
    if not 1 <= args.maximum_input_samples <= 960000:
        raise ValueError("Invalid maximum input sample count")
    records, blobs, source_hashes = {}, {}, None
    for name, prefix in (("IMBE", "imbe"), ("AMBE_PLUS_2", "ambe")):
        paths = [getattr(args, prefix + suffix) for suffix in ("_8k", "_bwe", "_calibration")]
        if not any(paths):
            continue
        if not all(paths):
            raise ValueError("Supply all three checkpoint/calibration paths for " + prefix)
        record, blob, sources = codec_record(name, *paths)
        if source_hashes is not None and source_hashes != sources:
            raise ValueError("Codec model source hashes disagree")
        records[name] = record
        blobs[record["weights_entry"]] = blob
        source_hashes = sources
    if not records:
        raise ValueError("At least one codec is required")
    manifest = {"format": "sdrtrunk-audio-enhancement", "schema_version": 1,
                "model_id": args.model_id, "architecture": "spectral48-highband48-v1",
                "input_sample_rate": 8000, "output_sample_rate": 16000,
                "maximum_input_samples": args.maximum_input_samples,
                "source_sha256": source_hashes, "codecs": records}
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode("utf-8")
    if len(manifest_bytes) > 65536 or len(manifest_bytes) + sum(map(len, blobs.values())) > MAX_BYTES:
        raise ValueError("Expanded bundle exceeds size limit")
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        for name, payload in {"manifest.json": manifest_bytes, **blobs}.items():
            info = zipfile.ZipInfo(name, date_time=(2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o600 << 16
            archive.writestr(info, payload)
    payload = output.getvalue()
    if len(payload) > MAX_BYTES:
        raise ValueError("ZIP bundle exceeds size limit")
    destination = Path(args.output)
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(prefix=destination.name + ".", suffix=".tmp", dir=destination.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, destination)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)
    print(json.dumps({"model_id": args.model_id, "sha256": sha(destination),
                      "zip_bytes": len(payload), "codecs": list(records)}, sort_keys=True))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--maximum-input-samples", type=int, default=960000)
    for codec in ("ambe", "imbe"):
        parser.add_argument("--" + codec + "-8k")
        parser.add_argument("--" + codec + "-bwe")
        parser.add_argument("--" + codec + "-calibration")
    args = parser.parse_args()
    torch.set_num_threads(2)
    torch.set_num_interop_threads(1)
    export(args)


if __name__ == "__main__":
    main()
