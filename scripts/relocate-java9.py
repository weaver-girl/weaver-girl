#!/usr/bin/env python3
"""Move classes compiled from src/main/java9 between the output root and META-INF/versions/9.

The java9 compiler execution writes its classes to the root of the build output, next to the Java 8
classes, and the tests load them from there: an exploded classes directory is not a multi-release jar,
so META-INF/versions/9 is invisible to it. The jar is multi-release, and a Java 8 runtime must not be
handed bytecode it cannot run, so those classes move under META-INF/versions/9 before it is packed and
back to the root afterwards.

The source list decides what moves. A Java 8 class has no source under java9 and is never touched.
"""

import os
import shutil
import sys


def classes_for(source_root):
    found = []
    for dirpath, _dirs, files in os.walk(source_root):
        for name in files:
            if not name.endswith(".java"):
                continue
            rel = os.path.relpath(dirpath, source_root)
            found.append(os.path.join(rel, name[:-5] + ".class"))
    return found


def move(output_root, relative, to_versioned):
    versioned = os.path.join("META-INF", "versions", "9", relative)
    src = os.path.join(output_root, versioned if not to_versioned else relative)
    dest = os.path.join(output_root, relative if not to_versioned else versioned)
    if not os.path.isfile(src):
        return False
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    shutil.move(src, dest)
    return True


def main(source_root, output_root, where):
    to_versioned = where == "versioned"
    moved = 0
    for relative in classes_for(source_root):
        if move(output_root, relative, to_versioned):
            moved += 1
    print(moved)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], sys.argv[3])
