package com.ridehail.notify;

import java.util.List;
import java.util.Map;

/** Port for sending a notification to phones. Implementations must never throw into a ride: a lost notification is not a failed ride. */
public interface PushNotifier {

    /**
     * @param ttlSeconds how long the provider may hold the message for a phone that is off or out of coverage; an offer that
     *                   expires in 15 seconds must not arrive a minute later. 0 means the provider's default.
     */
    record Message(String title, String body, Map<String, String> data, int ttlSeconds) {}

    /** @return the tokens the provider says are no longer valid (app uninstalled): the caller forgets them. */
    List<String> send(List<String> tokens, Message message);
}
