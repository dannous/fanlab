#!/usr/bin/env python3
"""Copy a zip/APK and add files to it, preserving every existing entry verbatim.

Usage: apkadd.py <src.apk> <dst.apk> <name-in-apk>=<local-path> [...]

Used to drop classes.dex into the resource-only APK that aapt2 link produces. Compression
type, timestamps and external attributes of the copied entries are preserved so that the
result is byte-for-byte the same archive plus the new members; zipalign runs afterwards.
"""
import os
import sys
import zipfile


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    src, dst = sys.argv[1], sys.argv[2]
    additions = []
    for spec in sys.argv[3:]:
        name, _, path = spec.partition("=")
        if not path:
            print("bad spec (want name=path): " + spec)
            return 2
        additions.append((name, path))

    with zipfile.ZipFile(src, "r") as zin:
        infos = zin.infolist()
        existing = set(i.filename for i in infos)
        payload = {i.filename: zin.read(i) for i in infos}

    tmp = dst + ".tmp"
    with zipfile.ZipFile(tmp, "w", zipfile.ZIP_DEFLATED) as zout:
        for i in infos:
            out = zipfile.ZipInfo(i.filename, date_time=i.date_time)
            out.compress_type = i.compress_type
            out.external_attr = i.external_attr
            out.internal_attr = i.internal_attr
            out.create_system = i.create_system
            zout.writestr(out, payload[i.filename])
        for name, path in additions:
            if name in existing:
                print("refusing to add a duplicate entry: " + name)
                return 3
            with open(path, "rb") as f:
                data = f.read()
            out = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            out.compress_type = zipfile.ZIP_DEFLATED
            out.external_attr = 0o644 << 16
            zout.writestr(out, data)
            print("  + %s  (%d bytes)" % (name, len(data)))

    if os.path.exists(dst):
        os.remove(dst)
    os.rename(tmp, dst)
    print("  wrote %s  (%d bytes, %d entries)"
          % (dst, os.path.getsize(dst), len(infos) + len(additions)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
