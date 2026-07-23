#!/usr/bin/env python3
"""Reason about an XFiles pane from a uiautomator XML dump.

Each expandable row carries a chevron exposed to the accessibility tree as
content-desc="Collapse" (the row is open) or "Expand" (the row is closed) — a
direct expansion signal that holds even for a row that is open but rendered no
children (the remembered-expansion bug). Chevron depth is encoded in its left
edge (x1): one indent step (~49px) per tree level.

Usage:
  tree_state.py DUMP collapse-deepest [MINX] -> "cx cy" of the deepest OPEN chevron
                                                (only chevrons with x1>=MINX; empty if none)
  tree_state.py DUMP expand-chevron TEXT-> "cx cy" of TEXT's chevron IF that row is closed
  tree_state.py DUMP first-app          -> "cx cy" of the first Installed app's chevron IF closed
  tree_state.py DUMP center TEXT        -> "cx cy" of the first row labelled TEXT
"""
import re, sys

def parse(path):
    xml = open(path, encoding="utf-8", errors="replace").read()
    labels, chevrons = [], []
    for m in re.finditer(r'text="([^"]*)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        t = m.group(1); x1, y1, x2, y2 = map(int, m.groups()[1:])
        if y1 < 290 or not t.strip() or re.search(r"/|·|GB|:| apps$", t):
            continue
        labels.append({"t": t, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    for m in re.finditer(r'content-desc="(Expand|Collapse)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        st = m.group(1); x1, y1, x2, y2 = map(int, m.groups()[1:])
        chevrons.append({"st": st, "x1": x1, "cx": (x1 + x2) // 2, "cy": (y1 + y2) // 2})
    return labels, chevrons

def chevron_for(chevrons, cy):
    near = [c for c in chevrons if abs(c["cy"] - cy) <= 40]
    return min(near, key=lambda c: abs(c["cy"] - cy)) if near else None

def main():
    path, cmd = sys.argv[1], sys.argv[2]
    labels, chevrons = parse(path)
    if cmd == "collapse-deepest":
        minx = int(sys.argv[3]) if len(sys.argv) > 3 else 0
        openc = [c for c in chevrons if c["st"] == "Collapse" and c["x1"] >= minx]
        if openc:
            c = max(openc, key=lambda c: (c["x1"], -c["cy"]))
            print(f'{c["cx"]} {c["cy"]}')
    elif cmd == "expand-chevron":
        text = sys.argv[3]
        lab = next((l for l in labels if l["t"] == text), None)
        if lab:
            c = chevron_for(chevrons, lab["cy"])
            if c and c["st"] == "Expand":
                print(f'{c["cx"]} {c["cy"]}')
    elif cmd == "first-app":
        inst = next((l for l in labels if l["t"] == "Installed"), None)
        if inst:
            ic = chevron_for(chevrons, inst["cy"])
            base = ic["x1"] if ic else 0
            # First chevron below Installed indented one level deeper = the first app.
            below = sorted((c for c in chevrons if c["cy"] > inst["cy"] + 20 and c["x1"] > base + 20),
                           key=lambda c: c["cy"])
            if below and below[0]["st"] == "Expand":
                print(f'{below[0]["cx"]} {below[0]["cy"]}')
    elif cmd == "center":
        text = sys.argv[3]
        lab = next((l for l in labels if l["t"] == text), None)
        if lab:
            print(f'{lab["cx"]} {lab["cy"]}')

if __name__ == "__main__":
    main()
