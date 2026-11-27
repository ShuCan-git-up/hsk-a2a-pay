package io.hska2a.payer;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One purchase made by the paying agent, with a human-readable timeline. */
public class PaymentRun {
    public enum State {
        DISCOVERING, QUOTED, ORDER_CREATED, AWAITING_SIGN_APPROVAL, AWAITING_SUBMIT_APPROVAL,
        SUBMITTED, PENDING_CONFIRMATION, SETTLED, DELIVERED, REJECTED, FAILED
    }

    public record Step(String at, String name, String detail, Map<String, Object> data) {}

    private final String id;
    private final String target;
    private final String createdAt = Instant.now().toString();
    private volatile State state = State.DISCOVERING;
    private final List<Step> timeline = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, Object> summary = Collections.synchronizedMap(new LinkedHashMap<>());

    // working values (not serialized)
    @JsonIgnore JsonNode terms;
    @JsonIgnore String flowId;
    @JsonIgnore JsonNode prepared;
    @JsonIgnore JsonNode finalized;

    public PaymentRun(String id, String target) {
        this.id = id;
        this.target = target;
    }

    public String getId() { return id; }
    public String getTarget() { return target; }
    public String getCreatedAt() { return createdAt; }
    public State getState() { return state; }
    public List<Step> getTimeline() { synchronized (timeline) { return new ArrayList<>(timeline); } }
    public Map<String, Object> getSummary() { synchronized (summary) { return new LinkedHashMap<>(summary); } }

    void state(State s) { state = s; }

    void step(String name, String detail, Map<String, Object> data) {
        timeline.add(new Step(Instant.now().toString(), name, detail, data == null ? Map.of() : data));
    }

    void put(String k, Object v) { if (v != null) summary.put(k, v); }
}
