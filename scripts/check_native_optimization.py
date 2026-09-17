#!/usr/bin/env python3
"""Check generated Android native commands after a build, including actual inference kernels."""
import json
import pathlib
import re
import shlex

root = pathlib.Path(__file__).resolve().parents[1]
databases = list((root / "app/.cxx").glob("**/compile_commands.json"))
assert databases, "Build the app first to generate compile_commands.json"
checked = 0
for database in databases:
    for entry in json.loads(database.read_text()):
        source = entry["file"]
        if not ("/whisper/src/" in source or "/whisper/ggml/src/" in source or source.endswith("/bridge.cpp")):
            continue
        arguments = entry.get("arguments") or shlex.split(entry["command"])
        flags = [arg for arg in arguments if re.fullmatch(r"-O[0-3sgz]|-Ofast", arg)]
        assert flags and flags[-1] in ("-O2", "-O3"), f"Unoptimized inference kernel: {database}: {source}: {flags}"
        checked += 1
assert checked, "No Whisper/GGML inference compilation commands were found"
print(f"Verified optimized compilation for {checked} native commands across {len(databases)} ABI/configuration databases.")
