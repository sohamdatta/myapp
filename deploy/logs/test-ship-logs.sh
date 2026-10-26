#!/usr/bin/env bash
# Tests ship-logs.sh with stand-ins for docker, rclone and journalctl (none are needed to run it).
#   deploy/logs/test-ship-logs.sh
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT
mkdir -p "$t/bin" "$t/dest"

cat >"$t/bin/docker" <<'X'
#!/usr/bin/env bash
if [ "$1" = ps ]; then printf 'app\nweb\n'; exit 0; fi
if [ "$1" = logs ]; then
  last="${@: -1}"
  [ "$last" = broken ] && { echo "no such container" >&2; exit 1; }
  echo "2026-10-25T10:00:00Z line from $last since=$4 until=$6"; exit 0
fi
exit 1
X
cat >"$t/bin/rclone" <<'X'
#!/usr/bin/env bash
# rclone copyto <file> <dest>/<object>  [flags]
[ "$1" = copyto ] || exit 1
[ -n "${FAKE_RCLONE_FAIL:-}" ] && exit 1
dest="${FAKE_DEST}/${3#*:}"; mkdir -p "$(dirname "$dest")"; cp "$2" "$dest"
X
cat >"$t/bin/journalctl" <<'X'
#!/usr/bin/env bash
echo "2026-10-25T01:02:03+0000 host sshd[1]: Accepted publickey for deploy"
X
chmod +x "$t/bin/"*
export PATH="$t/bin:$PATH" FAKE_DEST="$t/dest" LOG_DEST="fake:bucket" HRMS_ENVIRONMENT=uat

fail() { echo "FAIL: $*" >&2; exit 1; }

# 1. a normal day
"$here/ship-logs.sh" 2026-10-25 >"$t/out"
for f in app.log.gz web.log.gz host-auth.log.gz; do
  [ -s "$t/dest/bucket/uat/2026-10-25/$f" ] || fail "missing $f"
done
zcat "$t/dest/bucket/uat/2026-10-25/app.log.gz" | grep -q "since=2026-10-25T00:00:00Z until=2026-10-26T00:00:00Z" || fail "wrong time window"
zcat "$t/dest/bucket/uat/2026-10-25/host-auth.log.gz" | grep -q Accepted || fail "host log content"
grep -q "3 stored, 0 failed" "$t/out" || fail "summary"

# 2. month end: the window ends on the first of next month
"$here/ship-logs.sh" 2026-10-31 >/dev/null
zcat "$t/dest/bucket/uat/2026-10-31/app.log.gz" | grep -q "until=2026-11-01T00:00:00Z" || fail "month end window"

# 3. upload failure is an error
FAKE_RCLONE_FAIL=1 "$here/ship-logs.sh" 2026-10-25 >/dev/null 2>&1 && fail "should fail when the upload fails"

# 4. one unreadable container is an error, the others are still stored
rm -rf "$t/dest"/*
CONTAINERS="app broken" "$here/ship-logs.sh" 2026-10-25 >/dev/null 2>&1 && fail "should fail when a container cannot be read"
[ -s "$t/dest/bucket/uat/2026-10-25/app.log.gz" ] || fail "app should still be stored"

# 5. bad input
"$here/ship-logs.sh" yesterday >/dev/null 2>&1 && fail "bad date accepted"
env -u LOG_DEST "$here/ship-logs.sh" 2026-10-25 >/dev/null 2>&1 && fail "missing LOG_DEST accepted"
echo "ship-logs: all checks passed"
