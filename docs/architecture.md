# Architecture

```
 Payment Agent (payer wallet)                 Receiving Agent (Wallet Insight)              HSP (HashKey Settlement Platform)
 ───────────────────────────                 ────────────────────────────────              ─────────────────────────────────
 GET /.well-known/agent-card.json  ───────▶  agent card: payTo, termsSigner, price
 POST /a2a/receiver/quote          ───────▶  signed PaymentTerms (EIP-191)
                                             publishTerms() ─────────────────────────────▶ A2ASettlement (HashKey Chain)
 verify signature, payTo pin,
 off-chain caps + checkSpend() ─────────────────────────────────────────────────────────▶ A2ASettlement.checkSpend
 get_chain_config ─────────────────────────────────────────────────────────────────────▶ MCP tool
 create_order(merchant_name, pay_to, cart_mandate) ────────────────────────────────────▶ MCP tool (Cart Mandate)
 get_order / prepare_payment(flow_id, payer, eip3009) ─────────────────────────────────▶ EIP-712 TransferWithAuthorization
 signing firewall (struct, chain, token, from, to, value cap, validBefore)
 ── human approval #1 ──  sign EIP-712 locally
 finalize_payment(signature, payload, mandate contents, cart_hash) ────────────────────▶ Payment Mandate (JWT made by HSP)
 ── human approval #2 ──  submit_payment(payment_mandate_json) ────────────────────────▶ Gateway broadcasts the USDC transfer
 query_payment(flow_id, expected_merchant_name) every 15 s ─────────────────────────────▶ payment-safe / finalized / failed
 POST /a2a/receiver/settle ────────▶  query_payment itself (never trusts the payer)
                                      check attribution, pay_to, payer
                                      recordHspSettlement() ──────────────────────────────▶ A2ASettlement receipt
                                      deliver the resource
```

## Contract: `A2ASettlement.sol`

| Function | Who | Purpose |
|---|---|---|
| `publishTerms` / `publishTermsFor` | receiving agent / its operator | Anchor the payment terms (payee, token, raw amount, expiry, service hash) |
| `recordHspSettlement` | receiving agent / operator | Anchor the HSP-verified payment (payer, amount, keccak(payment_request_id), HSP tx hash) |
| `setSpendPolicy`, `setMerchantAllowed` | payer wallet | On-chain guardrail: per-payment cap, daily cap, merchant allowlist |
| `checkSpend` | anyone (view) | The payment agent calls this before it signs |
| `payDirect` | payer | Fallback rail without HSP: ERC-20 straight to the payee, policy enforced |
| `pause` | owner | Emergency stop; the owner can never move funds |

The contract never holds tokens. HSP moves the USDC; the contract is the audit trail and the policy.

## Backend (Java 21, Spring Boot 3.5, web3j)

- `hsp/McpHspGateway` — MCP Streamable HTTP client for `https://mcp-qa.hashkeymerchant.com/`; refuses to run unless all 8 tools are present.
- `hsp/MockHspGateway` — same 8 tools, same shapes, verifies the real signature, moves nothing, never invents a tx hash.
- `hsp/HspMerchantApiClient` — HSP Merchant REST API (HMAC-SHA256 + ES256K `merchant_authorization`) for teams with their own merchant account.
- `crypto/TypedDataFirewall` — refuses any EIP-712 payload that is not exactly the agreed transfer.
- `payer/PaymentAgentService` — the run state machine with two human approvals (or auto-approve inside policy).
- `receiver/ReceivingAgentService` — signed terms, independent HSP verification, on-chain receipt, delivery.
