# HSK A2A Pay

**Agent-to-agent payments & settlement on HashKey Chain, executed through HSP (HashKey Settlement Platform).**

Built for the HSK Chain Horizon Hackathon · Seoul — track **Agent Payments & A2A Settlement**.

> 中文说明见下方 [中文版](#中文版)。

A *Payment Agent* buys a paid service from a *Receiving Agent*. The receiving agent publishes signed payment terms;
the payment agent turns them into an HSP Cart Mandate, gets the EIP-712 authorization from HSP, checks it with a
signing firewall, asks a human to approve, signs with its wallet, lets HSP build the Payment Mandate and submits it
to the HSP Gateway, which executes the on-chain USDC transfer. The receiving agent verifies the payment with HSP on
its own, anchors a settlement receipt in the `A2ASettlement` contract on HashKey Chain, and delivers.

| | |
|---|---|
| Chain | HashKey Chain testnet `133` (`https://testnet.hsk.xyz`) → mainnet `177` (`https://mainnet.hsk.xyz`) |
| Payment rail | HSP Agentic Payment MCP (`create_order` → `prepare_payment` → `finalize_payment` → `submit_payment` → `query_payment`) |
| Token | USDC (EIP-3009) — testnet `0x8FE3cB71…3c6`, mainnet `0x054ed458…D0a` |
| Contract | `contracts/src/A2ASettlement.sol` — terms, HSP settlement receipts, payer spend policy, direct-pay fallback |
| Backend | Java 21 · Spring Boot 3.5 · web3j · BouncyCastle |
| UI | `frontend/` (served by the backend) — run timeline, approvals, explorer links |

## Live deployment (HashKey testnet 133)

| | |
|---|---|
| `A2ASettlement` | [`0xd72242774301dbc750372d4FF37ebD05f16D7200`](https://testnet-explorer.hskchain.net/address/0xd72242774301dbc750372d4FF37ebD05f16D7200) |
| Deploy tx | [`0xbbbc9214…a93027`](https://testnet-explorer.hskchain.net/tx/0xbbbc921421216ef9c315035bf9c7d5ffff6a93027736f334402537f75cd99ec1) (1,636,343 gas) |
| Demo order `publishTerms` | [`0x6d13ac1b…dd7ae`](https://testnet-explorer.hskchain.net/tx/0x6d13ac1bb4eccc622822adf3f0558d9fb26a67985be31a9d9bf1090a0b0dd7ae) |
| Demo order `recordHspSettlement` | [`0x50a40c18…82b7`](https://testnet-explorer.hskchain.net/tx/0x50a40c1892b6a5b8f9ee93a4a3e21ec1e2fe007d3b4337c64675d01451ae82b7) |
| Live demo | https://hsk.43-167-176-164.sslip.io |

The demo currently runs with **simulated HSP** (pending HSP QA IP allowlisting): the on-chain terms and receipts
above are real testnet transactions, but the USDC transfer itself was simulated (receipt `hspPaymentRef` is
`keccak256("SIMULATED:" + payment_request_id)` and `settlementTx` is zero). Mainnet is not deployed yet.

## Why it fits the track

- **Payment terms come from the receiving agent** (signed agent card + EIP-191 signed `PaymentTerms`).
- **The Payment Agent builds the mandate**: Cart Mandate via `create_order`, Payment Mandate via `prepare_payment` + wallet signature + `finalize_payment`.
- **HSP's Gateway executes the on-chain payment** via `submit_payment`; status via `query_payment` (`payment-safe` is never reported as final).
- **Safety**: signing firewall, two human approvals, off-chain and on-chain spend caps, merchant allowlist, team attribution check, receiver never trusts the payer's word.
- **On HashKey Chain**: every quote and every settlement is anchored in `A2ASettlement`.

## Quick start (local, no keys, no funds)

Requires Foundry, Java 21, Maven.

```bash
git clone https://github.com/ShuCan-git-up/hsk-a2a-pay && cd hsk-a2a-pay
cd contracts && forge install foundry-rs/forge-std --no-git && forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 --no-git && forge test && cd ..
mvn -f backend/pom.xml test          # unit + end-to-end mock flow
scripts/run-local.sh                 # anvil fork of HashKey testnet + contract + app (HSP mock)
# open http://localhost:8080, click "Start purchase", approve twice
```

## Deploy on HashKey testnet (133)

```bash
scripts/new-wallets.sh .hsk-pay/wallets.env      # operator / receiver / payer, prints addresses only
# fund OPERATOR_ADDRESS with test HSK: https://faucet.hsk.xyz
set -a; . .hsk-pay/wallets.env; set +a
DEPLOYER_PRIVATE_KEY=$OPERATOR_PRIVATE_KEY scripts/deploy.sh testnet
cp .env.example .env   # fill CONTRACT_ADDRESS, OPERATOR_PRIVATE_KEY, RECEIVER_*, PAYER_PRIVATE_KEY
docker compose up -d --build
```

## Switch to live HSP

1. Give HSP your server's public egress IP for the QA allowlist (hackathon Telegram support group).
2. Fund the payer wallet with **HashKey testnet USDC** `0x8FE3cB719Ee4410E236Cd6b72ab1fCDC06eF53c6` (manually, per the HSP guide).
3. Set `HSP_MODE=mcp` and `HSP_MERCHANT_NAME=<your exact team name>`, restart. The status card shows the MCP health.

## Mainnet (177)

```bash
CONFIRM_MAINNET=yes DEPLOYER_PRIVATE_KEY=... scripts/deploy.sh mainnet   # ~1.7M gas
```
Then set `HSK_RPC_URL=https://mainnet.hsk.xyz HSK_CHAIN_ID=177 HSK_EXPLORER_URL=https://hashkey.blockscout.com HSP_NETWORK=hashkey TOKEN_ADDRESS=0x054ed45810DbBAb8B27668922D110669c9D88D0a`.
The app refuses to anchor simulated HSP payments on chain 177.

## API

| Method | Path | |
|---|---|---|
| GET | `/.well-known/agent-card.json` | Receiving agent card |
| POST | `/a2a/receiver/quote` `{skill, target}` | Signed payment terms (+ `publishTerms` tx) |
| POST | `/a2a/receiver/settle` `{termsId, flowId, paymentRequestId, payer}` | Receiver verifies with HSP, anchors, delivers |
| POST | `/api/runs` `{target}` | Payment agent starts a purchase |
| POST | `/api/runs/{id}/approve?stage=sign\|submit` | Human approvals |
| POST | `/api/runs/{id}/reject` | Stop before signing / submitting |
| GET | `/api/runs`, `/api/runs/{id}`, `/api/status` | State |

See [docs/architecture.md](docs/architecture.md).

## Security notes

- Private keys only come from environment variables / chmod-600 files; nothing secret is in the repo
  (tests use anvil's public dev keys and a throwaway test PEM).
- The payer never signs anything the firewall did not match to the agreed terms; quotes expire after 5 minutes and are never reused.
- Mock mode is clearly labelled everywhere and cannot write to mainnet.

---

## 中文版

**HSK A2A Pay：在 HashKey Chain 上、通过 HSP（HashKey Settlement Platform）完成的 Agent 对 Agent 支付与结算。**
参赛赛道：HSK Chain Horizon 黑客松（首尔站）· Agent Payments & A2A Settlement。

### 它做了什么

“付款 Agent”向“收款 Agent”购买一项付费服务（实时 HashKey Chain 钱包画像）：

1. 付款 Agent 读取收款 Agent 的 agent card，拿到收款地址和签名地址。
2. 收款 Agent 返回带签名的支付条款（EIP-191），同时把条款写到链上 `A2ASettlement.publishTerms`。
3. 付款 Agent 验签、核对收款地址白名单、检查链下限额和链上 `checkSpend` 消费策略。
4. 调 HSP：`get_chain_config` → `create_order`（Cart Mandate）→ `get_order` → `prepare_payment`（拿到 EIP-712 授权）。
5. 签名防火墙逐项核对 EIP-712（结构、链、代币、付款人、收款人、金额上限、有效期），不一致就拒签。
6. **人工确认第一次** → 钱包本地签名 → `finalize_payment` 生成 Payment Mandate。
7. **人工确认第二次** → `submit_payment` 交给 HSP Gateway 上链转账 → 每 15 秒 `query_payment`。
8. 收款 Agent 自己再向 HSP 查询核实（不信付款方的说法），通过后写链上收据 `recordHspSettlement`，再交付结果。

### 测试网部署（133）

- 合约 `A2ASettlement`：[`0xd72242774301dbc750372d4FF37ebD05f16D7200`](https://testnet-explorer.hskchain.net/address/0xd72242774301dbc750372d4FF37ebD05f16D7200)
- 演示订单的链上记录：上面英文表格里的 `publishTerms` 和 `recordHspSettlement` 两笔交易。
- 目前 HSP 还是模拟模式（等 HSP 加 IP 白名单）：链上条款和收据是真实测试网交易，但 USDC 转账本身是模拟的。主网尚未部署。

### 本地快速体验（不需要私钥和资金）

```bash
cd contracts && forge install foundry-rs/forge-std --no-git && forge install OpenZeppelin/openzeppelin-contracts@v5.1.0 --no-git && forge test && cd ..
mvn -f backend/pom.xml test
scripts/run-local.sh     # 本地分叉 HashKey 测试网 + 部署合约 + 启动应用（HSP 模拟模式）
# 浏览器打开 http://localhost:8080，点 Start purchase，再点两次 Approve
```

### 切换到真实 HSP

1. 把服务器公网出口 IP 发给 HSP 官方（黑客松 Telegram 支持群）加入 QA 白名单。
2. 手动给付款钱包充值 HashKey 测试网 USDC（`0x8FE3cB719Ee4410E236Cd6b72ab1fCDC06eF53c6`）。
3. 设置 `HSP_MODE=mcp`、`HSP_MERCHANT_NAME=你在 DoraHacks 的队名`，重启即可。

### 部署主网（177）

获奖需要部署到 HashKey 主网。运行 `CONFIRM_MAINNET=yes DEPLOYER_PRIVATE_KEY=... scripts/deploy.sh mainnet`，
大约消耗 170 万 gas，需要部署钱包里有真实 HSK。模拟模式的支付不允许写入主网。

### 安全

- 私钥只从环境变量或权限 600 的文件读取，仓库里没有任何真实密钥。
- 付款 Agent 只签防火墙核对通过的内容；报价 5 分钟过期，过期的签名绝不复用。
- 模拟模式在界面和数据里都有明确标注。

## License

MIT
