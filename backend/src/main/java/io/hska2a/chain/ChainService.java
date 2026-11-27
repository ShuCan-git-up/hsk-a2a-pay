package io.hska2a.chain;

import io.hska2a.common.Eth;
import io.hska2a.config.HskProperties;
import io.hska2a.crypto.Signers;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint64;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;

/** Writes and reads the A2ASettlement contract on HashKey Chain with the operator key. */
@Service
public class ChainService {
    public record TxResult(String txHash, String explorerUrl) {}

    private static final Logger log = LoggerFactory.getLogger(ChainService.class);
    private final HskProperties.Chain cfg;
    private final Web3j web3j;
    private final Credentials operator;
    private RawTransactionManager txManager;

    public ChainService(HskProperties props) {
        this.cfg = props.getChain();
        this.web3j = Web3j.build(new HttpService(cfg.getRpcUrl()));
        this.operator = Signers.credentials(cfg.getOperatorPrivateKey());
    }

    public boolean enabled() {
        return operator != null && Eth.isAddress(cfg.getContractAddress());
    }

    public String operatorAddress() {
        return operator == null ? null : Eth.checksum(operator.getAddress());
    }

    public String txUrl(String hash) {
        return cfg.getExplorerUrl() + "/tx/" + hash;
    }

    public String addressUrl(String address) {
        return cfg.getExplorerUrl() + "/address/" + address;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rpcUrl", cfg.getRpcUrl());
        m.put("chainId", cfg.getChainId());
        m.put("contract", cfg.getContractAddress());
        m.put("contractUrl", Eth.isAddress(cfg.getContractAddress()) ? addressUrl(cfg.getContractAddress()) : null);
        m.put("operator", operatorAddress());
        m.put("enabled", enabled());
        if (operator != null) {
            try {
                m.put("operatorBalanceWei", web3j.ethGetBalance(operator.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance().toString());
            } catch (Exception e) {
                m.put("operatorBalanceWei", "rpc error: " + e.getMessage());
            }
        }
        if (enabled()) {
            try {
                m.put("termsCount", callUint("termsCount"));
                m.put("settledCount", callUint("settledCount"));
            } catch (Exception e) {
                m.put("readError", e.getMessage());
            }
        }
        return m;
    }

    public TxResult publishTerms(String termsId, String payTo, String token, BigInteger amount, long expiresAt, String serviceHash) {
        return send(new Function("publishTerms", List.of(new Bytes32(Eth.bytes32(termsId)), new Address(payTo), new Address(token),
                new Uint256(amount), new Uint64(BigInteger.valueOf(expiresAt)), new Bytes32(Eth.bytes32(serviceHash))), List.of()));
    }

    public TxResult recordHspSettlement(String termsId, String payer, BigInteger amountPaid, String hspPaymentRef, String settlementTx) {
        return send(new Function("recordHspSettlement", List.of(new Bytes32(Eth.bytes32(termsId)), new Address(payer),
                new Uint256(amountPaid), new Bytes32(Eth.bytes32(hspPaymentRef)), new Bytes32(Eth.bytes32(settlementTx))), List.of()));
    }

    /** On-chain spend policy of the payer. Returns null when the chain is not configured. */
    @SuppressWarnings("rawtypes")
    public Map.Entry<Boolean, String> checkSpend(String payer, String payTo, BigInteger amount) {
        if (!Eth.isAddress(cfg.getContractAddress())) return null;
        Function f = new Function("checkSpend", List.of(new Address(payer), new Address(payTo), new Uint256(amount)),
                List.of(new TypeReference<Bool>() {}, new TypeReference<Utf8String>() {}));
        List<Type> out = call(f);
        return Map.entry((Boolean) out.get(0).getValue(), (String) out.get(1).getValue());
    }

    /** ERC-20 balanceOf on the settlement chain (used before signing, per the HSP guide). */
    @SuppressWarnings("rawtypes")
    public BigInteger erc20Balance(String token, String owner) {
        Function f = new Function("balanceOf", List.of(new Address(owner)), List.of(new TypeReference<Uint256>() {}));
        try {
            String value = web3j.ethCall(Transaction.createEthCallTransaction(owner, token, FunctionEncoder.encode(f)),
                    DefaultBlockParameterName.LATEST).send().getValue();
            List<Type> out = FunctionReturnDecoder.decode(value, f.getOutputParameters());
            return out.isEmpty() ? BigInteger.ZERO : (BigInteger) out.get(0).getValue();
        } catch (Exception e) {
            throw new IllegalStateException("balanceOf failed: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("rawtypes")
    public int statusOf(String termsId) {
        Function f = new Function("statusOf", List.of(new Bytes32(Eth.bytes32(termsId))), List.of(new TypeReference<Uint8>() {}));
        return ((BigInteger) call(f).get(0).getValue()).intValue();
    }

    @SuppressWarnings("rawtypes")
    private BigInteger callUint(String name) {
        return (BigInteger) call(new Function(name, List.of(), List.of(new TypeReference<Uint256>() {}))).get(0).getValue();
    }

    @SuppressWarnings("rawtypes")
    private List<Type> call(Function f) {
        try {
            String from = operator == null ? "0x0000000000000000000000000000000000000000" : operator.getAddress();
            String value = web3j.ethCall(Transaction.createEthCallTransaction(from, cfg.getContractAddress(), FunctionEncoder.encode(f)),
                    DefaultBlockParameterName.LATEST).send().getValue();
            return FunctionReturnDecoder.decode(value, f.getOutputParameters());
        } catch (Exception e) {
            throw new IllegalStateException("eth_call " + f.getName() + " failed: " + e.getMessage(), e);
        }
    }

    private synchronized TxResult send(Function f) {
        if (!enabled()) throw new IllegalStateException("on-chain anchoring disabled (CONTRACT_ADDRESS / OPERATOR_PRIVATE_KEY not set)");
        try {
            if (txManager == null) txManager = new RawTransactionManager(web3j, operator, cfg.getChainId());
            String data = FunctionEncoder.encode(f);
            BigInteger gasPrice = web3j.ethGasPrice().send().getGasPrice();
            EthEstimateGas est = web3j.ethEstimateGas(Transaction.createEthCallTransaction(operator.getAddress(), cfg.getContractAddress(), data)).send();
            if (est.hasError()) throw new IllegalStateException("estimateGas " + f.getName() + ": " + est.getError().getMessage());
            BigInteger gasLimit = est.getAmountUsed().multiply(BigInteger.valueOf(13)).divide(BigInteger.TEN).add(BigInteger.valueOf(20_000));
            EthSendTransaction sent = txManager.sendTransaction(gasPrice, gasLimit, cfg.getContractAddress(), data, BigInteger.ZERO);
            if (sent.hasError()) throw new IllegalStateException(sent.getError().getMessage());
            String hash = sent.getTransactionHash();
            TransactionReceipt receipt = new PollingTransactionReceiptProcessor(web3j, cfg.getReceiptPollMs(), cfg.getReceiptAttempts())
                    .waitForTransactionReceipt(hash);
            if (!"0x1".equals(receipt.getStatus())) throw new IllegalStateException("reverted: " + hash);
            log.info("{} mined {}", f.getName(), hash);
            return new TxResult(hash, txUrl(hash));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(f.getName() + " failed: " + e.getMessage(), e);
        }
    }
}
