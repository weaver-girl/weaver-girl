#!/usr/bin/env python3
"""Stage JPMS descriptors for the reactor module path.

javac only reads a module descriptor out of a directory when module-info.class sits at the
directory root. This build keeps its descriptors at META-INF/versions/9 (so the jars stay Java 8
compatible), which means a plain `mvn test` -- where an upstream module is resolved as its exploded
target/classes rather than an installed jar -- cannot see them.

This copies every such descriptor to <classes>/../module-root/module-info.class, a layout javac
does understand, and prints the resulting directories as one module path. The caller appends it to
the compiler's module path.
"""

import os
import shutil
import sys


def main(entries):
    staged = []
    for entry in entries:
        if not os.path.isdir(entry):
            continue
        descriptor = os.path.join(entry, "META-INF", "versions", "9", "module-info.class")
        if not os.path.isfile(descriptor):
            continue
        # javac reads the descriptor only from the root, but it also resolves the module's
        # exported packages from this same directory, so the classes have to come along.
        root = os.path.join(os.path.dirname(entry), "module-root")
        shutil.copytree(entry, root, dirs_exist_ok=True)
        shutil.copy2(descriptor, os.path.join(root, "module-info.class"))
        staged.append(root)
    print(os.pathsep.join(staged))


if __name__ == "__main__":
    main(sys.argv[1:])
