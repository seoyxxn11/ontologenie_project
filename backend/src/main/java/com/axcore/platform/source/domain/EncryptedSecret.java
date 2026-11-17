package com.axcore.platform.source.domain;

import java.util.Arrays;

/**
 * 암호화한 원천 접속 비밀번호. data_source_credential의 secret_ciphertext(GCM 태그 16바이트를 뒤에 붙인 암호문),
 * secret_nonce(12바이트), key_version과 같다.
 */
public record EncryptedSecret(byte[] ciphertext, byte[] nonce, int keyVersion) {

    @Override
    public boolean equals(Object other) {
        return other instanceof EncryptedSecret secret
                && keyVersion == secret.keyVersion
                && Arrays.equals(ciphertext, secret.ciphertext)
                && Arrays.equals(nonce, secret.nonce);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(ciphertext);
        result = 31 * result + Arrays.hashCode(nonce);
        return 31 * result + Integer.hashCode(keyVersion);
    }

    @Override
    public String toString() {
        return "EncryptedSecret[ciphertext=<redacted>, nonce=<redacted>, keyVersion="
                + keyVersion
                + "]";
    }
}
