package com.ridehail.payment;

import com.ridehail.common.ApiExceptions.ForbiddenException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.ledger.Direction;
import com.ridehail.ledger.LedgerService;
import com.ridehail.ledger.TxKind;
import com.ridehail.pricing.PricingProperties;
import com.ridehail.rider.RiderRepository;
import com.ridehail.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

/** A rider's money: balance, history, payout account, and withdrawals. */
@RestController
@RequestMapping("/api/riders/me")
class RiderEarningsController {

    private final RiderRepository riders;
    private final LedgerService ledger;
    private final PayoutService payoutService;
    private final PayoutRepository payouts;
    private final PricingProperties pricing;
    private final OnboardingGateway onboarding;
    private final TransactionTemplate tx;

    RiderEarningsController(RiderRepository riders, LedgerService ledger, PayoutService payoutService,
                            PayoutRepository payouts, PricingProperties pricing, OnboardingGateway onboarding,
                            TransactionTemplate tx) {
        this.onboarding = onboarding;
        this.tx = tx;
        this.riders = riders;
        this.ledger = ledger;
        this.payoutService = payoutService;
        this.payouts = payouts;
        this.pricing = pricing;
    }

    record PayoutAccountRequest(@NotBlank @Size(max = 200) String token) {}
    record OnboardingResponse(String url, Instant expiresAt) {}
    record PayoutRequest(@DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount) {}
    record PayoutView(UUID id, BigDecimal amount, String currency, PayoutStatus status, String failureReason,
                      Instant createdAt, Instant completedAt) {
        static PayoutView of(Payout p) {
            return new PayoutView(p.getId(), p.getAmount(), p.getCurrency(), p.getStatus(), p.getFailureReason(),
                    p.getCreatedAt(), p.getCompletedAt());
        }
    }
    record LedgerLine(Instant at, Direction direction, BigDecimal amount, TxKind kind, String description) {}
    record Earnings(BigDecimal available, BigDecimal payoutsInProgress, String currency, boolean hasPayoutAccount,
                    List<LedgerLine> recent) {}

    /**
     * Starts payout onboarding with a real provider: the rider opens the returned URL to give their identity and
     * bank details to the provider (not to us). Once the provider reports the account can receive funds, a webhook
     * enables payouts; until then withdrawals are refused.
     */
    @PostMapping("/payout-account/onboarding")
    OnboardingResponse beginOnboarding(@AuthenticationPrincipal Jwt jwt) {
        UUID id = CurrentUser.profileId(jwt);
        String existing = tx.execute(s -> findRider(id).getProviderAccountId());
        var onboard = this.onboarding.beginPayoutOnboarding(id, existing); // provider call outside any DB transaction
        tx.executeWithoutResult(s -> findRider(id).setProviderAccountId(onboard.providerAccountId()));
        return new OnboardingResponse(onboard.url(), onboard.expiresAt());
    }

    /** Dev/test shortcut; refused with a real provider (a rider could otherwise point payouts at any account). */
    @PutMapping("/payout-account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void setPayoutAccount(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PayoutAccountRequest r) {
        if (!onboarding.acceptsClientSuppliedTokens()) {
            throw new ForbiddenException("Use POST /api/riders/me/payout-account/onboarding to set up payouts");
        }
        UUID id = CurrentUser.profileId(jwt);
        riders.findById(id).orElseThrow(() -> new NotFoundException("Rider", id)).setPayoutAccountToken(r.token());
    }

    @GetMapping("/earnings")
    @Transactional(readOnly = true)
    Earnings earnings(@AuthenticationPrincipal Jwt jwt) {
        UUID id = CurrentUser.profileId(jwt);
        var rider = riders.findById(id).orElseThrow(() -> new NotFoundException("Rider", id));
        var account = ledger.riderAccount(id);
        BigDecimal inProgress = payouts.findByRiderIdAndStatusIn(id, EnumSet.of(PayoutStatus.PENDING, PayoutStatus.REQUIRES_ATTENTION))
                .stream().map(Payout::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        var recent = ledger.recent(account).stream()
                .map(e -> new LedgerLine(e.at(), e.direction(), e.amount(), e.kind(), e.description())).toList();
        return new Earnings(ledger.balance(account), inProgress, pricing.currency(), rider.getPayoutAccountToken() != null, recent);
    }

    private com.ridehail.rider.Rider findRider(UUID id) {
        return riders.findById(id).orElseThrow(() -> new NotFoundException("Rider", id));
    }

    @GetMapping("/payouts")
    List<PayoutView> myPayouts(@AuthenticationPrincipal Jwt jwt) {
        return payouts.findTop20ByRiderIdOrderByCreatedAtDesc(CurrentUser.profileId(jwt)).stream().map(PayoutView::of).toList();
    }

    /** Withdraw `amount`, or everything available when the body is empty. */
    @PostMapping("/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    PayoutView requestPayout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody(required = false) PayoutRequest r) {
        return PayoutView.of(payoutService.request(CurrentUser.profileId(jwt), r == null ? null : r.amount()));
    }
}
