#!/usr/bin/env bash
# One-command local demo: anvil fork of HashKey testnet (chain 133) + contract + app in HSP mock mode.
# Uses anvil's public dev keys only. Open http://localhost:8080 afterwards.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p /tmp/hsk-run
anvil --fork-url https://testnet.hsk.xyz --chain-id 133 --port 8545 > /tmp/hsk-run/anvil.log 2>&1 &
trap 'kill $(jobs -p) 2>/dev/null' EXIT
sleep 5
K0=$(grep -A3 "Private Keys" /tmp/hsk-run/anvil.log | grep "(0)" | awk '{print $2}')
K1=$(grep -A4 "Private Keys" /tmp/hsk-run/anvil.log | grep "(1)" | awk '{print $2}')
K2=$(grep -A5 "Private Keys" /tmp/hsk-run/anvil.log | grep "(2)" | awk '{print $2}')
cd "$ROOT/contracts"
C=$(forge script script/Deploy.s.sol --rpc-url http://127.0.0.1:8545 --broadcast --private-key "$K0" | grep -oE "A2ASettlement +0x[0-9a-fA-F]{40}" | tail -1 | awk '{print $2}')
echo "A2ASettlement on local fork: $C"
cd "$ROOT/backend"; [[ -f target/hsk-a2a-pay.jar ]] || mvn -q -B -DskipTests package
HSK_RPC_URL=http://127.0.0.1:8545 CONTRACT_ADDRESS=$C OPERATOR_PRIVATE_KEY=$K0 \
RECEIVER_SIGNING_PRIVATE_KEY=$K1 RECEIVER_PAY_TO=$(cast wallet address --private-key "$K1") \
PAYER_PRIVATE_KEY=$K2 HSP_MODE=mock HSP_POLL_SECONDS=2 \
java -jar target/hsk-a2a-pay.jar
