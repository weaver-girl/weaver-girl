#!/usr/bin/env python3
"""Rewrite relocated-package paths inside META-INF/versions/<N>/ of a shaded jar.

maven-shade-plugin relocates a dependency's *bytecode* but leaves its multi-release entry *paths*
alone. A jar that should contain

    META-INF/versions/9/shaded/org/yaml/snakeyaml/internal/Logger.class

ends up with

    META-INF/versions/9/org/yaml/snakeyaml/internal/Logger.class

whose class file declares `shaded.org.yaml.snakeyaml.internal.Logger`.

That is harmless while the jar is only read through an isolated class loader, but the agent jar is
also appended to the bootstrap class loader search path (BootstrapInjection). A host application
that asks for `org.yaml.snakeyaml.internal.Logger` — Spring Boot's YAML support does exactly that —
gets the versioned entry, and the JVM refuses to define it:

    NoClassDefFoundError: org/yaml/snakeyaml/internal/Logger
        (wrong name: shaded/org/yaml/snakeyaml/internal/Logger)

It also moves a *slice* of `META-INF/versions/` that duplicates a package the host is expected to
provide. The agent bundles micrometer (which a Spring Boot application also bundles); the shaded jar
keeps micrometer's `META-INF/versions/9/...` overlay next to the root copy. Once that jar is on the
bootstrap search path, a host class loader building its `LogbackMetrics` sees the overlay as an
"older" version and refuses to define the host's own logback interface, which surfaces as

    NoClassDefFoundError: ch/qos/logback/classic/spi/LoggerContextListener

Usage:

    relocate-multi-release.py <jar> [--drop <prefix>] [<original>=<relocated> ...]

Pairs move `META-INF/versions/*/<original>/...` to `META-INF/versions/*/<relocated>/...`;
`--drop` deletes `META-INF/versions/*/<prefix>/...` entirely. The jar is rewritten in place;
entry content and compression are preserved.
"""

import os
import sys
import tempfile
import zipfile

VERSIONS = "META-INF/versions/"


def parse_args(args):
    pairs = []
    drops = []
    index = 0
    while index < len(args):
        arg = args[index]
        if arg == "--drop":
            index += 1
            if index >= len(args):
                raise SystemExit("--drop needs a package prefix")
            drops.append(args[index].strip("/") + "/")
        else:
            if "=" not in arg:
                raise SystemExit("expected original=relocated or --drop prefix, got %r" % arg)
            original, relocated = arg.split("=", 1)
            pairs.append((original.strip("/") + "/", relocated.strip("/") + "/"))
        index += 1
    return pairs, drops


def split_versioned(name):
    """Return (version, path) for a META-INF/versions entry, or None."""
    if not name.startswith(VERSIONS):
        return None
    rest = name[len(VERSIONS):]
    slash = rest.find("/")
    if slash < 0:
        return None
    return rest[:slash], rest[slash + 1:]


def main(jar_path, pairs, drops):
    # The archive comment is the jar's "no base multi-release entry" marker, and entry attributes
    # carry permissions; both are preserved so the rewritten jar is a drop-in replacement.
    handle, temp_path = tempfile.mkstemp(suffix=".jar", dir=os.path.dirname(jar_path) or ".")
    os.close(handle)
    moved = 0
    dropped = 0
    try:
        with zipfile.ZipFile(jar_path, "r") as source:
            infos = source.infolist()
            with zipfile.ZipFile(temp_path, "w", zipfile.ZIP_DEFLATED) as target:
                for info in infos:
                    split = split_versioned(info.filename)
                    new_name = info.filename
                    if split is not None:
                        version, path = split
                        if any(path.startswith(drop) for drop in drops):
                            dropped += 1
                            continue
                        for original, relocated in pairs:
                            if path.startswith(original):
                                new_name = VERSIONS + version + "/" + relocated + path[len(original):]
                                moved += 1
                                break
                    entry = zipfile.ZipInfo(new_name, date_time=info.date_time)
                    entry.compress_type = info.compress_type
                    entry.external_attr = info.external_attr
                    entry.internal_attr = info.internal_attr
                    entry.create_system = info.create_system
                    target.writestr(entry, source.read(info.filename))
        os.replace(temp_path, jar_path)
    finally:
        if os.path.exists(temp_path):
            os.remove(temp_path)
    print("relocated %d multi-release entries, dropped %d" % (moved, dropped))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    parsed_pairs, parsed_drops = parse_args(sys.argv[2:])
    main(sys.argv[1], parsed_pairs, parsed_drops)
