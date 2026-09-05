#!/usr/bin/env python3
"""Check every android.* API a classes.dex references against a minimum API level.

Compiling against a newer android.jar than the device runs is normal practice, but it
means the compiler will happily accept a class or method that does not exist on the
target. On this project the target is Android 9 / API 28 with no way to test on hardware
first, so the check has to be static.

Method: parse the dex's type_ids / field_ids / method_ids tables (no disassembly needed -
those tables list every symbol the code can possibly reference), then look each one up in
the SDK's own data/api-versions.xml, which records the API level each class, method and
field was added in and, where applicable, removed in.

Usage: dexapi.py <classes.dex> <api-versions.xml> <min-api>

Exit code 0 if everything referenced exists at <min-api>, 1 otherwise.
"""
import struct
import sys
import xml.etree.ElementTree as ET

# Packages that ship as part of the Android platform. java/* and javax/* on Android come
# from libcore, which api-versions.xml also covers.
PLATFORM_PREFIXES = ("android/", "dalvik/", "java/", "javax/", "org/apache/http/",
                     "org/json/", "org/w3c/dom/", "org/xml/sax/", "org/xmlpull/")


def uleb128(data, off):
    result = 0
    shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            return result, off
        shift += 7


def read_dex(path):
    d = open(path, "rb").read()
    if d[:4] != b"dex\n":
        raise SystemExit("not a dex: " + path)
    version = d[4:7].decode()
    (string_ids_size, string_ids_off) = struct.unpack_from("<II", d, 56)
    (type_ids_size, type_ids_off) = struct.unpack_from("<II", d, 64)
    (proto_ids_size, proto_ids_off) = struct.unpack_from("<II", d, 72)
    (field_ids_size, field_ids_off) = struct.unpack_from("<II", d, 80)
    (method_ids_size, method_ids_off) = struct.unpack_from("<II", d, 88)

    strings = []
    for i in range(string_ids_size):
        off = struct.unpack_from("<I", d, string_ids_off + 4 * i)[0]
        n, off = uleb128(d, off)
        end = d.index(b"\x00", off)
        strings.append(d[off:end].decode("utf-8", "replace"))

    types = []
    for i in range(type_ids_size):
        idx = struct.unpack_from("<I", d, type_ids_off + 4 * i)[0]
        types.append(strings[idx])

    fields = []
    for i in range(field_ids_size):
        cls, typ, name = struct.unpack_from("<HHI", d, field_ids_off + 8 * i)
        fields.append((types[cls], strings[name]))

    protos = []
    for i in range(proto_ids_size):
        shorty, ret, params_off = struct.unpack_from("<III", d, proto_ids_off + 12 * i)
        args = []
        if params_off:
            n = struct.unpack_from("<I", d, params_off)[0]
            for j in range(n):
                args.append(types[struct.unpack_from("<H", d, params_off + 4 + 2 * j)[0]])
        protos.append((types[ret], args))

    methods = []
    for i in range(method_ids_size):
        cls, proto, name = struct.unpack_from("<HHI", d, method_ids_off + 8 * i)
        ret, args = protos[proto]
        methods.append((types[cls], strings[name], "(" + "".join(args) + ")" + ret))

    return version, types, fields, methods


def desc_to_name(desc):
    """Lde/f/G; -> de/f/G ; arrays and primitives return None."""
    if desc.startswith("L") and desc.endswith(";"):
        return desc[1:-1]
    return None


def lvl(v, default=1):
    """API levels in api-versions.xml can be "28", "36.1" or absent."""
    if v is None or v == "":
        return default
    try:
        return int(float(v))
    except (TypeError, ValueError):
        return default


def load_api(path):
    """{class: (since, removed, {(name,sig): (since, removed)}, {field: (since, removed)})}"""
    tree = ET.parse(path)
    out = {}
    for cls in tree.getroot().findall("class"):
        name = cls.get("name")
        csince = lvl(cls.get("since"), 1)
        cremoved = lvl(cls.get("removed"), 0)
        methods = {}
        fields = {}
        for m in cls.findall("method"):
            mname = m.get("name")
            paren = mname.index("(")
            key = (mname[:paren], mname[paren:])
            methods[key] = (lvl(m.get("since"), csince), lvl(m.get("removed"), 0))
        for f in cls.findall("field"):
            fields[f.get("name")] = (lvl(f.get("since"), csince),
                                     lvl(f.get("removed"), 0))
        out[name] = (csince, cremoved, methods, fields, cls)
    # resolve inherited members lazily via the extends/implements chain
    return out, tree


def supers(api, name):
    """Walk the extends/implements chain recorded in api-versions.xml."""
    seen = set()
    stack = [name]
    while stack:
        n = stack.pop()
        if n in seen or n not in api:
            continue
        seen.add(n)
        node = api[n][4]
        for tag in ("extends", "implements"):
            for e in node.findall(tag):
                stack.append(e.get("name"))
    return seen


def main():
    if len(sys.argv) < 4:
        print(__doc__)
        return 2
    dexpath, apipath, minapi = sys.argv[1], sys.argv[2], int(sys.argv[3])
    version, types, fields, methods = read_dex(dexpath)
    api, _ = load_api(apipath)

    print("dex format %s   %d types, %d fields, %d methods referenced"
          % (version, len(types), len(fields), len(methods)))

    problems = []
    newest = []
    checked_classes = 0
    checked_methods = 0
    checked_fields = 0
    unknown = []

    platform_types = set()
    for t in types:
        n = desc_to_name(t)
        if n and n.startswith(PLATFORM_PREFIXES):
            platform_types.add(n)

    for n in sorted(platform_types):
        if n not in api:
            unknown.append("class " + n)
            continue
        checked_classes += 1
        since, removed, _, _, _ = api[n]
        newest.append((since, "class " + n))
        if since > minapi:
            problems.append("class %s needs API %d" % (n, since))
        if removed and removed <= minapi:
            problems.append("class %s was removed in API %d" % (n, removed))

    for cls, name, sig in methods:
        n = desc_to_name(cls)
        if not n or not n.startswith(PLATFORM_PREFIXES):
            continue
        if n not in api:
            continue
        found = None
        for owner in supers(api, n):
            entry = api[owner][2].get((name, sig))
            if entry:
                found = (owner, entry)
                break
        if not found:
            unknown.append("method %s.%s%s" % (n, name, sig))
            continue
        checked_methods += 1
        owner, (since, removed) = found
        newest.append((since, "method %s.%s%s" % (n, name, sig)))
        if since > minapi:
            problems.append("method %s.%s%s needs API %d (declared on %s)"
                            % (n, name, sig, since, owner))
        if removed and removed <= minapi:
            problems.append("method %s.%s%s was removed in API %d" % (n, name, sig, removed))

    for cls, name in fields:
        n = desc_to_name(cls)
        if not n or not n.startswith(PLATFORM_PREFIXES):
            continue
        if n not in api:
            continue
        found = None
        for owner in supers(api, n):
            entry = api[owner][3].get(name)
            if entry:
                found = (owner, entry)
                break
        if not found:
            unknown.append("field %s.%s" % (n, name))
            continue
        checked_fields += 1
        owner, (since, removed) = found
        newest.append((since, "field %s.%s" % (n, name)))
        if since > minapi:
            problems.append("field %s.%s needs API %d (declared on %s)"
                            % (n, name, since, owner))
        if removed and removed <= minapi:
            problems.append("field %s.%s was removed in API %d" % (n, name, removed))

    print("checked against API %d: %d platform classes, %d methods, %d fields"
          % (minapi, checked_classes, checked_methods, checked_fields))

    newest.sort(key=lambda x: -x[0])
    print("\nnewest platform symbols the dex touches:")
    for since, what in newest[:12]:
        print("   API %-3d %s" % (since, what))

    if unknown:
        print("\nnot listed in api-versions.xml (%d) - these are either hidden/internal "
              "APIs or libcore members the file does not track:" % len(unknown))
        for u in sorted(set(unknown)):
            print("   ? " + u)

    if problems:
        print("\nPROBLEMS (%d):" % len(problems))
        for p in problems:
            print("   ! " + p)
        return 1
    print("\nOK - every platform symbol the dex references exists at API %d" % minapi)
    return 0


if __name__ == "__main__":
    sys.exit(main())
