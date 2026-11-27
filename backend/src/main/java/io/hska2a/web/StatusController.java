package io.hska2a.web;

import io.hska2a.chain.ChainService;
import io.hska2a.config.HskProperties;
import io.hska2a.hsp.HspGateway;
import io.hska2a.payer.PaymentAgentService;
import io.hska2a.receiver.ReceivingAgentService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatusController {
    private final HskProperties props;
    private final HspGateway hsp;
    private final ChainService chain;
    private final PaymentAgentService payer;
    private final ReceivingAgentService receiver;

    public StatusController(HskProperties props, HspGateway hsp, ChainService chain, PaymentAgentService payer, ReceivingAgentService receiver) {
        this.props = props;
        this.hsp = hsp;
        this.chain = chain;
        this.payer = payer;
        this.receiver = receiver;
    }

    @GetMapping("/api/status")
    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("mode", hsp.mode());
        h.put("endpoint", props.getHsp().live() ? props.getHsp().getMcpUrl() : "local simulation");
        h.put("health", hsp.health());
        h.put("merchantName", props.getHsp().getMerchantName());
        m.put("hsp", h);
        m.put("chain", chain.status());
        m.put("token", Map.of("symbol", props.getToken().getSymbol(), "address", props.getToken().getAddress(),
                "network", props.getToken().getNetwork(), "protocol", props.getToken().getProtocol()));
        m.put("receiver", Map.of("agent", props.getReceiver().getAgentName(), "payTo", String.valueOf(receiver.payTo()),
                "termsSigner", String.valueOf(receiver.signerAddress()), "priceUsd", io.hska2a.common.Amounts.usd(props.getReceiver().getPriceUsd())));
        m.put("payer", Map.of("address", String.valueOf(payer.payerAddress()), "maxPerPaymentUsd", props.getPayer().getMaxPerPaymentUsd().toPlainString(),
                "dailyCapUsd", props.getPayer().getDailyCapUsd().toPlainString(), "autoApprove", props.getPayer().isAutoApprove()));
        return m;
    }
}
