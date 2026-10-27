package com.ridehail.payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ridehail.dispute.DisputeNotice.State;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StripeDisputeMapperTest {

    final ObjectMapper json = new ObjectMapper();

    @Test
    void stripeStatusesMapToTheStatesTheLedgerActsOn() throws Exception {
        assertThat(StripeDisputeMapper.state("warning_needs_response", "dp")).isEqualTo(State.INQUIRY);
        assertThat(StripeDisputeMapper.state("warning_under_review", "dp")).isEqualTo(State.INQUIRY);
        assertThat(StripeDisputeMapper.state("warning_closed", "dp")).isEqualTo(State.CLOSED);
        assertThat(StripeDisputeMapper.state("needs_response", "dp")).isEqualTo(State.OPEN);
        assertThat(StripeDisputeMapper.state("under_review", "dp")).isEqualTo(State.OPEN);
        assertThat(StripeDisputeMapper.state("won", "dp")).isEqualTo(State.WON);
        assertThat(StripeDisputeMapper.state("lost", "dp")).isEqualTo(State.LOST);
        assertThat(StripeDisputeMapper.state("prevented", "dp")).isEqualTo(State.CLOSED);
    }

    @Test
    void anUnknownStatusNeverMovesMoney() {
        assertThat(StripeDisputeMapper.state("something_new", "dp")).isEqualTo(State.INQUIRY);   // no ledger effect, logged at ERROR
        assertThat(StripeDisputeMapper.state("", "dp")).isEqualTo(State.INQUIRY);
    }

    @Test
    void aFullDisputeObjectIsReadIntoANeutralNotice() throws Exception {
        var n = StripeDisputeMapper.toNotice(json.readTree("""
                {"id":"dp_1","object":"dispute","amount":999,"currency":"usd","payment_intent":"pi_1","charge":"ch_1",
                 "reason":"fraudulent","status":"needs_response",
                 "evidence_details":{"due_by":1893456000,"has_evidence":false},
                 "balance_transactions":[{"id":"txn_1","amount":-999,"fee":1500,"net":-2499,"type":"adjustment"}]}"""), false);

        assertThat(n.providerId()).isEqualTo("dp_1");
        assertThat(n.paymentReference()).isEqualTo("pi_1");
        assertThat(n.amount()).isEqualByComparingTo("9.99");
        assertThat(n.currency()).isEqualTo("USD");
        assertThat(n.state()).isEqualTo(State.OPEN);
        assertThat(n.providerStatus()).isEqualTo("needs_response");
        assertThat(n.reason()).isEqualTo("fraudulent");
        assertThat(n.evidenceDueBy()).isEqualTo(Instant.ofEpochSecond(1893456000));
        assertThat(n.feeTotal()).isEqualByComparingTo("15.00");
        assertThat(n.providerUrl()).isEqualTo("https://dashboard.stripe.com/test/disputes/dp_1");
    }

    @Test
    void liveModeDisputesLinkToTheLiveDashboard() throws Exception {
        var n = StripeDisputeMapper.toNotice(json.readTree("{\"id\":\"dp_9\",\"amount\":100,\"currency\":\"usd\",\"status\":\"won\"}"), true);
        assertThat(n.providerUrl()).isEqualTo("https://dashboard.stripe.com/disputes/dp_9");
    }

    @Test
    void aRefundedFeeCancelsOutAndMissingPiecesAreTolerated() throws Exception {
        var refunded = StripeDisputeMapper.toNotice(json.readTree("""
                {"id":"dp_2","amount":999,"currency":"usd","status":"won","payment_intent":"pi_2",
                 "balance_transactions":[{"id":"t1","amount":-999,"fee":1500},{"id":"t2","amount":999,"fee":-1500}]}"""), false);
        assertThat(refunded.feeTotal()).isEqualByComparingTo("0.00");

        var sparse = StripeDisputeMapper.toNotice(json.readTree(
                "{\"id\":\"dp_3\",\"amount\":500,\"currency\":\"usd\",\"status\":\"lost\",\"balance_transactions\":[\"txn_unexpanded\"]}"), false);
        assertThat(sparse.paymentReference()).isNull();       // the caller records it as unmatched rather than guessing
        assertThat(sparse.evidenceDueBy()).isNull();
        assertThat(sparse.feeTotal()).isEqualByComparingTo("0");
    }

    @Test
    void amountsRespectTheCurrencysMinorUnit() throws Exception {
        var yen = StripeDisputeMapper.toNotice(json.readTree("{\"id\":\"dp_4\",\"amount\":1500,\"currency\":\"jpy\",\"status\":\"needs_response\"}"), false);
        assertThat(yen.amount()).isEqualByComparingTo("1500");   // yen has no cents
    }
}
