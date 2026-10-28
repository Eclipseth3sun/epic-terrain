"""Minimal Anvil (.mca) + NBT reader for inspecting what the game generated."""
import gzip
import io
import struct
import zlib

import numpy as np


def read_tag(b, t):
    if t == 1:
        return struct.unpack('>b', b.read(1))[0]
    if t == 2:
        return struct.unpack('>h', b.read(2))[0]
    if t == 3:
        return struct.unpack('>i', b.read(4))[0]
    if t == 4:
        return struct.unpack('>q', b.read(8))[0]
    if t == 5:
        return struct.unpack('>f', b.read(4))[0]
    if t == 6:
        return struct.unpack('>d', b.read(8))[0]
    if t == 7:
        n = struct.unpack('>i', b.read(4))[0]
        return np.frombuffer(b.read(n), dtype=np.int8)
    if t == 8:
        n = struct.unpack('>H', b.read(2))[0]
        return b.read(n).decode('utf-8', 'replace')
    if t == 9:
        it = b.read(1)[0]
        n = struct.unpack('>i', b.read(4))[0]
        return [read_tag(b, it) for _ in range(n)]
    if t == 10:
        d = {}
        while True:
            tt = b.read(1)[0]
            if tt == 0:
                return d
            n = struct.unpack('>H', b.read(2))[0]
            name = b.read(n).decode()
            d[name] = read_tag(b, tt)
    if t == 11:
        n = struct.unpack('>i', b.read(4))[0]
        return np.frombuffer(b.read(4 * n), dtype='>i4')
    if t == 12:
        n = struct.unpack('>i', b.read(4))[0]
        return np.frombuffer(b.read(8 * n), dtype='>i8')
    raise ValueError('bad tag %d' % t)


def parse_nbt(data):
    b = io.BytesIO(data)
    t = b.read(1)[0]
    n = struct.unpack('>H', b.read(2))[0]
    b.read(n)
    return read_tag(b, t)


def chunks_in_region(path):
    """Yields (local_x, local_z, nbt_dict) for every chunk present in a region file."""
    with open(path, 'rb') as f:
        header = f.read(4096)
        for i in range(1024):
            entry = struct.unpack('>I', header[i * 4:i * 4 + 4])[0]
            offset, sectors = entry >> 8, entry & 0xFF
            if offset == 0 or sectors == 0:
                continue
            f.seek(offset * 4096)
            length, compression = struct.unpack('>IB', f.read(5))
            payload = f.read(length - 1)
            if compression == 2:
                payload = zlib.decompress(payload)
            elif compression == 1:
                payload = gzip.decompress(payload)
            elif compression == 3:
                pass
            else:
                continue  # lz4 or custom: skip
            yield i & 31, i >> 5, parse_nbt(payload)


def unpack_longs(longs, bits, count):
    """Unpacks `count` values of `bits` bits each; values never straddle two longs (Minecraft 1.16+)."""
    per_long = 64 // bits
    mask = (1 << bits) - 1
    arr = np.asarray(longs).astype('>i8').view('>u8').astype(np.uint64)
    out = np.empty(len(arr) * per_long, dtype=np.int64)
    for j in range(per_long):
        out[j::per_long] = ((arr >> np.uint64(j * bits)) & np.uint64(mask)).astype(np.int64)
    return out[:count]
