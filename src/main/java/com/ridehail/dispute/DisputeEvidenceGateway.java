package com.ridehail.dispute;

import com.ridehail.payment.GatewayResult;

/**
 * Port for answering a dispute at the payment provider. Submitting is final: the provider accepts one response per dispute,
 * so implementations must be idempotent on the key and must not report success unless the evidence was actually submitted.
 */
public interface DisputeEvidenceGateway {
    GatewayResult submitEvidence(String idempotencyKey, String providerDisputeId, DisputeEvidenceFields evidence);
}
