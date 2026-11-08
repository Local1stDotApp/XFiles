"""Writes the deterministic source trees the solid RAR 4 fixtures are made from, and their MD5s.

    python3 gen_sources.py <out-dir>

Each tree goes to <out-dir>/<set>/, with <out-dir>/<set>.order (file order for RAR's -ds)
and <out-dir>/rar4_solid.md5 (set, path, MD5). make.sh then builds the archives. See README.md.
"""
import hashlib
import math
import os
import random
import struct
import sys

MTIME = 1_700_000_000


def text(seed, size):
    rng = random.Random(seed)
    syllables = ["ka", "lo", "mi", "ra", "tu", "sen", "dor", "vi", "pel", "an", "qu", "is", "ne", "bo"]
    vocab = ["".join(rng.choice(syllables) for _ in range(rng.randint(1, 4))) for _ in range(300)]
    out, line = [], 0
    while sum(len(w) + 1 for w in out) < size + 1:
        w = rng.choice(vocab)
        out.append(w + (rng.choice(".,;!?") if rng.random() < 0.07 else ""))
        line += len(w) + 1
        if line > 70:
            out.append("\n")
            line = 0
    return " ".join(out).replace(" \n ", "\n").encode()[:size]


def binary(seed, size):
    return random.Random(seed).randbytes(size)


def x86(seed, size):
    rng = random.Random(seed)
    out = bytearray()
    while len(out) < size:
        r = rng.random()
        if r < 0.12:
            out += bytes([rng.choice([0xE8, 0xE9])]) + struct.pack("<i", rng.randint(-50000, 50000))
        elif r < 0.5:
            out += bytes(rng.choice([[0x55], [0x89, 0xE5], [0x8B, 0x45, 0x08], [0x83, 0xEC, 0x10], [0xC3],
                                     [0x48, 0x8B, 0x05], [0x0F, 0x1F, 0x44, 0x00, 0x00], [0x31, 0xC0]]))
        else:
            out.append(rng.randint(0, 255))
    return bytes(out[:size])


def audio(seed, size):
    rng = random.Random(seed)
    f1, f2 = rng.uniform(200, 900), rng.uniform(200, 900)
    out = bytearray()
    t = 0
    while len(out) < size:
        left = int(9000 * math.sin(2 * math.pi * f1 * t / 44100)) + rng.randint(-300, 300)
        right = int(7000 * math.sin(2 * math.pi * f2 * t / 44100)) + rng.randint(-300, 300)
        out += struct.pack("<hh", left, right)
        t += 1
    return bytes(out[:size])


def rgb(seed, width, height):
    rng = random.Random(seed)
    return bytes(
        v & 0xFF
        for y in range(height) for x in range(width)
        for v in (x * 2 + rng.randint(0, 3), y * 2 + rng.randint(0, 3), x + y + rng.randint(0, 3))
    )


def delta(seed, size):
    rng = random.Random(seed)
    out = bytearray()
    counter, level = rng.randint(0, 1000), 5000
    while len(out) < size:
        counter += rng.randint(1, 3)
        level += rng.randint(-20, 20)
        out += struct.pack("<IH", counter, level & 0xFFFF) + bytes([rng.randint(0, 3), 0x7F])
    return bytes(out[:size])


SETS = {
    # More than 64 KB in all, so a 64 KB window wraps; duplicates match across members.
    "text": [
        ("a/empty.txt", b""),
        ("a/one.txt", text(2, 1)),
        ("a/short.txt", text(3, 37)),
        ("a/b/notes.txt", text(4, 900)),
        ("a/b/four.txt", text(5, 4096)),
        ("copy1/eight.txt", text(6, 8192)),
        ("copy2/eight.txt", text(6, 8192)),
        ("seventy.txt", text(8, 70 * 1024)),
        ("z/last.txt", text(10, 3000)),
    ],
    # Empty members between text (PPMd in auto mode) and binary (LZ), in this order (-ds).
    "edge": [
        ("01.txt", text(11, 5000)),
        ("02.e", b""),
        ("03.bin", binary(13, 3000)),
        ("04.e", b""),
        ("05.e", b""),
        ("06.txt", text(16, 8000)),
        ("07.e", b""),
        ("08.bin", binary(18, 2000)),
        ("09.txt", text(11, 4000)),
        ("10.e", b""),
        ("11.txt", text(21, 300)),
        ("12.e", b""),
    ],
    "x86": [(f"prog{i}.exe", x86(30 + i, 24 * 1024)) for i in range(2)],
    "audio": [(f"track{i}.raw", audio(40 + i, 16 * 1024)) for i in range(2)],
    "rgb": [(f"image{i}.raw", rgb(50 + i, 64, 64)) for i in range(2)],
    "delta": [(f"table{i}.dat", delta(60 + i, 16 * 1024)) for i in range(2)],
    "stored": [
        ("1.txt", text(71, 4000)),
        ("2.jpg", binary(72, 3000)),
        ("3.txt", text(73, 4000)),
        ("4.jpg", binary(74, 2000)),
        ("5.txt", text(71, 4000)),
    ],
}

out = sys.argv[1]
with open(os.path.join(out, "rar4_solid.md5"), "w") as manifest:
    for name, files in SETS.items():
        root = os.path.join(out, name)
        with open(os.path.join(out, name + ".order"), "w") as order:
            for rel, data in files:
                path = os.path.join(root, rel)
                os.makedirs(os.path.dirname(path), exist_ok=True)
                with open(path, "wb") as f:
                    f.write(data)
                os.utime(path, (MTIME, MTIME))
                order.write(rel + "\n")
                manifest.write(f"{name}\t{rel}\t{hashlib.md5(data).hexdigest()}\n")
