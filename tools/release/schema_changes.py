"""Select migration checks from schema changes against the release or PR baseline."""
import argparse
import json
from pathlib import Path
import re
import subprocess


APPLICATION_GROUPS = [
    {"os": "ubuntu-latest", "test_group": group}
    for group in ("core", "activity", "web", "http")
] + [{"os": "windows-latest", "test_group": "platform-filesystem"}]
SCHEMA_FILES = {
    "DatabaseFormatCatalog.java", "DatabaseMigrationChain.java",
    "SdrTrunkDatabase.java", "ConfigurationHistoryDatabase.java",
}


def schema_path(path):
    name = Path(path).name
    return ((path.startswith("src/main/java/") and
             (name in SCHEMA_FILES or name.endswith(("Schema.java", "SchemaSql.java", "SchemaMigration.java")) or
              re.fullmatch(r"Format\d+To\d+DatabaseMigration\.java", name) is not None)) or
            (path.startswith("src/main/resources/") and path.endswith(".sql")))


def plan(paths, baseline_known=True):
    changed = sorted(path for path in paths if schema_path(path))
    required = not baseline_known or bool(changed)
    groups = [dict(group) for group in APPLICATION_GROUPS]
    if required:
        groups.append({"os": "ubuntu-latest", "test_group": "migrations"})
    return {"required": required, "baseline_known": baseline_known,
            "changed_schema_paths": changed, "matrix": {"include": groups}}


def compare(base, head):
    head = subprocess.check_output(["git", "rev-parse", "--verify", head + "^{commit}"], text=True).strip()
    if not base:
        return {**plan([], False), "base": "", "head": head}
    base = subprocess.check_output(["git", "rev-parse", "--verify", base + "^{commit}"], text=True).strip()
    paths = subprocess.check_output(["git", "diff", "--name-only", "--no-renames", "-z", base, head]).decode().split("\0")
    return {**plan(paths), "base": base, "head": head}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="")
    parser.add_argument("--head", default="HEAD")
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = compare(args.base, args.head)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    if args.github_output:
        with args.github_output.open("a", encoding="utf-8") as output:
            output.write("required=" + str(result["required"]).lower() + "\n")
            output.write("matrix=" + json.dumps(result["matrix"], separators=(",", ":")) + "\n")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
