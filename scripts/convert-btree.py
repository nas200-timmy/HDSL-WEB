#!/usr/bin/env python3
"""Convert cubiomes biome tree C headers into compact resource files."""
import re
import sys
from pathlib import Path


def convert(header: Path, out: Path) -> None:
    text = header.read_text()

    order_match = re.search(r"enum\s*\{\s*(\w+)_order\s*=\s*(\d+)\s*\}", text)
    if not order_match:
        raise ValueError(f"order not found in {header}")
    order = int(order_match.group(2))

    steps_match = re.search(r"static const uint32_t \w+_steps\[\]\s*=\s*\{([^}]+)\}", text)
    if not steps_match:
        raise ValueError(f"steps not found in {header}")
    steps = [int(x.strip()) for x in steps_match.group(1).split(",") if x.strip()]

    param_match = re.search(r"static const int32_t \w+_param\[\]\[2\]\s*=\s*\{(.*?)\};", text, re.S)
    if not param_match:
        raise ValueError(f"param not found in {header}")
    param_text = re.sub(r"//.*", "", param_match.group(1))
    params = []
    for pair in re.findall(r"\{\s*(-?\d+)\s*,\s*(-?\d+)\s*\}", param_text):
        params.append((int(pair[0]), int(pair[1])))

    nodes_match = re.search(r"static const uint64_t \w+_nodes\[\]\s*=\s*\{(.*?)\};", text, re.S)
    if not nodes_match:
        raise ValueError(f"nodes not found in {header}")
    nodes_text = re.sub(r"//.*", "", nodes_match.group(1))
    nodes = []
    for token in re.findall(r"0x[0-9a-fA-F]+", nodes_text):
        nodes.append(int(token, 16))

    lines = [
        f"order={order}",
        "steps=" + ",".join(str(s) for s in steps),
        "params=" + ";".join(f"{a},{b}" for a, b in params),
        "nodes=" + ",".join(f"{n:016x}" for n in nodes),
    ]
    out.write_text("\n".join(lines) + "\n")
    print(f"wrote {out} ({len(params)} params, {len(nodes)} nodes)")


def main() -> None:
    if len(sys.argv) != 3:
        print("usage: convert-btree.py <header> <out.txt>", file=sys.stderr)
        sys.exit(1)
    convert(Path(sys.argv[1]), Path(sys.argv[2]))


if __name__ == "__main__":
    main()
