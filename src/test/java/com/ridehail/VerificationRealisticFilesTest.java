package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;

/**
 * The verification tests elsewhere use tiny files. Real photos are megabytes: this one uses the real size limit and a real-sized file,
 * and makes sure the bytes come back exactly (an earlier version only held 255 bytes in the dev database and every real photo failed).
 */
@SpringBootTest(properties = "ridehail.verification.required=true")
class VerificationRealisticFilesTest extends ApiTestBase {

    private static byte[] photo(int size) {
        byte[] b = new byte[size];
        new SecureRandom().nextBytes(b);       // incompressible, like a real JPEG
        b[0] = (byte) 0xFF; b[1] = (byte) 0xD8; b[2] = (byte) 0xFF; b[3] = (byte) 0xE0;
        return b;
    }

    @Test
    void aMultiMegabytePhotoSurvivesEncryptionStorageAndRetrieval() throws Exception {
        Actor rider = riderOffline("Asha");
        byte[] big = photo(3 * 1024 * 1024);
        LocalDate expires = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(400);

        mvc.perform(put("/api/riders/me/verification/documents/DRIVING_LICENCE?expiresOn=" + expires)
                .header("Authorization", "Bearer " + rider.token()).contentType("application/octet-stream").content(big))
                .andExpect(status().isOk());

        var res = call(HttpMethod.GET, "/api/admin/verifications/" + rider.id() + "/documents/DRIVING_LICENCE", admin(), null)
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getContentAsByteArray()).isEqualTo(big);
        assertThat(fetch("/api/riders/me/verification", rider).get("documents").get(0).get("sizeBytes").asInt()).isEqualTo(big.length);
    }

    @Test
    void theLargestAllowedFileIsAcceptedAndOneByteMoreIsNot() throws Exception {
        Actor rider = riderOffline("Asha");
        LocalDate expires = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).plusDays(400);
        int limit = 5 * 1024 * 1024;
        mvc.perform(put("/api/riders/me/verification/documents/INSURANCE?expiresOn=" + expires)
                .header("Authorization", "Bearer " + rider.token()).contentType("application/octet-stream").content(photo(limit)))
                .andExpect(status().isOk());
        mvc.perform(put("/api/riders/me/verification/documents/VEHICLE_REGISTRATION?expiresOn=" + expires)
                .header("Authorization", "Bearer " + rider.token()).contentType("application/octet-stream").content(photo(limit + 1)))
                .andExpect(status().isPayloadTooLarge());
    }
}
