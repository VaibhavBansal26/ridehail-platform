package com.ridehail.payment;

import com.ridehail.common.ApiExceptions.ForbiddenException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/customers/me")
class CustomerPaymentController {

    private final CustomerRepository customers;
    private final OnboardingGateway onboarding;
    private final TransactionTemplate tx;

    CustomerPaymentController(CustomerRepository customers, OnboardingGateway onboarding, TransactionTemplate tx) {
        this.customers = customers;
        this.onboarding = onboarding;
        this.tx = tx;
    }

    record PaymentMethodRequest(@NotBlank @Size(max = 200) String token) {}
    record CardSetupResponse(String clientSecret, String publishableKey) {}
    record CardCheckoutResponse(String url, java.time.Instant expiresAt) {}
    record CustomerResponse(UUID id, String name, boolean hasPaymentMethod) {}

    @GetMapping
    @Transactional(readOnly = true)
    CustomerResponse me(@AuthenticationPrincipal Jwt jwt) {
        var c = find(jwt);
        return new CustomerResponse(c.getId(), c.getName(), c.getPaymentMethodToken() != null);
    }

    /**
     * Step 1 of saving a card with a real provider: returns a client secret for the provider's mobile SDK, which
     * collects the card directly. The saved card reaches us afterwards via the provider's webhook, and
     * GET /api/customers/me then reports hasPaymentMethod=true.
     */
    @PostMapping("/payment-method/setup")
    CardSetupResponse beginCardSetup(@AuthenticationPrincipal Jwt jwt) {
        UUID id = CurrentUser.profileId(jwt);
        String existing = tx.execute(s -> find(jwt).getProviderCustomerId());
        var setup = onboarding.beginCardSetup(id, existing); // provider call happens outside any DB transaction
        tx.executeWithoutResult(s -> find(jwt).setProviderCustomerId(setup.providerCustomerId()));
        return new CardSetupResponse(setup.clientSecret(), setup.publishableKey());
    }

    /**
     * Saving a card on the provider's own hosted page: open the returned {@code url} (in the system browser; the card is never
     * typed into our app). The saved card reaches us afterwards via the provider's webhook, and GET /api/customers/me then
     * reports hasPaymentMethod=true.
     */
    @PostMapping("/payment-method/checkout")
    CardCheckoutResponse beginCardCheckout(@AuthenticationPrincipal Jwt jwt) {
        UUID id = CurrentUser.profileId(jwt);
        String existing = tx.execute(s -> find(jwt).getProviderCustomerId());
        var checkout = onboarding.beginCardCheckout(id, existing); // provider call happens outside any DB transaction
        tx.executeWithoutResult(s -> find(jwt).setProviderCustomerId(checkout.providerCustomerId()));
        return new CardCheckoutResponse(checkout.url(), checkout.expiresAt());
    }

    /**
     * Dev/test shortcut: the client submits a token directly. Refused with a real provider, where tokens may only
     * come from the verified webhook (otherwise a client could attach someone else's card).
     */
    @PutMapping("/payment-method")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void setPaymentMethod(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PaymentMethodRequest r) {
        if (!onboarding.acceptsClientSuppliedTokens()) {
            throw new ForbiddenException("Use POST /api/customers/me/payment-method/setup to add a card");
        }
        find(jwt).setPaymentMethodToken(r.token());
    }

    private com.ridehail.customer.Customer find(Jwt jwt) {
        UUID id = CurrentUser.profileId(jwt);
        return customers.findById(id).orElseThrow(() -> new NotFoundException("Customer", id));
    }
}
