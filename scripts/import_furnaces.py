# /// script
# dependencies = ["nbtlib==2.0.4"]
# ///
"""从三份原版结构副本生成发布净图。用 uv run scripts/import_furnaces.py <副本目录>。"""

import argparse
import gzip
import io
from pathlib import Path

import nbtlib as nbt


STRUCTURES = Path(__file__).resolve().parents[1] / "fabric/src/main/resources/data/meridian_brewing/structure"
REPLACEMENTS = {
    "lv1": {
        "orange_concrete": "waxed_cut_copper",
        "shroomlight": "waxed_cut_copper",
    },
    "lv2": {
        "purple_concrete": "waxed_weathered_cut_copper",
        "pearlescent_froglight": "waxed_weathered_cut_copper",
        "sea_lantern": "magma_block",
    },
    "lv3-auto": {
        "lime_concrete": "polished_blackstone",
        "verdant_froglight": "polished_blackstone",
        "shroomlight": "magma_block",
    },
}


def import_furnace(source, tier):
    template = nbt.load(source)
    palette = nbt.List[nbt.Compound]()
    blocks = nbt.List[nbt.Compound]()
    for block in template["blocks"]:
        state = template["palette"][block["state"]]
        material = str(state["Name"]).removeprefix("minecraft:")
        if material in {"air", "bedrock"}:
            continue
        if material.endswith("_stained_glass") and (tier, material) != ("lv3-auto", "orange_stained_glass"):
            continue
        replacement = REPLACEMENTS[tier].get(material)
        if replacement:
            state = nbt.Compound({"Name": nbt.String(f"minecraft:{replacement}")})
        if state not in palette:
            palette.append(state)
        block["state"] = nbt.Int(palette.index(state))
        blocks.append(block)

    # 以实际保留方块的包围盒裁剪。朝向、几何属性和方块实体随原格保留。
    axes = list(zip(*(block["pos"] for block in blocks)))
    origin = [min(axis) for axis in axes]
    for block in blocks:
        block["pos"] = nbt.List[nbt.Int](p - base for p, base in zip(block["pos"], origin))
    template["size"] = nbt.List[nbt.Int](max(axis) - min(axis) + 1 for axis in axes)
    template["blocks"] = blocks
    template["palette"] = palette
    template["entities"] = nbt.List[nbt.Compound]()
    raw = io.BytesIO()
    template.write(raw)
    return gzip.compress(raw.getvalue(), compresslevel=9, mtime=0)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--check", action="store_true", help="只核对生成字节与发布净图")
    args = parser.parse_args()
    for tier in REPLACEMENTS:
        name = f"cauldron-v2-{tier}.nbt"
        source = args.source / name
        target = STRUCTURES / name
        if source.resolve() == target.resolve():
            parser.error("输入须为原件副本目录")
        data = import_furnace(source, tier)
        if args.check:
            if data != target.read_bytes():
                raise SystemExit(f"{name} 与重新生成的净图不同")
        else:
            target.write_bytes(data)
        print(name)
