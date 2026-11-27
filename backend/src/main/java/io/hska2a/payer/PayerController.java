package io.hska2a.payer;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runs")
public class PayerController {
    private final PaymentAgentService agent;

    public PayerController(PaymentAgentService agent) {
        this.agent = agent;
    }

    public record StartRequest(String target) {}

    @PostMapping
    public PaymentRun start(@RequestBody StartRequest r) { return agent.start(r.target()); }

    @GetMapping
    public List<PaymentRun> list() { return agent.list(); }

    @GetMapping("/{id}")
    public PaymentRun get(@PathVariable String id) { return agent.get(id); }

    @PostMapping("/{id}/approve")
    public PaymentRun approve(@PathVariable String id, @RequestParam String stage) { return agent.approve(id, stage); }

    @PostMapping("/{id}/reject")
    public PaymentRun reject(@PathVariable String id) { return agent.reject(id); }
}
