#!/usr/bin/env python3
"""Build app/src/main/assets/oui.tsv.gz (MAC prefix -> vendor) from Wireshark's manuf list.

Each line is "<hex prefix>\t<vendor>"; the prefix has 6, 7 or 9 hex digits (/24, /28, /36).
Vendor names are shortened ("TP-Link Technologies Co.,Ltd." -> "TP-Link") to fit a widget line.

usage: scripts/gen-oui.py [path/to/manuf]     (downloads it when no path is given)
"""
import gzip
import os
import re
import sys
import urllib.request

URL = "https://www.wireshark.org/download/automated/data/manuf"
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "oui.tsv.gz")

SUFFIXES = re.compile(
    r"\b(co|corp|corporation|company|inc|incorporated|ltd|limited|llc|gmbh|ag|sa|s\.a|s\.p\.a|spa|bv|b\.v|"
    r"nv|oy|ab|as|a/s|kg|plc|pty|srl|s\.r\.l|technologies|technology|tech|electronics|international|"
    r"communications|systems|group|holdings?)\b\.?",
    re.I,
)


def short(name: str) -> str:
    n = name.split(",")[0]
    n = re.sub(r"\(.*?\)", "", n)
    prev = None
    while prev != n:  # strip trailing corporate words repeatedly
        prev = n
        n = re.sub(SUFFIXES.pattern + r"\s*$", "", n.strip(), flags=re.I).strip(" .,&-")
    return n or name.strip()


def main():
    src = open(sys.argv[1], encoding="utf-8").read() if len(sys.argv) > 1 else \
        urllib.request.urlopen(URL, timeout=60).read().decode("utf-8")
    rows = {}
    for line in src.splitlines():
        if not line or line.startswith("#"):
            continue
        cols = [c.strip() for c in line.split("\t")]
        if len(cols) < 2:
            continue
        prefix, _, bits = cols[0].partition("/")
        hexs = re.sub(r"[^0-9A-Fa-f]", "", prefix).upper()
        n = int(bits or 24) // 4
        if len(hexs) < n or n not in (6, 7, 9):
            continue
        rows[hexs[:n]] = short(cols[2] if len(cols) > 2 and cols[2] else cols[1])
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with gzip.open(OUT, "wt", encoding="utf-8", compresslevel=9) as f:
        for k in sorted(rows):
            f.write(f"{k}\t{rows[k]}\n")
    print(f"{len(rows)} prefixes -> {os.path.relpath(OUT)} ({os.path.getsize(OUT) // 1024} KB)")


if __name__ == "__main__":
    main()
