package com.ridehail.verification;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class FileSnifferTest {
    @Test
    void recognisesOnlyJpegPngAndPdfByTheirContent() {
        assertThat(FileSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0})).isEqualTo("image/jpeg");
        assertThat(FileSniffer.sniff(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0})).isEqualTo("image/png");
        assertThat(FileSniffer.sniff("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))).isEqualTo("application/pdf");
    }

    @Test
    void refusesEverythingElseWhateverItIsCalled() {
        for (String s : new String[] {"", "GIF89a", "<html><script>alert(1)</script>", "#!/bin/sh\nrm -rf /", "PK\u0003\u0004zip", "MZ\u0090 an exe", "%PD", "just text"}) {
            assertThat(FileSniffer.sniff(s.getBytes(StandardCharsets.ISO_8859_1))).as(s).isNull();
        }
        assertThat(FileSniffer.sniff(new byte[0])).isNull();
        assertThat(FileSniffer.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8})).isNull();   // too short to be one
    }
}
