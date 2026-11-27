#!/usr/bin/env bash
# Deploy A2ASettlement. Usage: DEPLOYER_PRIVATE_KEY=... scripts/deploy.sh testnet|mainnet
# Never pass the key on a shared terminal history; source it from a chmod-600 env file instead.
set -euo pipefail
NET="${1:-testnet}"
case "$NET" in
  testnet) RPC=https://testnet.hsk.xyz; CHAIN=133; EXPLORER=https://testnet-explorer.hsk.xyz ;;
  mainnet) RPC=https://mainnet.hsk.xyz; CHAIN=177; EXPLORER=https://hashkey.blockscout.com ;;
  local)   RPC=http://127.0.0.1:8545;  CHAIN=$(cast chain-id --rpc-url http://127.0.0.1:8545); EXPLORER=local ;;
  *) echo "usage: $0 testnet|mainnet|local"; exit 1 ;;
esac
: "${DEPLOYER_PRIVATE_KEY:?set DEPLOYER_PRIVATE_KEY}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT/contracts"
ADDR=$(cast wallet address --private-key "$DEPLOYER_PRIVATE_KEY")
BAL=$(cast balance "$ADDR" --rpc-url "$RPC" -e)
echo "network=$NET chainId=$CHAIN deployer=$ADDR balance=${BAL} HSK"
if [[ "$NET" == "mainnet" && "${CONFIRM_MAINNET:-}" != "yes" ]]; then
  echo "Mainnet spends real HSK. Re-run with CONFIRM_MAINNET=yes"; exit 1
fi
forge script script/Deploy.s.sol --rpc-url "$RPC" --broadcast --private-key "$DEPLOYER_PRIVATE_KEY" | tee /tmp/hsk-deploy.log
C=$(grep -oE "A2ASettlement +0x[0-9a-fA-F]{40}" /tmp/hsk-deploy.log | tail -1 | awk '{print $2}')
echo "A2ASettlement deployed: $C"
echo "Explorer: $EXPLORER/address/$C"
mkdir -p "$ROOT/deployments"
printf '{"network":"%s","chainId":%s,"A2ASettlement":"%s","deployer":"%s","deployedAt":"%s"}\n' "$NET" "$CHAIN" "$C" "$ADDR" "$(date -u +%FT%TZ)" > "$ROOT/deployments/$NET.json"
