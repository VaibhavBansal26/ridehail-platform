package com.ridehail.payment.stripe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ridehail.payment.stripe.StripeProperties.Mode;
import org.junit.jupiter.api.Test;

class StripePropertiesTest {

    private static StripeProperties props(String apiKey, String webhookSecret, String publishableKey) {
        return new StripeProperties(apiKey, webhookSecret, publishableKey, null, 0, null, "a", "b");
    }

    @Test
    void theModeComesFromTheSecretKey() {
        assertThat(props("sk_test_abc", "whsec_a", "pk_test_a").mode()).contains(Mode.TEST);
        assertThat(props("sk_live_abc", "whsec_a", "pk_live_a").mode()).contains(Mode.LIVE);
        assertThat(props("rk_live_abc", "whsec_a", "pk_live_a").mode()).contains(Mode.LIVE);   // restricted key
        assertThat(props(" sk_live_abc ", "whsec_a", "pk_live_a").mode()).contains(Mode.LIVE); // stray whitespace
        assertThat(props("pk_live_abc", "whsec_a", "pk_live_a").mode()).isEmpty();             // wrong kind of key
        assertThat(props("", "whsec_a", "pk_live_a").mode()).isEmpty();
        assertThat(props(null, "whsec_a", "pk_live_a").mode()).isEmpty();
    }

    @Test
    void consistentConfigurationHasNoProblems() {
        assertThat(props("sk_live_abc", "whsec_a,whsec_b", "pk_live_a").problems()).isEmpty();
        assertThat(props("rk_test_abc", " whsec_a , whsec_b ", "pk_test_a").problems()).isEmpty();
        assertThat(props("sk_test_abc", "whsec_a", null).problems()).isEmpty();   // the app does not need it to run, only card setup does
    }

    @Test
    void aPublishableKeyFromTheOtherModeIsCaught() {
        assertThat(props("sk_live_abc", "whsec_a", "pk_test_a").problems())
                .singleElement().asString().contains("test-mode").contains("live-mode");
        assertThat(props("sk_test_abc", "whsec_a", "pk_live_a").problems()).hasSize(1);
    }

    @Test
    void keysOfTheWrongKindAreCaught() {
        assertThat(props("pk_live_abc", "whsec_a", "pk_live_a").problems()).anyMatch(p -> p.contains("api-key"));
        assertThat(props("sk_live_abc", "whsec_a", "sk_live_a").problems()).anyMatch(p -> p.contains("publishable-key"));
        assertThat(props("sk_live_abc", "we_1ABC", "pk_live_a").problems()).anyMatch(p -> p.contains("whsec_"));
        assertThat(props("sk_live_abc", "whsec_a,sk_live_oops", "pk_live_a").problems()).anyMatch(p -> p.contains("whsec_"));
    }

    @Test
    void theGatewayRefusesToStartWhenTheKeysDoNotAgree() {
        assertThatThrownBy(() -> new StripeGateway(props("sk_live_abc", "whsec_a", "pk_test_a")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("misconfigured");
        assertThatThrownBy(() -> new StripeGateway(props("pk_live_abc", "whsec_a", "pk_live_a")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void problemMessagesNeverRepeatASecret() {
        var secrets = new String[] {"sk_live_SECRETVALUE", "whsec_SECRETWEBHOOK", "pk_test_PUBLISHABLEVALUE"};
        var all = String.join(" ", props(secrets[0], "we_SECRETWEBHOOK", secrets[2]).problems());
        assertThat(all).isNotBlank().doesNotContain("SECRETVALUE").doesNotContain("SECRETWEBHOOK").doesNotContain("PUBLISHABLEVALUE");
    }
}
