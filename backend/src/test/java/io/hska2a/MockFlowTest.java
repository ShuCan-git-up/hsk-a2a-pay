package io.hska2a;

import static org.junit.jupiter.api.Assertions.*;

import io.hska2a.payer.PaymentAgentService;
import io.hska2a.payer.PaymentRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** End-to-end A2A flow in HSP mock mode, both agents talking over real HTTP. Chain anchoring disabled. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "hsk.hsp.mode=mock",
        "hsk.hsp.poll-seconds=1",
        "hsk.chain.contract-address=",
        "hsk.chain.insight-rpc-url=http://127.0.0.1:1",
        "hsk.receiver.signing-private-key=0x5de4111afa1a4b94908f83103eb1f1706367c2e68ca870fc3fb9a804cdab365a",
        "hsk.receiver.pay-to=0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
        "hsk.payer.private-key=0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d"
})
class MockFlowTest {
    @Autowired PaymentAgentService agent;

    PaymentRun waitFor(String id, PaymentRun.State s) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            PaymentRun r = agent.get(id);
            if (r.getState() == s) return r;
            if (r.getState() == PaymentRun.State.FAILED) fail("run failed: " + r.getTimeline());
            Thread.sleep(100);
        }
        fail("timeout waiting for " + s + ", got " + agent.get(id).getState() + " " + agent.get(id).getTimeline());
        return null;
    }

    @Test
    void fullA2aPurchaseWithTwoApprovals() throws Exception {
        PaymentRun run = agent.start("0x70997970C51812dc3A010C7d01b50e0d17dc79C8");
        waitFor(run.getId(), PaymentRun.State.AWAITING_SIGN_APPROVAL);
        assertEquals("100000", agent.get(run.getId()).getSummary().get("tokenTotalRaw"));
        agent.approve(run.getId(), "sign");
        waitFor(run.getId(), PaymentRun.State.AWAITING_SUBMIT_APPROVAL);
        agent.approve(run.getId(), "submit");
        PaymentRun done = waitFor(run.getId(), PaymentRun.State.DELIVERED);
        assertEquals("payment-safe", done.getSummary().get("hspStatus"));
        assertEquals(Boolean.TRUE, done.getSummary().get("simulated"));
        assertNotNull(done.getSummary().get("resource"));
    }

    @Test
    void rejectStopsBeforeSigning() throws Exception {
        PaymentRun run = agent.start("0x70997970C51812dc3A010C7d01b50e0d17dc79C8");
        waitFor(run.getId(), PaymentRun.State.AWAITING_SIGN_APPROVAL);
        agent.reject(run.getId());
        assertEquals(PaymentRun.State.REJECTED, agent.get(run.getId()).getState());
        assertTrue(agent.get(run.getId()).getTimeline().stream().noneMatch(s -> s.name().equals("sign")));
    }
}
