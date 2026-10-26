#!/usr/bin/env python3
"""Wrap the inherited Android launcher icon, preserving app identity and attribution."""
import pathlib
import struct
import zlib

root = pathlib.Path(__file__).resolve().parents[2]
source = root / "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png"
target = root / "desktopApp/packaging"
png = source.read_bytes()
width, height, depth, color, compression, filtering, interlace = struct.unpack(">IIBBBBB", png[16:29])
assert (depth, color, interlace) == (8, 6, 0)
compressed = bytearray()
offset = 8
while offset < len(png):
    size, kind = struct.unpack(">I4s", png[offset:offset + 8])
    if kind == b"IDAT":
        compressed += png[offset + 8:offset + 8 + size]
    offset += size + 12
decoded = zlib.decompress(compressed)
rows = []
stride = width * 4
previous = bytearray(stride)
for y in range(height):
    method = decoded[y * (stride + 1)]
    row = bytearray(decoded[y * (stride + 1) + 1:(y + 1) * (stride + 1)])
    for x in range(stride):
        left, up, corner = (row[x - 4] if x >= 4 else 0), previous[x], (previous[x - 4] if x >= 4 else 0)
        p = left + up - corner
        pa, pb, pc = abs(p - left), abs(p - up), abs(p - corner)
        paeth = left if pa <= pb and pa <= pc else up if pb <= pc else corner
        predictor = (0, left, up, (left + up) // 2, paeth)[method]
        row[x] = (row[x] + predictor) & 255
    rows.append(row)
    previous = row

def chunk(kind, data):
    return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

pixels = bytearray()
for y in range(256):
    pixels.append(0)
    for x in range(256):
        start = (x * width // 256) * 4
        pixels += rows[y * height // 256][start:start + 4]
resized = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 256, 256, 8, 6, 0, 0, 0))
resized += chunk(b"IDAT", zlib.compress(pixels, 9)) + chunk(b"IEND", b"")
(target / "archivetune.png").write_bytes(resized)
(root / "desktopApp/src/jvmMain/resources/archivetune.png").write_bytes(resized)
(target / "archivetune.ico").write_bytes(
    struct.pack("<HHHBBBBHHII", 0, 1, 1, 0, 0, 0, 0, 1, 32, len(resized), 22) + resized)
block = b"ic08" + struct.pack(">I", len(resized) + 8) + resized
(target / "archivetune.icns").write_bytes(b"icns" + struct.pack(">I", len(block) + 8) + block)
