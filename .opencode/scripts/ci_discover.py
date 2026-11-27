#!/usr/bin/env python3
"""Runner host-only de descoberta unittest para o CI Python da coordenação."""
from __future__ import annotations

import sys
import unittest


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print("uso: ci_discover.py <diretorio-dos-testes>", file=sys.stderr)
        return 2
    start = argv[0]
    sys.path.insert(0, start)
    suite = unittest.TestLoader().discover(
        start, pattern="test_*.py", top_level_dir=start)
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
