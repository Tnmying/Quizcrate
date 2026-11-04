#!/usr/bin/env python3
"""Prepare an example-only CJK font for PDFBox.

Noto Serif SC is an OFL font, but its variable cmap maps some everyday characters to
Kangxi-radical/private compatibility code points. PDFBox then writes those compatibility
characters into ToUnicode, which makes the parser see e.g. U+2F00 instead of U+4E00. This
script removes only those compatibility mappings (the standard ideograph mappings remain) and
writes a derived font under work/. The original font and the derived font are not committed.
"""

from __future__ import annotations

import argparse
from pathlib import Path

from fontTools.ttLib import TTFont


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    font = TTFont(args.source)
    for table in font["cmap"].tables:
        table.cmap = {
            code: glyph
            for code, glyph in table.cmap.items()
            if not (0x2E80 <= code <= 0x2FFF or 0xF900 <= code <= 0xFAFF)
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    font.save(args.output)
    print(f"prepared {args.output}")


if __name__ == "__main__":
    main()
