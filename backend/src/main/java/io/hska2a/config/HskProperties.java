package io.hska2a.config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All runtime configuration. Secrets come from environment variables only (see .env.example). */
@ConfigurationProperties(prefix = "hsk")
public class HskProperties {

    private final Chain chain = new Chain();
    private final Token token = new Token();
    private final Hsp hsp = new Hsp();
    private final Receiver receiver = new Receiver();
    private final Payer payer = new Payer();

    public Chain getChain() { return chain; }
    public Token getToken() { return token; }
    public Hsp getHsp() { return hsp; }
    public Receiver getReceiver() { return receiver; }
    public Payer getPayer() { return payer; }

    public static class Chain {
        /** HashKey Chain RPC. Testnet: https://testnet.hsk.xyz (133). Mainnet: https://mainnet.hsk.xyz (177). */
        private String rpcUrl = "https://testnet.hsk.xyz";
        private long chainId = 133;
        private String explorerUrl = "https://testnet-explorer.hsk.xyz";
        /** A2ASettlement contract. Blank = on-chain anchoring disabled. */
        private String contractAddress = "";
        /** Operator key that sends publishTerms / recordHspSettlement. Env only. */
        private String operatorPrivateKey = "";
        /** RPC used for the paid "wallet insight" resource (mainnet gives richer data). */
        private String insightRpcUrl = "https://mainnet.hsk.xyz";
        private long insightChainId = 177;
        private long receiptPollMs = 1500;
        private int receiptAttempts = 60;

        public String getRpcUrl() { return rpcUrl; }
        public void setRpcUrl(String v) { rpcUrl = v; }
        public long getChainId() { return chainId; }
        public void setChainId(long v) { chainId = v; }
        public String getExplorerUrl() { return explorerUrl; }
        public void setExplorerUrl(String v) { explorerUrl = v; }
        public String getContractAddress() { return contractAddress; }
        public void setContractAddress(String v) { contractAddress = v; }
        public String getOperatorPrivateKey() { return operatorPrivateKey; }
        public void setOperatorPrivateKey(String v) { operatorPrivateKey = v; }
        public String getInsightRpcUrl() { return insightRpcUrl; }
        public void setInsightRpcUrl(String v) { insightRpcUrl = v; }
        public long getInsightChainId() { return insightChainId; }
        public void setInsightChainId(long v) { insightChainId = v; }
        public long getReceiptPollMs() { return receiptPollMs; }
        public void setReceiptPollMs(long v) { receiptPollMs = v; }
        public int getReceiptAttempts() { return receiptAttempts; }
        public void setReceiptAttempts(int v) { receiptAttempts = v; }
    }

    public static class Token {
        /** HSP network name for the cart mandate: hashkey-testnet / hashkey. Live mode re-reads it from get_chain_config. */
        private String network = "hashkey-testnet";
        /** USDC on HashKey Chain testnet per HSP docs (EIP-3009). Mainnet USDC: 0x054ed45810DbBAb8B27668922D110669c9D88D0a */
        private String address = "0x8FE3cB719Ee4410E236Cd6b72ab1fCDC06eF53c6";
        private String symbol = "USDC";
        private int decimals = 6;
        private String protocol = "eip3009";

        public String getNetwork() { return network; }
        public void setNetwork(String v) { network = v; }
        public String getAddress() { return address; }
        public void setAddress(String v) { address = v; }
        public String getSymbol() { return symbol; }
        public void setSymbol(String v) { symbol = v; }
        public int getDecimals() { return decimals; }
        public void setDecimals(int v) { decimals = v; }
        public String getProtocol() { return protocol; }
        public void setProtocol(String v) { protocol = v; }
    }

    public static class Hsp {
        /** mock = local simulation of the 8 HSP MCP tools; mcp = HSP Agentic Payment server (needs IP allowlist). */
        private String mode = "mock";
        private String mcpUrl = "https://mcp-qa.hashkeymerchant.com/";
        /** Team attribution name used in create_order / query_payment. Must match the DoraHacks team name. */
        private String merchantName = "HSK A2A Pay";
        private int pollSeconds = 15;
        private int pendingAfterMinutes = 10;
        /** Optional: HSP Merchant REST API (own merchant account). */
        private String restBaseUrl = "https://merchant-qa.hashkeymerchant.com";
        private String appKey = "";
        private String appSecret = "";
        private String merchantPrivateKeyPem = "";

        public String getMode() { return mode; }
        public void setMode(String v) { mode = v; }
        public String getMcpUrl() { return mcpUrl; }
        public void setMcpUrl(String v) { mcpUrl = v; }
        public String getMerchantName() { return merchantName; }
        public void setMerchantName(String v) { merchantName = v; }
        public int getPollSeconds() { return pollSeconds; }
        public void setPollSeconds(int v) { pollSeconds = v; }
        public int getPendingAfterMinutes() { return pendingAfterMinutes; }
        public void setPendingAfterMinutes(int v) { pendingAfterMinutes = v; }
        public String getRestBaseUrl() { return restBaseUrl; }
        public void setRestBaseUrl(String v) { restBaseUrl = v; }
        public String getAppKey() { return appKey; }
        public void setAppKey(String v) { appKey = v; }
        public String getAppSecret() { return appSecret; }
        public void setAppSecret(String v) { appSecret = v; }
        public String getMerchantPrivateKeyPem() { return merchantPrivateKeyPem; }
        public void setMerchantPrivateKeyPem(String v) { merchantPrivateKeyPem = v; }
        public boolean live() { return "mcp".equalsIgnoreCase(mode); }
    }

    public static class Receiver {
        private String agentName = "HashKey Wallet Insight Agent";
        /** Payee wallet for the receiving agent (HSP pay_to). */
        private String payTo = "";
        /** Key the receiving agent signs its payment terms with (EIP-191). Env only. */
        private String signingPrivateKey = "";
        private BigDecimal priceUsd = new BigDecimal("0.10");
        private int termsTtlMinutes = 60;
        /** Public base URL of this service (agent card links). Blank = derived from the request. */
        private String publicBaseUrl = "";

        public String getAgentName() { return agentName; }
        public void setAgentName(String v) { agentName = v; }
        public String getPayTo() { return payTo; }
        public void setPayTo(String v) { payTo = v; }
        public String getSigningPrivateKey() { return signingPrivateKey; }
        public void setSigningPrivateKey(String v) { signingPrivateKey = v; }
        public BigDecimal getPriceUsd() { return priceUsd; }
        public void setPriceUsd(BigDecimal v) { priceUsd = v; }
        public int getTermsTtlMinutes() { return termsTtlMinutes; }
        public void setTermsTtlMinutes(int v) { termsTtlMinutes = v; }
        public String getPublicBaseUrl() { return publicBaseUrl; }
        public void setPublicBaseUrl(String v) { publicBaseUrl = v; }
    }

    public static class Payer {
        /** Payer wallet key that signs the EIP-712 payment authorization. Env only. */
        private String privateKey = "";
        /** Receiving agent base URL. Blank = this same service (demo runs both agents in one process). */
        private String receiverAgentUrl = "";
        private BigDecimal maxPerPaymentUsd = new BigDecimal("1.00");
        private BigDecimal dailyCapUsd = new BigDecimal("5.00");
        /** Pinned payees the payment agent may pay. Empty = only the receiver's configured payTo. */
        private List<String> allowedPayTo = new ArrayList<>();
        /** Allowed slack over the quoted price (gas when gas_fee_advanced=false), percent. */
        private int maxOverPricePct = 10;
        private int maxAuthorizationSeconds = 3600;
        /** If true, approvals are automatic while inside policy (unattended agent mode). Default: a human approves. */
        private boolean autoApprove = false;

        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String v) { privateKey = v; }
        public String getReceiverAgentUrl() { return receiverAgentUrl; }
        public void setReceiverAgentUrl(String v) { receiverAgentUrl = v; }
        public BigDecimal getMaxPerPaymentUsd() { return maxPerPaymentUsd; }
        public void setMaxPerPaymentUsd(BigDecimal v) { maxPerPaymentUsd = v; }
        public BigDecimal getDailyCapUsd() { return dailyCapUsd; }
        public void setDailyCapUsd(BigDecimal v) { dailyCapUsd = v; }
        public List<String> getAllowedPayTo() { return allowedPayTo; }
        public void setAllowedPayTo(List<String> v) { allowedPayTo = v; }
        public int getMaxOverPricePct() { return maxOverPricePct; }
        public void setMaxOverPricePct(int v) { maxOverPricePct = v; }
        public int getMaxAuthorizationSeconds() { return maxAuthorizationSeconds; }
        public void setMaxAuthorizationSeconds(int v) { maxAuthorizationSeconds = v; }
        public boolean isAutoApprove() { return autoApprove; }
        public void setAutoApprove(boolean v) { autoApprove = v; }
    }
}
