#!/usr/bin/env python3
"""Builds DriverLab's register-name tables from Mesa's a6xx.xml.

Usage: make_regs.py a6xx.xml OUTDIR [mesa-commit]
Writes OUTDIR/a6xx.tsv, a7xx.tsv, a8xx.tsv: "offset<TAB>name" (offset hex),
covering reg32/reg64 and arrays (expanded as NAME[i] or NAME[i].REG) of the
A6XX register domain. The register database is MIT licensed (Mesa).
"""
import sys
import xml.etree.ElementTree as ET

GENS = ["A6XX", "A7XX", "A8XX"]


def variant_ok(variants, gen):
    if not variants:
        return True
    gi = GENS.index(gen)
    for part in variants.split(","):
        part = part.strip()
        if not part:
            continue
        if part.endswith("-"):
            lo = part[:-1]
            if lo in GENS and gi >= GENS.index(lo):
                return True
        elif "-" in part:
            lo, hi = part.split("-", 1)
            if lo in GENS and hi in GENS and GENS.index(lo) <= gi <= GENS.index(hi):
                return True
        elif part == gen:
            return True
    return False


def local(tag):
    return tag.rsplit("}", 1)[-1]


def walk(elem, gen, base, prefix, out, inherited=None):
    for child in elem:
        tag = local(child.tag)
        var = child.get("variants") or inherited
        if not variant_ok(var, gen):
            continue
        if tag in ("reg32", "reg64"):
            off = base + int(child.get("offset"), 0)
            name = prefix + child.get("name")
            out.setdefault(off, name)
            if tag == "reg64":
                out.setdefault(off + 1, name + "+1")
        elif tag == "array":
            off = base + int(child.get("offset"), 0)
            stride = int(child.get("stride", "1"), 0)
            length = int(child.get("length", "1"), 0)
            name = child.get("name")
            subs = [c for c in child if local(c.tag) in ("reg32", "reg64")]
            for i in range(length):
                if not subs:
                    out.setdefault(off + i * stride, f"{prefix}{name}[{i}]")
                else:
                    walk(child, gen, off + i * stride, f"{prefix}{name}[{i}].", out, var)
        elif tag == "stripe":
            walk(child, gen, base, prefix, out, var)


def main():
    src, outdir = sys.argv[1], sys.argv[2]
    commit = sys.argv[3] if len(sys.argv) > 3 else "unknown"
    root = ET.parse(src).getroot()
    dom = next(d for d in root.iter() if local(d.tag) == "domain" and d.get("name") == "A6XX")
    for gen in GENS:
        out = {}
        walk(dom, gen, 0, "", out)
        with open(f"{outdir}/{gen.lower()}.tsv", "w") as f:
            f.write(f"# Mesa a6xx.xml @ {commit}, {gen}, {len(out)} registers\n")
            for off in sorted(out):
                f.write(f"{off:x}\t{out[off]}\n")
        print(gen, len(out))


if __name__ == "__main__":
    main()
