package com.ridehail.payment.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.ridehail.dispute.DisputeNotice;
import com.ridehail.dispute.DisputeNotice.State;
import java.math.BigDecimal;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Turns a Stripe dispute object into the provider-neutral notice the dispute logic understands. */
final class StripeDisputeMapper {
    private static final Logger log = LoggerFactory.getLogger(StripeDisputeMapper.class);

    private StripeDisputeMapper() {}

    static DisputeNotice toNotice(JsonNode dispute, boolean livemode) {
        String id = dispute.path("id").asText();
        String currency = dispute.path("currency").asText("usd");
        String status = dispute.path("status").asText("");
        return new DisputeNotice(
                id,
                dispute.path("payment_intent").asText(null),
                StripeMoney.fromMinorUnits(dispute.path("amount").asLong(), currency),
                currency.toUpperCase(),
                state(status, id),
                status,
                dispute.path("reason").asText(null),
                evidenceDueBy(dispute),
                fees(dispute, currency),
                "https://dashboard.stripe.com/" + (livemode ? "" : "test/") + "disputes/" + id);
    }

    /**
     * Stripe's statuses: "warning_*" are inquiries (no money moved); "needs_response"/"under_review" are real chargebacks
     * (money withdrawn); "won"/"lost" are decisions.
     */
    static State state(String status, String disputeId) {
        return switch (status) {
            case "warning_needs_response", "warning_under_review" -> State.INQUIRY;
            case "needs_response", "under_review" -> State.OPEN;
            case "won" -> State.WON;
            case "lost" -> State.LOST;
            case "warning_closed", "prevented" -> State.CLOSED;
            default -> {
                // Never guess about money: an unknown status is kept as an inquiry (no ledger effect) and flagged loudly.
                log.error("Dispute {} has a status we do not know: '{}'", disputeId, status);
                yield State.INQUIRY;
            }
        };
    }

    private static Instant evidenceDueBy(JsonNode dispute) {
        long epoch = dispute.path("evidence_details").path("due_by").asLong(0);
        return epoch > 0 ? Instant.ofEpochSecond(epoch) : null;
    }

    /** Net fees Stripe has charged for this dispute (a refunded fee shows as a negative fee on a later balance transaction). */
    private static BigDecimal fees(JsonNode dispute, String currency) {
        long minor = 0;
        for (JsonNode bt : dispute.path("balance_transactions")) {
            if (bt.isObject()) minor += bt.path("fee").asLong(0);   // an unexpanded id string carries no amounts
        }
        return StripeMoney.fromMinorUnits(Math.max(minor, 0), currency);
    }
}
