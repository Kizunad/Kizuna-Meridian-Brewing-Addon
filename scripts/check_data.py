from pathlib import Path
import json
import sys


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    failures = []
    for path in ROOT.rglob("*.json"):
        if "build" in path.parts or ".gradle" in path.parts:
            continue
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            failures.append(f"{path}: {exc}")
    if failures:
        print("数据 JSON 检查失败：")
        print("\n".join(failures))
        return 1
    print("数据 JSON 检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
