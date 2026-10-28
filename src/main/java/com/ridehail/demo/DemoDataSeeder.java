package com.ridehail.demo;

import com.ridehail.common.VehicleType;
import com.ridehail.customer.Customer;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.dispatch.OfferService;
import com.ridehail.dispatch.OfferStatus;
import com.ridehail.dispatch.TripOffer;
import com.ridehail.dispatch.TripOfferRepository;
import com.ridehail.dispute.DisputeNotice;
import com.ridehail.dispute.DisputeNotice.State;
import com.ridehail.dispute.DisputeService;
import com.ridehail.dispute.IncidentService;
import com.ridehail.dispute.IncidentType;
import com.ridehail.payment.Payment;
import com.ridehail.payment.PaymentRepository;
import com.ridehail.payment.PaymentService;
import com.ridehail.pricing.FareService;
import com.ridehail.rider.Rider;
import com.ridehail.rider.RiderRepository;
import com.ridehail.security.AuthService;
import com.ridehail.trip.Trip;
import com.ridehail.trip.TripRepository;
import com.ridehail.trip.TripService;
import com.ridehail.trip.TripStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Fills a development database with a believable city so the admin panel has something to show: riders on the map,
 * trips in every state, captured payments, disputes and finance incidents. Everything goes through the same services
 * the real API uses, so the data obeys the same rules. Off unless {@code ridehail.demo-data.enabled=true}, and it can
 * never load under the {@code prod} profile.
 */
@Component
@Profile("!prod")
@ConditionalOnProperty(name = "ridehail.demo-data.enabled", havingValue = "true")
@Order(100)
class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final double CENTER_LAT = 12.9716, CENTER_LON = 77.5946;   // Bengaluru
    private static final String PASSWORD = "demo-password-1";

    private final AuthService auth;
    private final RiderRepository riders;
    private final CustomerRepository customers;
    private final TripRepository trips;
    private final TripOfferRepository offers;
    private final PaymentRepository payments;
    private final FareService fares;
    private final TripService tripService;
    private final OfferService offerService;
    private final PaymentService paymentService;
    private final DisputeService disputes;
    private final IncidentService incidents;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    DemoDataSeeder(AuthService auth, RiderRepository riders, CustomerRepository customers, TripRepository trips,
                   TripOfferRepository offers, PaymentRepository payments, FareService fares, TripService tripService,
                   OfferService offerService, PaymentService paymentService, DisputeService disputes,
                   IncidentService incidents, JdbcTemplate jdbc, Clock clock) {
        this.auth = auth;
        this.riders = riders;
        this.customers = customers;
        this.trips = trips;
        this.offers = offers;
        this.payments = payments;
        this.fares = fares;
        this.tripService = tripService;
        this.offerService = offerService;
        this.paymentService = paymentService;
        this.disputes = disputes;
        this.incidents = incidents;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (riders.count() > 0) {
            log.info("Demo data skipped: the database already has riders");
            return;
        }
        Random rnd = new Random(42);
        List<UUID> riderIds = seedRiders(rnd);
        List<UUID> customerIds = seedCustomers();

        // history first (each rider is freed again when its trip completes), then what is happening right now
        List<UUID> completed = new ArrayList<>();
        for (int i = 0; i < 14; i++) completed.add(drive(rnd, customerIds.get(i), riderIds, TripStatus.COMPLETED));
        spreadOverThePastTwoDays(completed, rnd);
        drive(rnd, customerIds.get(14), riderIds, TripStatus.CANCELLED);
        drive(rnd, customerIds.get(15), riderIds, TripStatus.CANCELLED);
        noRidersTrip(customerIds.get(16));
        drive(rnd, customerIds.get(0), riderIds, TripStatus.IN_PROGRESS);
        drive(rnd, customerIds.get(1), riderIds, TripStatus.IN_PROGRESS);
        drive(rnd, customerIds.get(2), riderIds, TripStatus.ARRIVED);
        drive(rnd, customerIds.get(3), riderIds, TripStatus.ACCEPTED);
        drive(rnd, customerIds.get(4), riderIds, TripStatus.REQUESTED);

        seedDisputes(completed);
        seedIncidents(riderIds, completed);
        log.info("Demo data ready: {} riders, {} customers, {} trips. Sign in as admin / the bootstrap admin password.",
                riders.count(), customers.count(), trips.count());
    }

    private List<UUID> seedRiders(Random rnd) {
        String[] names = {"Arjun Rao", "Meera Iyer", "Kabir Shah", "Divya Nair", "Rohan Gupta", "Sana Khan", "Vikram Patel",
                "Ishita Das", "Nikhil Menon", "Priya Reddy", "Aman Verma", "Lakshmi Pillai", "Farhan Ali", "Tara Joshi",
                "Dev Malhotra", "Anika Bose", "Rahul Sethi", "Zoya Mirza"};
        VehicleType[] types = VehicleType.values();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            var result = auth.registerRider(names[i], "demo-rider-" + i, PASSWORD, types[i % types.length]);
            Rider r = riders.findById(result.profileId()).orElseThrow();
            r.setRating(Math.round((4.2 + rnd.nextDouble() * 0.8) * 10) / 10.0);
            if (i < 15) {                                   // the last few stay offline
                double[] at = near(rnd, CENTER_LAT, CENTER_LON, 5.0);
                r.goOnline(at[0], at[1], Instant.now(clock));
            }
            riders.save(r);
            ids.add(r.getId());
        }
        return ids;
    }

    private List<UUID> seedCustomers() {
        String[] names = {"Asha Kapoor", "Bilal Ahmed", "Chitra Rao", "Deepak Jain", "Esha Singh", "Farida Sheikh", "Gaurav Mehta",
                "Hema Krishnan", "Imran Qureshi", "Jaya Prakash", "Kiran Bedi", "Leela Thomas", "Manoj Tiwari", "Nandini Roy",
                "Omkar Desai", "Pooja Hegde", "Qasim Raza", "Ritu Sinha"};
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            var result = auth.registerCustomer(names[i], "demo-customer-" + i, PASSWORD);
            Customer c = customers.findById(result.profileId()).orElseThrow();
            c.setPaymentMethodToken("tok_ok");
            customers.save(c);
            ids.add(c.getId());
        }
        return ids;
    }

    /** Books a trip near an available rider and takes it as far as {@code until}. Returns the trip id. */
    private UUID drive(Random rnd, UUID customerId, List<UUID> riderIds, TripStatus until) {
        Rider anchor = riders.findAllById(riderIds).stream()
                .filter(r -> r.getStatus() == com.ridehail.rider.RiderStatus.AVAILABLE).findAny().orElseThrow();
        double[] pickup = near(rnd, anchor.getLat(), anchor.getLon(), 1.5);
        double[] drop = near(rnd, CENTER_LAT, CENTER_LON, 7.0);
        var quote = fares.quote(customerId, anchor.getVehicleType(), pickup[0], pickup[1], drop[0], drop[1]);
        Trip trip = tripService.request(customerId, quote.getId());

        if (until == TripStatus.REQUESTED) return trip.getId();
        TripOffer offer = offers.findByTripIdAndStatus(trip.getId(), OfferStatus.PENDING).stream().findFirst().orElseThrow();
        UUID riderId = offer.getRiderId();
        if (until == TripStatus.CANCELLED) {
            tripService.cancel(trip.getId(), customerId);
            return trip.getId();
        }
        offerService.accept(offer.getId(), riderId);
        if (until == TripStatus.ACCEPTED) return trip.getId();
        tripService.riderArrived(trip.getId(), riderId);
        if (until == TripStatus.ARRIVED) return trip.getId();
        tripService.start(trip.getId(), riderId);
        if (until == TripStatus.IN_PROGRESS) return trip.getId();
        tripService.complete(trip.getId(), riderId);
        paymentService.captureForTrip(trip.getId());    // settle now rather than in the background, disputes need the charge
        return trip.getId();
    }

    /** A request in a town with no riders: dispatch gives up and the trip ends NO_RIDERS_AVAILABLE. */
    private void noRidersTrip(UUID customerId) {
        var quote = fares.quote(customerId, VehicleType.SEDAN, 19.0760, 72.8777, 19.1136, 72.8697);   // Mumbai
        tripService.request(customerId, quote.getId());
    }

    /** Everything above happened in the last second; stagger the finished trips over two days so lists look lived-in. */
    private void spreadOverThePastTwoDays(List<UUID> tripIds, Random rnd) {
        Instant now = Instant.now(clock);
        for (int i = 0; i < tripIds.size(); i++) {
            Instant requested = now.minus(Duration.ofMinutes(20L + (long) (i + 1) * 3 * 60 + rnd.nextInt(90)));
            int pickupWait = 2 + rnd.nextInt(5), ride = 10 + rnd.nextInt(30);
            jdbc.update("update trips set requested_at = ?, accepted_at = ?, started_at = ?, completed_at = ? where id = ?",
                    utc(requested), utc(requested.plus(Duration.ofMinutes(1))), utc(requested.plus(Duration.ofMinutes(1 + pickupWait))),
                    utc(requested.plus(Duration.ofMinutes(1L + pickupWait + ride))), tripIds.get(i));
        }
    }

    private void seedDisputes(List<UUID> completedTrips) {
        Instant now = Instant.now(clock);
        record Plan(int trip, State state, String reason, long dueInDays) {}
        List<Plan> plans = List.of(
                new Plan(0, State.OPEN, "fraudulent", 6),
                new Plan(1, State.OPEN, "product_not_received", -1),        // past its deadline
                new Plan(2, State.INQUIRY, "general", 9),
                new Plan(3, State.WON, "duplicate", 0),
                new Plan(4, State.LOST, "fraudulent", 0));
        for (Plan p : plans) {
            Payment payment = payments.findByTripId(completedTrips.get(p.trip())).orElseThrow();
            boolean unanswered = p.state() == State.OPEN || p.state() == State.INQUIRY;
            disputes.apply(new DisputeNotice("dp_demo_" + p.trip(), payment.getGatewayReference(), payment.getAmount(),
                    payment.getCurrency(), p.state(), p.state().name().toLowerCase(), p.reason(),
                    unanswered ? now.plus(Duration.ofDays(p.dueInDays())) : null,
                    p.state() == State.LOST ? new BigDecimal("15.00") : BigDecimal.ZERO,
                    "https://dashboard.stripe.com/test/disputes/dp_demo_" + p.trip()));
        }
    }

    private void seedIncidents(List<UUID> riderIds, List<UUID> completedTrips) {
        Payment payment = payments.findByTripId(completedTrips.get(5)).orElseThrow();
        incidents.record(IncidentType.CONNECT_PAYOUT_FAILED, "po_demo_failed", riderIds.get(2), null, null, new BigDecimal("42.50"),
                "USD", "The bank rejected the payout: account closed");
        incidents.record(IncidentType.REFUND_FAILED, "re_demo_failed", payment.getRiderId(), payment.getId(), null,
                payment.getAmount(), payment.getCurrency(), "Refund failed: the card has been closed");
        incidents.record(IncidentType.TRANSFER_REVERSED, "tr_demo_reversed", riderIds.get(4), null, null, new BigDecimal("18.00"),
                "USD", "Transfer reversed by the provider");
        var resolved = incidents.record(IncidentType.PLATFORM_PAYOUT_FAILED, "po_demo_platform", null, null, null,
                new BigDecimal("1200.00"), "USD", "Platform bank account details were out of date");
        incidents.resolve(resolved.getId(), "Bank details updated; payout re-sent");
    }

    private static OffsetDateTime utc(Instant i) { return OffsetDateTime.ofInstant(i, ZoneOffset.UTC); }

    /** A random point within {@code km} of the given one (uniform over the disc). */
    private static double[] near(Random rnd, double lat, double lon, double km) {
        double r = km * Math.sqrt(rnd.nextDouble()) / 111.0, a = rnd.nextDouble() * 2 * Math.PI;
        return new double[] {lat + r * Math.sin(a), lon + r * Math.cos(a) / Math.cos(Math.toRadians(lat))};
    }
}
