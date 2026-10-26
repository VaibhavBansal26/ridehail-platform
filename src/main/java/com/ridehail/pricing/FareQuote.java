package com.ridehail.pricing;

import com.ridehail.common.VehicleType;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An upfront price shown to the customer; booking against it locks the fare. */
@Entity
@Table(name = "fare_quotes", indexes = {
        @Index(name = "idx_quote_demand", columnList = "vehicleType,createdAt,pickupLat,pickupLon")})
@Getter @Setter @NoArgsConstructor
public class FareQuote {
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;

    private UUID customerId;
    @Enumerated(EnumType.STRING) private VehicleType vehicleType;
    private double pickupLat;
    private double pickupLon;
    private double dropLat;
    private double dropLon;
    private double distanceKm;
    private double durationMin;

    @Column(precision = 12, scale = 2) private BigDecimal subtotal;
    @Column(precision = 4, scale = 1) private BigDecimal surgeMultiplier;
    @Column(precision = 12, scale = 2) private BigDecimal total;
    private String currency;
    private long demand;
    private long supply;

    private Instant createdAt;
    private Instant expiresAt;
    private boolean used;
}
