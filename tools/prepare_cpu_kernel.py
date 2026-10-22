#!/usr/bin/env python3
"""Prepare an auditable kernel config in a separate output directory. Never flash a device."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
LOCK = json.loads((ROOT / "kernel/source-lock.json").read_text())
REQUIRED = {"CONFIG_CGROUP_SCHED": "y", "CONFIG_FAIR_GROUP_SCHED": "y", "CONFIG_CFS_BANDWIDTH": "y"}


def configuration(text):
    result = {}
    for line in text.splitlines():
        if line.startswith("CONFIG_") and "=" in line:
            key, value = line.split("=", 1)
            result[key] = value
        elif line.startswith("# CONFIG_") and line.endswith(" is not set"):
            result[line[2:-11]] = "n"
    return result


def merge(text):
    lines = []
    for line in text.splitlines():
        key = line.split("=", 1)[0] if line.startswith("CONFIG_") else line[2:-11] if line.startswith("# CONFIG_") and line.endswith(" is not set") else ""
        if key not in REQUIRED:
            lines.append(line)
    return "\n".join(lines + [f"{key}={value}" for key, value in REQUIRED.items()]) + "\n"


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def prepare(source, baseline, output):
    source, baseline, output = (p.resolve() for p in (source, baseline, output))
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=source, text=True).strip()
    if commit != LOCK["commit"] or tree != LOCK["tree"]:
        raise ValueError("Source checkout does not match the pinned device commit")
    if subprocess.check_output(["git", "status", "--porcelain"], cwd=source, text=True).strip():
        raise ValueError("Use a clean kernel checkout; keep output outside the source tree")
    if output == source or source in output.parents or output == baseline or baseline in output.parents:
        raise ValueError("Output must be separate from source and baseline")
    output.mkdir(parents=True, exist_ok=False)
    missing = [name for name in LOCK["requiredBaselineFiles"] if not (baseline / name).is_file()]
    report = {"commit": commit, "tree": tree, "flashable": False, "missing": missing,
              "baselineSha256": {name: sha(baseline / name) for name in LOCK["requiredBaselineFiles"] if (baseline / name).is_file()},
              "pending": ["Match exact Android Clang build and vendor module revisions", "Compile original and quota configs with identical inputs", "Compare ABI/KMI and vendor module symbols", "Validate UID aggregate CPU hierarchy against Android task profiles", "Device boot and rollback acceptance"]}
    if (baseline / "config").is_file():
        original = (baseline / "config").read_text()
        (output / "config.original").write_text(original)
        candidate = merge(original)
        (output / "config.quota").write_text(candidate)
        before, after = configuration(original), configuration(candidate)
        report["configChanges"] = {key: {"before": before.get(key, "n"), "after": value} for key, value in after.items() if before.get(key) != value}
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(prepare(args.source, args.baseline, args.output), indent=2))
