#!/usr/bin/env python3
"""Prepare an exact mixed PTQ1/PQ2 GGUF header and repack instructions."""

import json
import struct
from pathlib import Path


def main() -> None:
    root = Path(__file__).resolve().parents[2]
    work = root / "build/device-bonsai/hypotheses"
    manifest = json.loads((work / "pq2-repack-manifest.json").read_text())
    source = Path(manifest["input"])
    data_start = manifest["dataStart"]
    assert manifest["alignment"] == 32
    assert source.stat().st_size == 5_946_648_928
    with source.open("rb") as stream:
        header = bytearray(stream.read(data_start))
    assert header[:4] == b"GGUF"

    offset = 0
    selected = 0
    elements = 0
    records = bytearray()
    for tensor in manifest["tensors"]:
        convert = tensor["type"] == 143 and tensor["name"].endswith(".ffn_down.weight")
        size = tensor["newBytes"] if convert else tensor["oldBytes"]
        output_type = 142 if convert else tensor["type"]
        struct.pack_into("<IQ", header, tensor["typePosition"], output_type, offset)
        # 142: convert PTQ1 to PQ2; 144: copy an unchanged PTQ1 tensor.
        instruction_type = 142 if convert else 144 if tensor["type"] == 143 else tensor["type"]
        records += struct.pack("<QQQQ", data_start + tensor["oldOffset"], data_start + offset,
                               tensor["elements"], instruction_type)
        selected += convert
        elements += tensor["elements"] if convert else 0
        offset = (offset + size + 31) & ~31

    assert selected == 64 and elements == 5_704_253_440
    assert len(records) == 851 * 32
    total = data_start + offset
    assert total == 6_214_035_808
    (work / "mixed-ffn-down-header.bin").write_bytes(header)
    (work / "mixed-ffn-down-records.bin").write_bytes(records)
    (work / "mixed-ffn-down-spec.json").write_text(json.dumps({
        "input": str(source), "outputBytes": total, "convertedTensors": selected,
        "convertedWeights": elements, "extraBytes": total - source.stat().st_size,
        "selection": "all blk.*.ffn_down.weight PTQ1 tensors",
    }, indent=2) + "\n")
    print(f"{selected} tensors, {elements} weights, {total} output bytes")


if __name__ == "__main__":
    main()
