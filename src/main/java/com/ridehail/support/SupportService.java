package com.ridehail.support;

import com.ridehail.common.ApiExceptions.BadRequestException;
import com.ridehail.common.ApiExceptions.NotFoundException;
import com.ridehail.common.ApiExceptions.TooManyRequestsException;
import com.ridehail.customer.CustomerRepository;
import com.ridehail.notify.NotificationService;
import com.ridehail.notify.PushNotifier;
import com.ridehail.rider.RiderRepository;
import com.ridehail.security.Role;
import com.ridehail.trip.TripRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Chat between customers or riders and support staff. One running conversation per person; newest messages are what matter. */
@Service
public class SupportService {
    public static final int MAX_BODY = 2000;
    static final int HISTORY = 100;
    static final int MESSAGES_PER_HOUR = 20;

    public record MessageView(UUID id, String from, String body, UUID tripId, Instant createdAt) {}

    public record ConversationView(UUID id, String status, int unread, List<MessageView> messages) {}

    public record InboxItem(UUID id, Role role, UUID userId, String name, String phone, String status, boolean awaitingReply,
                            Instant awaitingSince, String lastPreview, Instant lastMessageAt) {}

    public record Thread(InboxItem conversation, List<MessageView> messages) {}

    public record Summary(long awaiting, long oldestWaitingSeconds) {}

    private final SupportConversationRepository conversations;
    private final SupportMessageRepository messages;
    private final CustomerRepository customers;
    private final RiderRepository riders;
    private final TripRepository trips;
    private final NotificationService notifications;
    private final Clock clock;

    SupportService(SupportConversationRepository conversations, SupportMessageRepository messages, CustomerRepository customers,
                   RiderRepository riders, TripRepository trips, NotificationService notifications, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.customers = customers;
        this.riders = riders;
        this.trips = trips;
        this.notifications = notifications;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ the person's side

    @Transactional(readOnly = true)
    public ConversationView mine(UUID userId) {
        return conversations.findByUserId(userId).map(this::view).orElseGet(() -> new ConversationView(null, "OPEN", 0, List.of()));
    }

    @Transactional
    public ConversationView send(UUID userId, Role role, String rawBody, UUID tripId) {
        String body = clean(rawBody);
        Instant now = Instant.now(clock);
        if (tripId != null && !ownsTrip(userId, role, tripId)) throw new BadRequestException("That trip is not yours");
        var conversation = conversations.findByUserId(userId).orElseGet(() -> open(userId, role, now));
        long lastHour = messages.countByConversationIdAndSenderAndCreatedAtAfter(conversation.getId(), Sender.USER, now.minus(Duration.ofHours(1)));
        if (lastHour >= MESSAGES_PER_HOUR) throw new TooManyRequestsException("You have sent a lot of messages. Please wait a little for a reply.", 600);
        Instant at = conversation.stampFor(now);
        messages.save(new SupportMessage(conversation.getId(), Sender.USER, null, body, tripId, at));
        conversation.userWrote(body, at);
        conversations.save(conversation);
        return view(conversation);
    }

    @Transactional
    public void markRead(UUID userId) {
        conversations.findByUserId(userId).ifPresent(c -> {
            if (c.getUserUnread() > 0) {
                c.userRead();
                conversations.save(c);
            }
        });
    }

    // ------------------------------------------------------------------ staff side

    @Transactional(readOnly = true)
    public Page<InboxItem> inbox(String filter, int page, int size) {
        // people waiting come first, longest wait first; then the most recent conversations
        Pageable paging = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100),
                "awaiting".equals(filter) ? Sort.by("awaitingSince") : Sort.by(Sort.Direction.DESC, "awaitingReply").and(Sort.by("awaitingSince")).and(Sort.by(Sort.Direction.DESC, "lastMessageAt")));
        Page<SupportConversation> result = switch (filter == null ? "all" : filter) {
            case "awaiting" -> conversations.findByAwaitingReplyTrue(paging);
            case "resolved" -> conversations.findByStatus(ConversationStatus.RESOLVED, paging);
            case "open" -> conversations.findByStatus(ConversationStatus.OPEN, paging);
            default -> conversations.findAll(paging);
        };
        return result.map(c -> new InboxItem(c.getId(), c.getRole(), c.getUserId(), c.getName(), c.getPhone(), c.getStatus().name(),
                c.isAwaitingReply(), c.getAwaitingSince(), c.getLastPreview(), c.getLastMessageAt()));
    }

    @Transactional(readOnly = true)
    public Thread thread(UUID id) {
        var c = conversation(id);
        return new Thread(item(c), view(c).messages());
    }

    @Transactional
    public MessageView reply(UUID id, UUID staffId, String rawBody) {
        String body = clean(rawBody);
        var c = conversation(id);
        Instant now = c.stampFor(Instant.now(clock));
        var saved = messages.save(new SupportMessage(c.getId(), Sender.STAFF, staffId, body, null, now));
        c.staffWrote(body, now);
        conversations.save(c);
        notifications.notify(c.getUserId(), new PushNotifier.Message("Support replied", "Open the app to read it.", Map.of("type", "SUPPORT_REPLY"), 0));
        return toView(saved);
    }

    @Transactional
    public void resolve(UUID id) {
        var c = conversation(id);
        c.resolve();
        conversations.save(c);
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        long oldest = conversations.oldestWaiting().map(t -> Duration.between(t, Instant.now(clock)).toSeconds()).orElse(0L);
        return new Summary(conversations.countByAwaitingReplyTrue(), oldest);
    }

    // ------------------------------------------------------------------ internals

    private SupportConversation open(UUID userId, Role role, Instant now) {
        String name;
        String phone;
        if (role == Role.RIDER) {
            var r = riders.findById(userId).orElseThrow(() -> new NotFoundException("Rider", userId));
            name = r.getName();
            phone = r.getPhone();
        } else {
            var c = customers.findById(userId).orElseThrow(() -> new NotFoundException("Customer", userId));
            name = c.getName();
            phone = c.getPhone();
        }
        try {
            return conversations.saveAndFlush(new SupportConversation(userId, role, name, phone, now));
        } catch (DataIntegrityViolationException twoPhonesAtOnce) {
            return conversations.findByUserId(userId).orElseThrow(() -> twoPhonesAtOnce);
        }
    }

    private boolean ownsTrip(UUID userId, Role role, UUID tripId) {
        return trips.findById(tripId).map(t -> role == Role.RIDER ? userId.equals(t.getRiderId()) : userId.equals(t.getCustomerId())).orElse(false);
    }

    private SupportConversation conversation(UUID id) {
        return conversations.findById(id).orElseThrow(() -> new NotFoundException("Conversation", id));
    }

    private ConversationView view(SupportConversation c) {
        var newestFirst = messages.findByConversationIdOrderByCreatedAtDesc(c.getId(), PageRequest.of(0, HISTORY));
        var list = new java.util.ArrayList<>(newestFirst.stream().map(this::toView).toList());
        Collections.reverse(list);
        return new ConversationView(c.getId(), c.getStatus().name(), c.getUserUnread(), list);
    }

    private InboxItem item(SupportConversation c) {
        return new InboxItem(c.getId(), c.getRole(), c.getUserId(), c.getName(), c.getPhone(), c.getStatus().name(), c.isAwaitingReply(),
                c.getAwaitingSince(), c.getLastPreview(), c.getLastMessageAt());
    }

    private MessageView toView(SupportMessage m) {
        return new MessageView(m.getId(), m.getSender().name(), m.getBody(), m.getTripId(), m.getCreatedAt());
    }

    /** Text only: control characters (other than newlines) are dropped, and an empty message is refused. */
    static String clean(String raw) {
        if (raw == null) throw new BadRequestException("Write a message first");
        String s = raw.replaceAll("[\\p{Cntrl}&&[^\\n]]", "").strip();
        if (s.isEmpty()) throw new BadRequestException("Write a message first");
        if (s.length() > MAX_BODY) throw new BadRequestException("Messages can be at most " + MAX_BODY + " characters");
        return s;
    }
}
