package com.ridehail.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehail.push.RealtimeProperties.Broker;
import com.ridehail.push.RealtimeProperties.Relay;
import org.junit.jupiter.api.Test;

class RealtimePropertiesTest {

    static RealtimeProperties relay(String host, int port, String user, String password) {
        return new RealtimeProperties(Broker.RELAY, new Relay(host, port, "/", user, password, user, password));
    }

    @Test
    void aCompleteRelayConfigurationIsAccepted() {
        assertThatCode(() -> relay("rabbit.internal", 61613, "ridehail", "s3cret").validate()).doesNotThrowAnyException();
    }

    @Test
    void theInMemoryBrokerNeedsNoBrokerSettingsAtAll() {
        assertThatCode(() -> new RealtimeProperties(Broker.SIMPLE, new Relay("", 0, "", "", "", "", "")).validate()).doesNotThrowAnyException();
        assertThatCode(() -> new RealtimeProperties(Broker.SIMPLE, null).validate()).doesNotThrowAnyException();
        assertThat(new RealtimeProperties(Broker.SIMPLE, null).isRelay()).isFalse();
    }

    @Test
    void aRelayWithMissingSettingsFailsAtStartupNamingExactlyWhatIsMissing() {
        assertThatThrownBy(() -> relay("", 61613, "ridehail", "pw").validate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("RABBITMQ_HOST").hasMessageNotContaining("RABBITMQ_PASSWORD");
        assertThatThrownBy(() -> relay("h", 61613, "", "pw").validate()).hasMessageContaining("RABBITMQ_USER");
        assertThatThrownBy(() -> relay("h", 61613, "u", "  ").validate()).hasMessageContaining("RABBITMQ_PASSWORD");
        assertThatThrownBy(() -> relay("h", 0, "u", "pw").validate()).hasMessageContaining("RABBITMQ_STOMP_PORT");
        assertThatThrownBy(() -> relay("", 0, "", "").validate())
                .hasMessageContaining("RABBITMQ_HOST").hasMessageContaining("RABBITMQ_STOMP_PORT")
                .hasMessageContaining("RABBITMQ_USER").hasMessageContaining("RABBITMQ_PASSWORD");
        assertThatThrownBy(() -> new RealtimeProperties(Broker.RELAY, null).validate()).isInstanceOf(IllegalStateException.class);
    }
}
