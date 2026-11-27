package io.hska2a.hsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The eight tools of HSP Agentic Payment (HashKey Settlement Platform MCP server).
 * Arguments and results are passed through as JSON exactly as the live tool schemas define them.
 */
public interface HspGateway {
    String mode();

    /** Health / reachability text for the status page. */
    String health();

    JsonNode getChainConfig();

    /** create_order {merchant_name, pay_to, cart_mandate:{contents}} -> {flow_id, payment_request_id, payment_url} */
    JsonNode createOrder(String merchantName, String payTo, ObjectNode cartMandate);

    JsonNode getOrder(String flowId);

    /** prepare_payment {flow_id, payer, protocol} -> {eip712, cart_hash, token_total_raw, payment_payload, payment_mandate_contents, ...} */
    JsonNode preparePayment(String flowId, String payer, String protocol);

    /** finalize_payment {signature, payment_payload, payment_mandate_contents, cart_hash} -> {payment_mandate_json, ...} */
    JsonNode finalizePayment(String signature, JsonNode paymentPayload, JsonNode paymentMandateContents, String cartHash);

    /** submit_payment {payment_mandate_json} */
    JsonNode submitPayment(JsonNode paymentMandateJson);

    /** query_payment {flow_id, expected_merchant_name} -> {status, attribution_verified, payment:{tx_signature,...}} */
    JsonNode queryPayment(String flowId, String expectedMerchantName);
}
