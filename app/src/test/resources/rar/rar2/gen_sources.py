"""Writes the deterministic source trees the RAR 2.x fixtures are made from, and their MD5s.

    python3 gen_sources.py <out-dir>

Each tree goes to <out-dir>/<set>/, with <out-dir>/<set>.order (file order for RAR's -ds)
and <out-dir>/rar2.md5 (set, path, MD5). make.sh then builds the archives. See README.md.
"""
import hashlib
import math
import os
import random
import struct
import sys

MTIME = 1_000_000_000  # 2001, when RAR 2.80 came out; DOS times can't go below 1980


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


def pcm16(seed, size, channels=2):
    rng = random.Random(seed)
    freqs = [rng.uniform(200, 900) for _ in range(channels)]
    out = bytearray()
    t = 0
    while len(out) < size:
        for c, f in enumerate(freqs):
            v = int((9000 - 2000 * c) * math.sin(2 * math.pi * f * t / 44100)) + rng.randint(-300, 300)
            out += struct.pack("<h", v)
        t += 1
    return bytes(out[:size])


def pcm8(seed, size, channels=1):
    rng = random.Random(seed)
    freqs = [rng.uniform(100, 700) for _ in range(channels)]
    out = bytearray()
    t = 0
    while len(out) < size:
        for c, f in enumerate(freqs):
            v = 128 + int((90 - 15 * c) * math.sin(2 * math.pi * f * t / 22050)) + rng.randint(-3, 3)
            out.append(max(0, min(255, v)))
        t += 1
    return bytes(out[:size])


def rgb(seed, width, height):
    rng = random.Random(seed)
    return bytes(
        v & 0xFF
        for y in range(height) for x in range(width)
        for v in (x * 2 + rng.randint(0, 3), y * 2 + rng.randint(0, 3), x + y + rng.randint(0, 3))
    )


def far(seed):
    """Matches from more than 256 KB back, which RAR 2.0 lengthens by one, and from 8 KB."""
    rng = random.Random(seed)
    head = rng.randbytes(4000)
    words = text(seed, 3000)
    filler = bytearray()
    while len(filler) < 280_000:
        filler += bytes(rng.randint(500, 3000))
        filler += rng.randbytes(rng.randint(4, 24))
        if rng.random() < 0.3:
            filler += words[:rng.randint(200, 3000)]
    return head + bytes(filler) + head + words + head[:3000]


def mixed(seed):
    """Text, then audio, then text again in one file: blocks switch between LZ and audio."""
    return text(seed, 3000) + pcm16(seed + 1, 6000) + text(seed + 2, 2500) + pcm8(seed + 3, 3000)


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
    "far": [("far.bin", far(20))],
    # -mmf: every member is packed as audio, with as many channels as RAR finds.
    "audio": [
        ("mono8.raw", pcm8(30, 6000, 1)),
        ("quad8.raw", pcm8(31, 8000, 4)),
        ("stereo16.raw", pcm16(32, 9000, 2)),
        ("image.rgb", rgb(33, 40, 40)),
    ],
    # -mm: RAR picks audio or LZ for each part of each member.
    "mixed": [
        ("1.txt", text(40, 3000)),
        ("2.mix", mixed(41)),
        ("3.e", b""),
        ("4.raw", pcm16(43, 6000)),
        ("5.bin", binary(44, 2000)),
        ("6.txt", text(40, 3000)),
        ("7.raw", pcm8(46, 3000, 2)),
    ],
    # Empty members between the others, and members RAR may store.
    "edge": [
        ("01.txt", text(51, 5000)),
        ("02.e", b""),
        ("03.bin", binary(53, 3000)),
        ("04.e", b""),
        ("05.e", b""),
        ("06.txt", text(56, 8000)),
        ("07.e", b""),
        ("08.bin", binary(58, 2000)),
        ("09.txt", text(51, 4000)),
        ("10.e", b""),
    ],
    "small": [
        ("readme.txt", text(61, 1500)),
        ("data.bin", binary(62, 700)),
        ("sub/notes.txt", text(63, 2500)),
    ],
}

out = sys.argv[1]
with open(os.path.join(out, "rar2.md5"), "w") as manifest:
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
