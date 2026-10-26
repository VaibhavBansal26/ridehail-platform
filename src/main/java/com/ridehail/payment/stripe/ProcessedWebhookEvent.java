package com.ridehail.payment.stripe;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Stripe delivers webhooks at-least-once; remembering event ids lets us skip redeliveries. */
@Entity
@Table(name = "processed_webhook_events")
@Getter @NoArgsConstructor
public class ProcessedWebhookEvent {
    @Id private String id;
    private String type;
    private Instant processedAt;

    public ProcessedWebhookEvent(String id, String type, Instant processedAt) {
        this.id = id;
        this.type = type;
        this.processedAt = processedAt;
    }
}
