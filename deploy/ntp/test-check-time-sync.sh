#!/usr/bin/env bash
# Tests check-time-sync.sh against a stand-in chronyc.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
t="$(mktemp -d)"; trap 'rm -rf "$t"' EXIT
mkdir -p "$t/bin"
cat >"$t/bin/chronyc" <<'X'
#!/usr/bin/env bash
case "$2" in
  tracking) cat "$FAKE_TRACKING" ;;
  sources)  cat "$FAKE_SOURCES" ;;
esac
X
chmod +x "$t/bin/chronyc"
export PATH="$t/bin:$PATH" FAKE_TRACKING="$t/tracking" FAKE_SOURCES="$t/sources"
fail() { echo "FAIL: $*" >&2; exit 1; }
run() { "$here/check-time-sync.sh" 2>&1; }

good_sources='^,*,samay1.nic.in,2,6,377,12,0.0001,0.0001,0.001
^,-,samay2.nic.in,2,6,377,12,0.0001,0.0001,0.001'
echo '8A2B0C01,samay1.nic.in,2,1761440000.0,0.000020,0.000010,0.0002,1.0,0.001,0.1,0.01,0.001,64,0' >"$t/tracking"
echo "$good_sources" >"$t/sources"
out="$(run)" || fail "a good clock was refused: $out"
[[ "$out" == OK* ]] || fail "expected OK, got $out"

# off by 2 seconds
echo '8A2B0C01,samay1.nic.in,2,1761440000.0,-2.000000,0.0,0.0002,1.0,0.001,0.1,0.01,0.001,64,0' >"$t/tracking"
out="$(run)" && fail "a 2 s offset was accepted"
[[ "$out" == *"away from its source"* ]] || fail "offset message: $out"

# not synchronised
echo '00000000,,0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0,3' >"$t/tracking"
out="$(run)" && fail "an unsynchronised clock was accepted"
[[ "$out" == *"not synchronised"* ]] || fail "sync message: $out"

# a public pool in the list
echo '8A2B0C01,samay1.nic.in,2,1761440000.0,0.000020,0.0,0.0002,1.0,0.001,0.1,0.01,0.001,64,0' >"$t/tracking"
printf '%s\n^,+,0.ubuntu.pool.ntp.org,2,6,377,12,0,0,0\n' "$good_sources" >"$t/sources"
out="$(run)" && fail "a non-government source was accepted"
[[ "$out" == *"outside NIC/NPL"* ]] || fail "pool message: $out"

# only unusable government sources
echo '^,?,samay1.nic.in,0,6,0,0,0,0,0' >"$t/sources"
out="$(run)" && fail "no usable source was accepted"
[[ "$out" == *"no NIC or NPL source"* ]] || fail "unusable message: $out"

# chronyc missing
out="$(CHRONYC=no-such-chronyc "$here/check-time-sync.sh" 2>&1)" && fail "missing chronyc accepted" || true
[[ "$out" == *"not installed"* ]] || fail "missing message: $out"
echo "check-time-sync: all checks passed"
