#!/usr/bin/env python3
"""Compares the schema built by the migrations on two git refs, and fails on breaking changes.

    schema_diff.py <base ref> <head ref>

Builds each ref's migrations into a fresh database (applied with psql, one transaction per file,
in version order, as Flyway does) and compares them through PostgreSQL's catalog. During a deploy
the previous release keeps running against the new schema for a while, so these are breaking:

  - a table, view, column or function that is gone (dropped or renamed)
  - a column whose type changed
  - a column that was nullable and is now NOT NULL
  - a new NOT NULL column without a default (the previous release cannot insert rows)

New or changed constraints are listed for review but do not fail: a changed check can as well
widen as tighten. Breaking changes pass when the pull request carries the label `breaking-change`
(BREAKING_ALLOWED=true), after review: see docs/database.md, "expand, then contract".

Needs psql, createdb/dropdb and pg_dump on PATH and a PostgreSQL to connect to (PG* variables).
Writes a Markdown report to $GITHUB_STEP_SUMMARY when set.
"""
import difflib
import json
import os
import re
import subprocess
import sys
import tempfile

MIGRATIONS = "backend/src/main/resources/db/migration"
SCHEMAS = ("app", "auth", "platform", "public")


def run(*args, **kw):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kw).stdout


def migration_files(ref):
    names = run("git", "ls-tree", "--name-only", f"{ref}:{MIGRATIONS}").split()
    files = [n for n in names if re.fullmatch(r"V\d+__.+\.sql", n)]
    return sorted(files, key=lambda n: int(n[1:n.index("__")]))


def build(ref, db):
    subprocess.run(["dropdb", "--if-exists", "--force", db], check=True, capture_output=True)
    run("createdb", db)
    with tempfile.TemporaryDirectory() as tmp:
        for name in migration_files(ref):
            path = os.path.join(tmp, name)
            with open(path, "w") as f:
                f.write(run("git", "show", f"{ref}:{MIGRATIONS}/{name}"))
            result = subprocess.run(["psql", "-X", "-q", "-v", "ON_ERROR_STOP=1", "--single-transaction",
                                     "-d", db, "-f", path], text=True, capture_output=True)
            if result.returncode != 0:
                sys.exit(f"{ref}: {name} failed to apply:\n{result.stderr}")


CATALOG = """
select json_build_object(
  'relations', (select coalesce(json_object_agg(n.nspname || '.' || c.relname, c.relkind), '{}')
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = any(%(schemas)s) and c.relkind in ('r', 'v', 'm', 'p')),
  'columns', (select coalesce(json_object_agg(n.nspname || '.' || c.relname || '.' || a.attname,
                  json_build_object('type', format_type(a.atttypid, a.atttypmod), 'notnull', a.attnotnull,
                                    'default', pg_get_expr(d.adbin, d.adrelid))), '{}')
              from pg_attribute a join pg_class c on c.oid = a.attrelid join pg_namespace n on n.oid = c.relnamespace
              left join pg_attrdef d on d.adrelid = a.attrelid and d.adnum = a.attnum
              where n.nspname = any(%(schemas)s) and c.relkind in ('r', 'v', 'm', 'p')
                and a.attnum > 0 and not a.attisdropped),
  'functions', (select coalesce(json_agg(n.nspname || '.' || p.proname || '(' || pg_get_function_identity_arguments(p.oid) || ')'), '[]')
                from pg_proc p join pg_namespace n on n.oid = p.pronamespace
                left join pg_depend dep on dep.objid = p.oid and dep.deptype = 'e'
                where n.nspname = any(%(schemas)s) and dep.objid is null),
  'constraints', (select coalesce(json_object_agg(n.nspname || '.' || c.relname || '.' || k.conname, pg_get_constraintdef(k.oid)), '{}')
                  from pg_constraint k join pg_class c on c.oid = k.conrelid join pg_namespace n on n.oid = c.relnamespace
                  where n.nspname = any(%(schemas)s))
)
"""


def catalog(db):
    sql = CATALOG % {"schemas": "array[" + ",".join(f"'{s}'" for s in SCHEMAS) + "]"}
    return json.loads(run("psql", "-X", "-At", "-d", db, "-c", sql))


def dump(db):
    out = run("pg_dump", "--schema-only", "--no-owner", "--no-comments", *[f"--schema={s}" for s in SCHEMAS], db)
    # \restrict / \unrestrict carry a random key that pg_dump makes anew for every dump.
    return [line for line in out.splitlines()
            if line and not line.startswith(("--", "SET ", "SELECT pg_catalog", "\\restrict", "\\unrestrict"))]


def compare(old, new):
    breaking, review = [], []
    kinds = {"r": "table", "p": "table", "v": "view", "m": "view"}
    for name, kind in old["relations"].items():
        if name not in new["relations"]:
            breaking.append(f"{kinds.get(kind, 'relation')} `{name}` is gone")
    for name, col in old["columns"].items():
        table = name.rsplit(".", 1)[0]
        if table not in new["relations"]:
            continue
        now = new["columns"].get(name)
        if now is None:
            breaking.append(f"column `{name}` is gone")
            continue
        if now["type"] != col["type"]:
            breaking.append(f"column `{name}` changed type from `{col['type']}` to `{now['type']}`")
        if now["notnull"] and not col["notnull"]:
            breaking.append(f"column `{name}` is now NOT NULL")
    for name, col in new["columns"].items():
        table = name.rsplit(".", 1)[0]
        if name not in old["columns"] and table in old["relations"] and old["relations"][table] in ("r", "p"):
            if col["notnull"] and col["default"] is None:
                breaking.append(f"new column `{name}` is NOT NULL without a default")
    for fn in sorted(set(old["functions"]) - set(new["functions"])):
        breaking.append(f"function `{fn}` is gone or its arguments changed")
    for name, definition in new["constraints"].items():
        before = old["constraints"].get(name)
        table = name.rsplit(".", 1)[0]
        if before is None and table in old["relations"]:
            review.append(f"new constraint `{name}`: `{definition}`")
        elif before is not None and before != definition:
            review.append(f"changed constraint `{name}`: `{before}` → `{definition}`")
    return breaking, review


def main():
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    base, head = sys.argv[1], sys.argv[2]
    build(base, "schema_base")
    build(head, "schema_head")
    breaking, review = compare(catalog("schema_base"), catalog("schema_head"))
    diff = list(difflib.unified_diff(dump("schema_base"), dump("schema_head"), base, head, lineterm="", n=2))
    allowed = os.environ.get("BREAKING_ALLOWED") == "true"

    report = [f"## Schema change: `{base}` → `{head}`", ""]
    if not diff:
        report.append("The migrations build the same schema.")
    if breaking:
        report += ["### Breaking for the release still running during a deploy", ""]
        report += [f"- {b}" for b in breaking]
        report += ["", ("Allowed by the `breaking-change` label." if allowed else
                        "Fails unless the pull request has the `breaking-change` label, after review. "
                        "Prefer expand, then contract (docs/database.md).")]
    if review:
        report += ["", "### Constraints to review", ""] + [f"- {r}" for r in review]
    if diff:
        shown = diff[:400]
        report += ["", "<details><summary>Schema diff (" + str(len(diff)) + " lines)</summary>", "", "```diff",
                   *shown, *(["... (truncated)"] if len(diff) > len(shown) else []), "```", "</details>"]
    text = "\n".join(report)
    print(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(text + "\n")
    sys.exit(1 if breaking and not allowed else 0)


if __name__ == "__main__":
    main()
