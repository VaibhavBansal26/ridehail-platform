package com.ridehail.payment.stripe;

import com.ridehail.payment.stripe.StripeWebhookService.InvalidWebhookException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public endpoint (no JWT) that Stripe calls. Takes the body as a raw string because the signature is computed
 * over the exact bytes Stripe sent; anything re-serialised would fail verification.
 */
@RestController
@RequestMapping("/api/webhooks")
@ConditionalOnProperty(name = "ridehail.payments.gateway", havingValue = "stripe")
class StripeWebhookController {

    private final StripeWebhookService webhooks;

    StripeWebhookController(StripeWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @PostMapping("/stripe")
    ResponseEntity<String> receive(@RequestBody String payload,
                                   @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        if (signature == null) return ResponseEntity.badRequest().body("Missing signature");
        try {
            return ResponseEntity.ok(webhooks.handle(payload, signature).name());
        } catch (InvalidWebhookException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (RuntimeException e) {
            // our fault: tell Stripe to retry later
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing failed");
        }
    }
}
