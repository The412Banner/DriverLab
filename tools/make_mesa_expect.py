#!/usr/bin/env python3
"""Builds DriverLab's table of results Mesa's own Turnip testing expects.

Usage: make_mesa_expect.py MESA_COMMIT OUT.tsv
Reads src/freedreno/ci/freedreno-<gpu>-{fails,flakes,skips}.txt at that Mesa commit
(gitlab.freedesktop.org) and keeps the Vulkan CTS lines (dEQP-VK.*). Output lines:
  list<TAB>mode<TAB>kind<TAB>pattern<TAB>status
list  = the file's GPU part (a618, a660, a6xx, a702, a750, ...)
mode  = "" or the prefix Mesa CI uses for a job with a forced setting (gmem, sysmem, ...)
kind  = fail (exact name + expected status), flake (regex), skip (regex)
The lists are MIT licensed (Mesa).
"""
import json
import re
import sys
import urllib.request

API = "https://gitlab.freedesktop.org/api/v4/projects/mesa%2Fmesa/repository/tree?path=src/freedreno/ci&per_page=100&ref="
RAW = "https://gitlab.freedesktop.org/mesa/mesa/-/raw/{commit}/src/freedreno/ci/{name}"
NAME = re.compile(r"^freedreno-(a[0-9x]+)-(fails|flakes|skips)\.txt$")
LINE = re.compile(r"^(?:([a-z0-9]+)-)?(dEQP-VK\..*)$")


def get(url):
    with urllib.request.urlopen(url, timeout=60) as r:
        return r.read().decode("utf-8")


def main():
    commit, out = sys.argv[1], sys.argv[2]
    names = sorted(e["name"] for e in json.loads(get(API + commit)) if NAME.match(e["name"]))
    rows = []
    for name in names:
        gpu, kind = NAME.match(name).groups()
        kind = kind[:-1]  # fails -> fail
        for raw in get(RAW.format(commit=commit, name=name)).splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            status = ""
            if kind == "fail":
                if "," not in line:
                    continue
                line, status = line.rsplit(",", 1)
            m = LINE.match(line)
            if not m:
                continue
            mode, pattern = m.group(1) or "", m.group(2)
            rows.append((gpu, mode, kind, pattern, status.strip()))
    with open(out, "w") as f:
        f.write(f"# Mesa {commit} src/freedreno/ci: list, mode, kind, pattern, status\n")
        for r in rows:
            f.write("\t".join(r) + "\n")
    lists = sorted({r[0] for r in rows})
    print(f"{len(rows)} lines from {len(names)} files; lists: {' '.join(lists)}")


if __name__ == "__main__":
    main()
