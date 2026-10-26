#!/usr/bin/env bash
# Checks that this server's clock follows the NIC / NPL time servers and is close to them.
#
#   deploy/ntp/check-time-sync.sh            prints OK or what is wrong; exit 0 when fine, 1 when not
#   MAX_OFFSET_MS=100 deploy/ntp/check-time-sync.sh
#
# Run daily (docs/security/cert-in.md) and keep the output: it is the evidence that clocks are in step.
set -uo pipefail

max_ms="${MAX_OFFSET_MS:-100}"
# Government of India sources (CERT-In directions of 28 April 2022). A pool of other servers is not enough.
allowed='(^|\.)(nic\.in|nplindia\.org|nplindia\.in)$'
problems=()

command -v chronyc >/dev/null 2>&1 || { echo "PROBLEM: chronyc is not installed (apt install chrony)."; exit 1; }

# 1. the clock is synchronised, and its offset is small
tracking="$(chronyc -c tracking 2>/dev/null || true)"
if [ -z "$tracking" ]; then
  problems+=("chrony did not answer (is the chrony service running?)")
else
  # fields: refid-hex,refname,stratum,reftime,system-time-offset(s),last-offset,rms,freq,resid,skew,delay,disp,interval,leap
  IFS=, read -r _ _ stratum _ offset _ _ _ _ _ _ _ _ leap <<<"$tracking"
  if [ "$leap" = "3" ] || [ "${stratum:-16}" -ge 16 ] 2>/dev/null; then
    problems+=("the clock is not synchronised (stratum ${stratum:-?}, leap status ${leap:-?})")
  fi
  ms="$(awk -v o="${offset:-1}" 'BEGIN { if (o < 0) o = -o; printf "%.1f", o * 1000 }')"
  if awk -v m="$ms" -v max="$max_ms" 'BEGIN { exit !(m > max) }'; then
    problems+=("the clock is ${ms} ms away from its source; the limit is ${max_ms} ms")
  fi
fi

# 2. the sources are the government ones
sources="$(chronyc -c sources 2>/dev/null || true)"
good=0; other=()
while IFS=, read -r _ state name _; do
  [ -n "${name:-}" ] || continue
  if [[ "$name" =~ $allowed ]]; then
    # state '*' = selected, '+' = combined: usable
    case "$state" in '*'|'+') good=$((good + 1)) ;; esac
  else
    other+=("$name")
  fi
done <<<"$sources"
[ "$good" -ge 1 ] || problems+=("no NIC or NPL source is selected or usable")
[ "${#other[@]}" -eq 0 ] || problems+=("sources outside NIC/NPL are configured: ${other[*]}")

if [ "${#problems[@]}" -eq 0 ]; then
  echo "OK: clock follows $good NIC/NPL source(s), offset ${ms} ms (limit ${max_ms} ms)"
  exit 0
fi
printf 'PROBLEM: %s\n' "${problems[@]}"
exit 1
