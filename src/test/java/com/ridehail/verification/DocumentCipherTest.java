package com.ridehail.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentCipherTest {
    private static String key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return Base64.getEncoder().encodeToString(k);
    }

    private static DocumentCipher cipher(String key) { return new DocumentCipher(new VerificationProperties(false, 0, key)); }

    private static final byte[] PLAIN = "my licence number is SECRET-12345".getBytes(StandardCharsets.UTF_8);

    @Test
    void roundTripsAndNeverStoresThePlaintext() {
        var c = cipher(key());
        UUID id = UUID.randomUUID();
        byte[] sealed = c.encrypt(id, PLAIN);
        assertThat(c.decrypt(id, sealed)).isEqualTo(PLAIN);
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("SECRET");
        assertThat(sealed[0]).isEqualTo((byte) 1);   // key version, for rotation later
    }

    @Test
    void everyEncryptionIsDifferent() {
        var c = cipher(key());
        UUID id = UUID.randomUUID();
        assertThat(c.encrypt(id, PLAIN)).isNotEqualTo(c.encrypt(id, PLAIN));   // a fresh random nonce each time
    }

    @Test
    void aBlobCannotBeMovedOntoAnotherDocument() {
        var c = cipher(key());
        byte[] sealed = c.encrypt(UUID.randomUUID(), PLAIN);
        assertThatThrownBy(() -> c.decrypt(UUID.randomUUID(), sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void alteredOrTruncatedDataIsDetected() {
        var c = cipher(key());
        UUID id = UUID.randomUUID();
        byte[] sealed = c.encrypt(id, PLAIN);
        byte[] flipped = sealed.clone();
        flipped[flipped.length - 1] ^= 1;
        assertThatThrownBy(() -> c.decrypt(id, flipped)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt(id, Arrays.copyOf(sealed, 10))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> c.decrypt(id, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theWrongKeyCannotRead() {
        UUID id = UUID.randomUUID();
        byte[] sealed = cipher(key()).encrypt(id, PLAIN);
        assertThatThrownBy(() -> cipher(key()).decrypt(id, sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aMalformedKeyIsRefusedAtStartup() {
        assertThatThrownBy(() -> cipher("not base64!!")).isInstanceOf(IllegalStateException.class).hasMessageContaining("base64");
        assertThatThrownBy(() -> cipher(Base64.getEncoder().encodeToString(new byte[16]))).isInstanceOf(IllegalStateException.class).hasMessageContaining("32 bytes");
    }
}
