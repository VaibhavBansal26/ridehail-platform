package com.ridehail.trip;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.VehicleType;
import com.ridehail.pricing.FareQuote;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "trips", uniqueConstraints = @UniqueConstraint(columnNames = "quoteId"))
@Getter @NoArgsConstructor
public class Trip {
    @Id private UUID id = UUID.randomUUID();
    @Version private Long version;

    private UUID customerId;
    private UUID riderId;
    private UUID quoteId;
    @Enumerated(EnumType.STRING) private VehicleType vehicleType;
    @Enumerated(EnumType.STRING) private TripStatus status = TripStatus.REQUESTED;

    private double pickupLat;
    private double pickupLon;
    private double dropLat;
    private double dropLon;

    /** Locked from the quote at booking time: the customer pays what they were shown. */
    @Column(precision = 12, scale = 2) private BigDecimal fare;
    @Column(precision = 4, scale = 1) private BigDecimal surgeMultiplier;
    private String currency;

    private int offersSent;
    private Instant requestedAt;
    private Instant acceptedAt;
    private Instant startedAt;
    private Instant completedAt;

    public static Trip fromQuote(FareQuote q, Instant now) {
        var t = new Trip();
        t.customerId = q.getCustomerId();
        t.quoteId = q.getId();
        t.vehicleType = q.getVehicleType();
        t.pickupLat = q.getPickupLat();
        t.pickupLon = q.getPickupLon();
        t.dropLat = q.getDropLat();
        t.dropLon = q.getDropLon();
        t.fare = q.getTotal();
        t.surgeMultiplier = q.getSurgeMultiplier();
        t.currency = q.getCurrency();
        t.requestedAt = now;
        return t;
    }

    public void offerSent() { offersSent++; }

    public void accept(UUID riderId, Instant now) {
        transition(TripStatus.ACCEPTED);
        this.riderId = riderId;
        this.acceptedAt = now;
    }

    public void arrive() { transition(TripStatus.ARRIVED); }

    public void start(Instant now) {
        transition(TripStatus.IN_PROGRESS);
        this.startedAt = now;
    }

    public void complete(Instant now) {
        transition(TripStatus.COMPLETED);
        this.completedAt = now;
    }

    public void cancel() { transition(TripStatus.CANCELLED); }

    public void noRidersAvailable() { transition(TripStatus.NO_RIDERS_AVAILABLE); }

    private void transition(TripStatus target) {
        if (!status.canMoveTo(target)) {
            throw new ConflictException("Trip cannot go from " + status + " to " + target);
        }
        this.status = target;
    }
}
