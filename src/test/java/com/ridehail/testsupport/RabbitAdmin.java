package com.ridehail.testsupport;

import java.util.Arrays;
import java.util.List;

/** Looks inside the real RabbitMQ the tests run against. */
public final class RabbitAdmin {
    private RabbitAdmin() {}

    public record Queue(String name, boolean durable, boolean autoDelete, boolean exclusive, String arguments) {}

    public static List<Queue> queues() {
        return rows("list_queues", "name", "durable", "auto_delete", "exclusive", "arguments").stream()
                .map(c -> new Queue(c[0], Boolean.parseBoolean(c[1]), Boolean.parseBoolean(c[2]), Boolean.parseBoolean(c[3]), c.length > 4 ? c[4] : ""))
                .toList();
    }

    /**
     * STOMP connections currently open to the broker: the application's own, plus one for every connected WebSocket client.
     * (`rabbitmqctl list_connections` only shows AMQP connections, so STOMP has its own command.)
     */
    public static int connections() {
        return rows("list_stomp_connections", "session_id").size();
    }

    private static List<String[]> rows(String command, String... columns) {
        try {
            var args = new java.util.ArrayList<String>(List.of("rabbitmqctl", command));
            args.addAll(Arrays.asList(columns));
            String out = Containers.rabbit().execInContainer(args.toArray(String[]::new)).getStdout();
            return out.lines()
                    .filter(l -> !l.isBlank() && !l.startsWith("Timeout:") && !l.startsWith("Listing ") && !l.equals(columns[0]) && !l.startsWith(columns[0] + "\t"))
                    .map(l -> l.split("\t", -1)).toList();
        } catch (Exception e) {
            throw new IllegalStateException("rabbitmqctl " + command + " failed", e);
        }
    }
}
