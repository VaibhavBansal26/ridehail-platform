package com.ridehail.rider;

import com.ridehail.common.ApiExceptions.ConflictException;
import com.ridehail.common.VehicleType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A rider is the driver who accepts customers' trip requests. */
@Entity
@Table(name = "riders", indexes = @Index(name = "idx_rider_avail", columnList = "status,vehicleType,lat,lon"))
@Getter @Setter @NoArgsConstructor
public class Rider {
    @Id private UUID id = UUID.randomUUID();
    /** Optimistic lock: two trips can never claim the same rider concurrently. */
    @Version private Long version;

    private String name;
    private String phone;
    @Enumerated(EnumType.STRING) private VehicleType vehicleType;
    @Enumerated(EnumType.STRING) private RiderStatus status = RiderStatus.OFFLINE;
    private Double lat;
    private Double lon;
    /** Opaque provider token for the rider's bank account / payout destination. */
    private String payoutAccountToken;
    /** The rider's connected account at the provider (e.g. Stripe acct_...), created at onboarding start. */
    private String providerAccountId;
    private double rating = 5.0;
    private Instant locationUpdatedAt;

    public Rider(String name, String phone, VehicleType vehicleType) {
        this.name = name;
        this.phone = phone;
        this.vehicleType = vehicleType;
    }

    public void goOnline(double lat, double lon, Instant now) {
        if (status == RiderStatus.ON_TRIP) throw new ConflictException("Rider is on a trip");
        updateLocation(lat, lon, now);
        this.status = RiderStatus.AVAILABLE;
    }

    public void goOffline() {
        if (status == RiderStatus.ON_TRIP) throw new ConflictException("Finish the current trip first");
        this.status = RiderStatus.OFFLINE;
    }

    public void updateLocation(double lat, double lon, Instant now) {
        this.lat = lat;
        this.lon = lon;
        this.locationUpdatedAt = now;
    }

    public void claimForTrip() {
        if (status != RiderStatus.AVAILABLE) throw new ConflictException("Rider is not available");
        this.status = RiderStatus.ON_TRIP;
    }

    public void release() {
        if (status == RiderStatus.ON_TRIP) this.status = RiderStatus.AVAILABLE;
    }
}
