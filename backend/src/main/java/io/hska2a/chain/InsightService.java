package io.hska2a.chain;

import io.hska2a.common.Amounts;
import io.hska2a.config.HskProperties;
import java.math.BigInteger;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.http.HttpService;

/** The paid resource the receiving agent sells: a live HashKey Chain wallet snapshot read from RPC. */
@Service
public class InsightService {
    private final Web3j web3j;
    private final long chainId;

    public InsightService(HskProperties props) {
        this.web3j = Web3j.build(new HttpService(props.getChain().getInsightRpcUrl()));
        this.chainId = props.getChain().getInsightChainId();
    }

    public Map<String, Object> walletInsight(String address) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("address", address);
        m.put("chainId", chainId);
        try {
            BigInteger bal = web3j.ethGetBalance(address, DefaultBlockParameterName.LATEST).send().getBalance();
            BigInteger nonce = web3j.ethGetTransactionCount(address, DefaultBlockParameterName.LATEST).send().getTransactionCount();
            String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
            BigInteger block = web3j.ethBlockNumber().send().getBlockNumber();
            boolean contract = code != null && code.length() > 2;
            m.put("hskBalance", Amounts.fromRaw(bal, 18).stripTrailingZeros().toPlainString());
            m.put("txCount", nonce);
            m.put("isContract", contract);
            m.put("block", block);
            String tier = contract ? "contract" : nonce.compareTo(BigInteger.valueOf(100)) > 0 ? "active" : nonce.signum() > 0 ? "light" : "fresh";
            m.put("activityTier", tier);
            m.put("riskNote", nonce.signum() == 0 && !contract ? "No outgoing history on HashKey Chain: treat as unverified counterparty." : "Has on-chain history.");
        } catch (Exception e) {
            m.put("error", "rpc: " + e.getMessage());
        }
        m.put("generatedAt", Instant.now().toString());
        return m;
    }
}
