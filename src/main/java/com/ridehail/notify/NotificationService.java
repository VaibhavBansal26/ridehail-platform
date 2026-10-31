package com.ridehail.notify;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Sends a notification to every phone a customer or rider has registered. Fire and forget: it never throws and never waits. */
@Service
public class NotificationService {
    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final DeviceTokenRepository devices;
    private final PushNotifier notifier;
    private final TaskExecutor executor;
    private final TransactionTemplate tx;

    NotificationService(DeviceTokenRepository devices, PushNotifier notifier, @Qualifier("notificationExecutor") TaskExecutor executor,
                        TransactionTemplate tx) {
        this.devices = devices;
        this.notifier = notifier;
        this.executor = executor;
        this.tx = tx;
    }

    public void notify(UUID profileId, PushNotifier.Message message) {
        if (profileId == null) return;
        try {
            executor.execute(() -> deliver(profileId, message));
        } catch (RejectedExecutionException busy) {
            log.warn("Dropped a push notification for {}: the sending queue is full", profileId);
        }
    }

    private void deliver(UUID profileId, PushNotifier.Message message) {
        try {
            List<String> tokens = devices.findByProfileIdOrderByLastSeenAtDesc(profileId).stream().map(DeviceToken::getToken).toList();
            if (tokens.isEmpty()) return;
            List<String> dead = notifier.send(tokens, message);
            if (!dead.isEmpty()) tx.executeWithoutResult(s -> devices.deleteByTokenIn(dead));   // uninstalled apps: forget them
        } catch (RuntimeException e) {
            log.warn("Could not send a push notification to {}", profileId, e);
        }
    }
}
