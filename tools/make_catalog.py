#!/usr/bin/env python3
"""Builds DriverLab's compact Vulkan CTS test catalog.

Usage: make_catalog.py vk-default.txt.gz OUTDIR CTS_TAG [presets.tsv]

Input: the concatenated mustpass list (one dEQP-VK.* test per line, grouped
by top-level group as in external/vulkancts/mustpass/main/vk-default.txt).
Output in OUTDIR:
  index.tsv        "top<TAB>tests<TAB>nodes<TAB>file", first line "#cts<TAB>TAG<TAB>total"
  <top>.bin        one tree per top-level group (format below)
  presets.tsv      presets checked against the catalog (missing names dropped)

<top>.bin, little endian:
  "DLC1", u32 nStrings, nStrings x (u16 len, utf-8 bytes),
  u32 nNodes, then five int32 columns of nNodes each:
  name (string index), parent (-1 for the root), firstChild, childCount,
  tests (number of test cases at or under the node).
  Node 0 is the top-level group. Children of a node are contiguous and keep
  the mustpass order. A node with childCount 0 is a test case.
"""
import gzip
import io
import struct
import sys
from collections import deque

PREFIX = "dEQP-VK."


def build(top, names, outdir):
    root = {}
    for n in names:
        parts = n[len(PREFIX):].split(".")[1:]
        d = root
        for p in parts:
            d = d.setdefault(p, {})
    strings, sidx = [], {}

    def s(x):
        i = sidx.get(x)
        if i is None:
            i = sidx[x] = len(strings)
            strings.append(x)
        return i

    name, parent, first, count, tests = [s(top)], [-1], [0], [0], [0]
    dicts = [root]
    q = deque([0])
    while q:
        i = q.popleft()
        kids = dicts[i]
        first[i] = len(name)
        count[i] = len(kids)
        for k, v in kids.items():
            j = len(name)
            name.append(s(k))
            parent.append(i)
            first.append(0)
            count.append(0)
            tests.append(0)
            dicts.append(v)
            q.append(j)
        dicts[i] = None
    for i in range(len(name) - 1, -1, -1):
        if count[i] == 0:
            tests[i] = 1
        if parent[i] >= 0:
            tests[parent[i]] += tests[i]
    buf = io.BytesIO()
    buf.write(b"DLC1")
    buf.write(struct.pack("<I", len(strings)))
    for x in strings:
        b = x.encode()
        buf.write(struct.pack("<H", len(b)))
        buf.write(b)
    buf.write(struct.pack("<I", len(name)))
    for col in (name, parent, first, count, tests):
        buf.write(struct.pack("<%di" % len(col), *col))
    fname = top + ".bin"
    with open(f"{outdir}/{fname}", "wb") as f:
        f.write(buf.getvalue())
    return tests[0], len(name), fname


def main():
    src, outdir, tag = sys.argv[1], sys.argv[2], sys.argv[3]
    presets = sys.argv[4] if len(sys.argv) > 4 else None
    opener = gzip.open if src.endswith(".gz") else open
    rows, total, allnames = [], 0, set()
    cur, names = None, []
    seen_tops = set()

    def flush():
        nonlocal total
        if cur is None:
            return
        t, n, f = build(cur, names, outdir)
        rows.append((cur, t, n, f))
        total += t
        print(f"{cur}: {t} tests, {n} nodes", flush=True)

    with opener(src, "rt") as f:
        for line in f:
            line = line.strip()
            if not line.startswith(PREFIX):
                continue
            top = line[len(PREFIX):].split(".", 1)[0]
            if top != cur:
                if top in seen_tops:
                    sys.exit(f"top-level group {top} is not contiguous in the list")
                flush()
                seen_tops.add(top)
                cur, names = top, []
            names.append(line)
            if presets:
                allnames.add(line)
    flush()
    with open(f"{outdir}/index.tsv", "w") as f:
        f.write(f"#cts\t{tag}\t{total}\n")
        for r in rows:
            f.write("\t".join(map(str, r)) + "\n")
    print(f"total {total} tests in {len(rows)} groups")

    if presets:
        groups = set()
        for n in allnames:
            parts = n.split(".")
            for k in range(2, len(parts)):
                groups.add(".".join(parts[:k]))
        with open(presets) as f, open(f"{outdir}/presets.tsv", "w") as o:
            for line in f:
                if not line.strip() or line.startswith("#"):
                    continue
                pid, title, desc, pats = line.rstrip("\n").split("\t")
                keep = []
                for p in pats.split(","):
                    p = p.strip()
                    if p == "fd-gles.*" or p.startswith("fd-gles."):
                        keep.append(p)  # GLES programs are not in the CTS list
                        continue
                    if p.endswith(".*"):
                        ok = p[:-2] in groups
                    else:
                        ok = p in allnames
                    if ok:
                        keep.append(p)
                    else:
                        print(f"preset {pid}: dropping {p} (not in this CTS)")
                if keep:
                    o.write(f"{pid}\t{title}\t{desc}\t{','.join(keep)}\n")


if __name__ == "__main__":
    main()
