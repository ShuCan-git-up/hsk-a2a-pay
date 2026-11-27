#!/usr/bin/env bash
# Generates fresh operator / receiver / payer wallets into a chmod-600 env file. Prints addresses only.
set -euo pipefail
OUT="${1:-.hsk-pay/wallets.env}"
mkdir -p "$(dirname "$OUT")"; umask 077
[[ -f "$OUT" ]] && { echo "$OUT exists, refusing to overwrite"; exit 1; }
for role in OPERATOR RECEIVER PAYER; do
  json=$(cast wallet new --json)
  pk=$(echo "$json" | python3 -c 'import sys,json;d=json.load(sys.stdin);d=d.get("data",d);w=d[0] if isinstance(d,list) else d;print(w["private_key"])')
  ad=$(echo "$json" | python3 -c 'import sys,json;d=json.load(sys.stdin);d=d.get("data",d);w=d[0] if isinstance(d,list) else d;print(w["address"])')
  echo "${role}_PRIVATE_KEY=$pk" >> "$OUT"; echo "${role}_ADDRESS=$ad" >> "$OUT"
  echo "$role $ad"
done
chmod 600 "$OUT"; echo "saved to $OUT (keep it secret, never commit)"
