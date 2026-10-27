package com.ridehail.testsupport;

import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Redis and RabbitMQ for the tests that need them, started on first use and shared by the whole test run
 * (Testcontainers stops them when the JVM exits). Tests using them are skipped when Docker is not available.
 */
public final class Containers {
    public static final String RABBIT_USER = "ridehail";
    public static final String RABBIT_PASSWORD = "ridehail-test";

    private static GenericContainer<?> redis;
    private static GenericContainer<?> rabbit;

    private Containers() {}

    /** For {@code @EnabledIf("com.ridehail.testsupport.Containers#dockerAvailable")}. */
    public static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable notAvailable) {
            return false;
        }
    }

    public static synchronized GenericContainer<?> redis() {
        if (redis == null) {
            redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            redis.start();
        }
        return redis;
    }

    public static String redisHost() { return redis().getHost(); }

    public static int redisPort() { return redis().getMappedPort(6379); }

    /** RabbitMQ with the STOMP plugin enabled, as in docker-compose. The built-in guest user only works from localhost, so use a real one. */
    public static synchronized GenericContainer<?> rabbit() {
        if (rabbit == null) {
            rabbit = new GenericContainer<>(DockerImageName.parse("rabbitmq:3.13-alpine"))
                    .withCopyToContainer(Transferable.of("[rabbitmq_stomp].\n"), "/etc/rabbitmq/enabled_plugins")
                    .withEnv("RABBITMQ_DEFAULT_USER", RABBIT_USER)
                    .withEnv("RABBITMQ_DEFAULT_PASS", RABBIT_PASSWORD)
                    .withExposedPorts(61613)
                    .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));
            rabbit.start();
        }
        return rabbit;
    }

    public static String rabbitHost() { return rabbit().getHost(); }

    public static int rabbitStompPort() { return rabbit().getMappedPort(61613); }
}
