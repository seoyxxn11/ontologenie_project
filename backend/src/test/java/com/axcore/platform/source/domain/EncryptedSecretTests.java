package com.axcore.platform.source.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EncryptedSecretTests {

    @Test
    void comparesArrayContentsAndKeyVersion() {
        EncryptedSecret secret = new EncryptedSecret(new byte[] {1, 2}, new byte[] {3, 4}, 1);
        EncryptedSecret same = new EncryptedSecret(new byte[] {1, 2}, new byte[] {3, 4}, 1);
        assertThat(secret).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(secret)
                .isNotEqualTo(new EncryptedSecret(new byte[] {2, 1}, new byte[] {3, 4}, 1));
        assertThat(secret)
                .isNotEqualTo(new EncryptedSecret(new byte[] {1, 2}, new byte[] {4, 3}, 1));
        assertThat(secret)
                .isNotEqualTo(new EncryptedSecret(new byte[] {1, 2}, new byte[] {3, 4}, 2));
        assertThat(secret).isNotEqualTo(null).isNotEqualTo("other");
    }

    @Test
    void doesNotIncludeEncryptedBytesInText() {
        EncryptedSecret secret = new EncryptedSecret(new byte[] {11, 22}, new byte[] {33, 44}, 7);
        assertThat(secret.toString())
                .contains("keyVersion=7", "<redacted>")
                .doesNotContain("11", "22", "33", "44");
    }
}
