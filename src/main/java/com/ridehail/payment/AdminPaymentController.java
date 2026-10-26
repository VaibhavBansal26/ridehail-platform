package com.ridehail.payment;

import com.ridehail.ledger.LedgerService;
import com.ridehail.ledger.LedgerService.TrialBalance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

/** Finance operations. Role-gated to ADMIN in SecurityConfig. */
@RestController
@RequestMapping("/api/admin")
class AdminPaymentController {

    private final PaymentRepository payments;
    private final PaymentService paymentService;
    private final PayoutRepository payouts;
    private final LedgerService ledger;

    AdminPaymentController(PaymentRepository payments, PaymentService paymentService, PayoutRepository payouts,
                           LedgerService ledger) {
        this.payments = payments;
        this.paymentService = paymentService;
        this.payouts = payouts;
        this.ledger = ledger;
    }

    record PageView<T>(List<T> items, long total, int page, int size) {
        static <S, T> PageView<T> of(Page<S> p, Function<S, T> map) {
            return new PageView<>(p.getContent().stream().map(map).toList(), p.getTotalElements(), p.getNumber(), p.getSize());
        }
    }

    record PaymentView(UUID id, UUID tripId, UUID customerId, UUID riderId, BigDecimal amount, BigDecimal commission,
                       BigDecimal riderAmount, String currency, PaymentStatus status, int attempts, String failureReason) {
        static PaymentView of(Payment p) {
            return new PaymentView(p.getId(), p.getTripId(), p.getCustomerId(), p.getRiderId(), p.getAmount(),
                    p.getCommissionAmount(), p.getRiderAmount(), p.getCurrency(), p.getStatus(), p.getAttempts(), p.getFailureReason());
        }
    }

    record PayoutView(UUID id, UUID riderId, BigDecimal amount, PayoutStatus status, int attempts, String failureReason,
                      Instant createdAt) {
        static PayoutView of(Payout p) {
            return new PayoutView(p.getId(), p.getRiderId(), p.getAmount(), p.getStatus(), p.getAttempts(),
                    p.getFailureReason(), p.getCreatedAt());
        }
    }

    @GetMapping("/payments")
    PageView<PaymentView> payments(@RequestParam(required = false) PaymentStatus status,
                                   @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageView.of(status == null ? payments.findAll(paging) : payments.findByStatus(status, paging), PaymentView::of);
    }

    @PostMapping("/payments/{id}/retry")
    PaymentView retry(@PathVariable UUID id) {
        return PaymentView.of(paymentService.retry(id));
    }

    @PostMapping("/payments/{id}/refund")
    PaymentView refund(@PathVariable UUID id) {
        return PaymentView.of(paymentService.refund(id));
    }

    @GetMapping("/payouts")
    PageView<PayoutView> payouts(@RequestParam(required = false) PayoutStatus status,
                                 @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageView.of(status == null ? payouts.findAll(paging) : payouts.findByStatus(status, paging), PayoutView::of);
    }

    /** Total debits must equal total credits; anything else means the books are corrupt. */
    @GetMapping("/ledger/trial-balance")
    TrialBalance trialBalance() {
        return ledger.trialBalance();
    }
}
