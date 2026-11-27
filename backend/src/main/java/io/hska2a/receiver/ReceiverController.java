package io.hska2a.receiver;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
public class ReceiverController {
    private final ReceivingAgentService receiver;
    private final io.hska2a.config.HskProperties props;

    public ReceiverController(ReceivingAgentService receiver, io.hska2a.config.HskProperties props) {
        this.receiver = receiver;
        this.props = props;
    }

    public record QuoteRequest(String skill, String target) {}
    public record SettleRequest(String termsId, String flowId, String paymentRequestId, String payer) {}

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> card(HttpServletRequest req) {
        String base = props.getReceiver().getPublicBaseUrl();
        if (base == null || base.isBlank()) base = ServletUriComponentsBuilder.fromContextPath(req).build().toUriString();
        return receiver.agentCard(base);
    }

    @PostMapping("/a2a/receiver/quote")
    public Map<String, Object> quote(@RequestBody QuoteRequest r) {
        return receiver.quote(r.skill() == null ? ReceivingAgentService.SKILL : r.skill(), r.target());
    }

    @PostMapping("/a2a/receiver/settle")
    public Map<String, Object> settle(@RequestBody SettleRequest r) {
        return receiver.settle(r.termsId(), r.flowId(), r.paymentRequestId(), r.payer());
    }
}
