package io.hska2a.receiver;

/** Payment terms the receiving agent hands to a paying agent (signed with EIP-191 over canonical JSON). */
public record PaymentTerms(
        String termsId,
        String cartMandateId,
        String paymentRequestId,
        String merchantName,
        String agentName,
        String skill,
        String target,
        String payTo,
        String priceUsd,
        String network,
        long chainId,
        String token,
        String tokenSymbol,
        int tokenDecimals,
        String protocol,
        String amountRaw,
        String serviceHash,
        String expiresAt,
        String settlementContract) {}
