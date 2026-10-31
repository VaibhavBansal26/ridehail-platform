package com.ridehail.notify;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The default: nothing leaves the server. Notifications are only logged (at debug), so development needs no provider account. */
class LoggingNotifier implements PushNotifier {
    private static final Logger log = LoggerFactory.getLogger(LoggingNotifier.class);

    @Override
    public List<String> send(List<String> tokens, Message message) {
        log.debug("push (not sent, no provider configured) to {} device(s): {} / {}", tokens.size(), message.title(), message.body());
        return List.of();
    }
}
