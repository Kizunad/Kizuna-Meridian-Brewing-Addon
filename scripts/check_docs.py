from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]


def main() -> int:
    missing = []
    for path in ROOT.joinpath("docs", "wiki").glob("*.md"):
        text = path.read_text(encoding="utf-8")
        for target in re.findall(r"\]\(([^)#]+)(?:#[^)]+)?\)", text):
            if target.startswith(("http://", "https://", "mailto:")):
                continue
            candidate = (path.parent / target).resolve()
            if not candidate.exists():
                missing.append(f"{path}: {target}")
    if missing:
        print("缺少文档链接目标：")
        print("\n".join(missing))
        return 1
    print("文档链接检查通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
