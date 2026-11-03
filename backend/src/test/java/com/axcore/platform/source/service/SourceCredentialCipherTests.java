package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.EncryptedSecret;
import com.axcore.platform.source.domain.SourceAccount;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** 원천 접속 비밀번호 암·복호화(S15P31C106-275)의 형식, 키 버전, 실패 경로를 확인한다. 키는 테스트 전용 값이다. */
@ExtendWith(OutputCaptureExtension.class)
class SourceCredentialCipherTests {

    private static final String KEY_1 = encode("test-only-source-credential-key!");
    private static final String KEY_2 = encode("test-only-source-credential-ke2!");
    private static final String PASSWORD = "source-password-for-test";

    @Test
    void decryptsWithSameSourceAndPurpose() {
        SourceCredentialCipher cipher = cipher(1, Map.of(1, KEY_1));

        EncryptedSecret secret = cipher.encrypt(7, CredentialPurpose.AI, PASSWORD);

        assertThat(secret.keyVersion()).isEqualTo(1);
        assertThat(secret.nonce()).hasSize(12);
        assertThat(secret.ciphertext()).hasSize(PASSWORD.length() + 16);
        assertThat(cipher.decrypt(7, CredentialPurpose.AI, secret)).isEqualTo(PASSWORD);
    }

    @Test
    void usesNewNonceEachTime() {
        SourceCredentialCipher cipher = cipher(1, Map.of(1, KEY_1));

        EncryptedSecret first = cipher.encrypt(7, CredentialPurpose.AI, PASSWORD);
        EncryptedSecret second = cipher.encrypt(7, CredentialPurpose.AI, PASSWORD);

        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
    }

    // 64번에 정한 형식(AAD "{data_source_id}:{purpose}", 태그를 암호문 뒤에 붙임)을 표준 JCE로 직접 풀어 확인한다.
    @Test
    void followsAgreedFormat() throws Exception {
        EncryptedSecret secret =
                cipher(1, Map.of(1, KEY_1)).encrypt(7, CredentialPurpose.SYNC, PASSWORD);

        Cipher jce = Cipher.getInstance("AES/GCM/NoPadding");
        jce.init(
                Cipher.DECRYPT_MODE,
                new SecretKeySpec(Base64.getDecoder().decode(KEY_1), "AES"),
                new GCMParameterSpec(128, secret.nonce()));
        jce.updateAAD("7:SYNC".getBytes(StandardCharsets.UTF_8));

        assertThat(new String(jce.doFinal(secret.ciphertext()), StandardCharsets.UTF_8))
                .isEqualTo(PASSWORD);
    }

    @Test
    void rejectsOtherSourcePurposeOrKey() {
        SourceCredentialCipher cipher = cipher(1, Map.of(1, KEY_1));
        EncryptedSecret secret = cipher.encrypt(7, CredentialPurpose.AI, PASSWORD);

        assertThatThrownBy(() -> cipher.decrypt(8, CredentialPurpose.AI, secret))
                .isInstanceOf(SourceCredentialException.class);
        assertThatThrownBy(() -> cipher.decrypt(7, CredentialPurpose.SYNC, secret))
                .isInstanceOf(SourceCredentialException.class);
        assertThatThrownBy(
                        () -> cipher(1, Map.of(1, KEY_2)).decrypt(7, CredentialPurpose.AI, secret))
                .isInstanceOf(SourceCredentialException.class)
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining(KEY_2);
    }

    @Test
    void rejectsTamperedCiphertextAndWrongNonce() {
        SourceCredentialCipher cipher = cipher(1, Map.of(1, KEY_1));
        EncryptedSecret secret = cipher.encrypt(7, CredentialPurpose.AI, PASSWORD);
        byte[] tampered = secret.ciphertext().clone();
        tampered[0] ^= 1;

        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        7,
                                        CredentialPurpose.AI,
                                        new EncryptedSecret(tampered, secret.nonce(), 1)))
                .isInstanceOf(SourceCredentialException.class);
        assertThatThrownBy(
                        () ->
                                cipher.decrypt(
                                        7,
                                        CredentialPurpose.AI,
                                        new EncryptedSecret(secret.ciphertext(), new byte[11], 1)))
                .isInstanceOf(SourceCredentialException.class);
    }

    @Test
    void decryptsValueStoredWithPreviousKeyVersion() {
        EncryptedSecret old =
                cipher(1, Map.of(1, KEY_1)).encrypt(7, CredentialPurpose.AI, PASSWORD);
        SourceCredentialCipher rotated = cipher(2, Map.of(1, KEY_1, 2, KEY_2));

        assertThat(rotated.decrypt(7, CredentialPurpose.AI, old)).isEqualTo(PASSWORD);
        EncryptedSecret renewed = rotated.encrypt(7, CredentialPurpose.AI, PASSWORD);
        assertThat(renewed.keyVersion()).isEqualTo(2);
        assertThat(cipher(2, Map.of(2, KEY_2)).decrypt(7, CredentialPurpose.AI, renewed))
                .isEqualTo(PASSWORD);
    }

    @Test
    void startsWithoutKeyButFailsToEncryptOrDecrypt(CapturedOutput output) {
        EncryptedSecret secret =
                cipher(1, Map.of(1, KEY_1)).encrypt(7, CredentialPurpose.AI, PASSWORD);
        SourceCredentialCipher noKey = cipher(1, Map.of(1, ""));

        assertThatThrownBy(() -> noKey.encrypt(7, CredentialPurpose.AI, PASSWORD))
                .isInstanceOf(SourceCredentialException.class)
                .hasMessageNotContaining(PASSWORD);
        assertThatThrownBy(() -> noKey.decrypt(7, CredentialPurpose.AI, secret))
                .isInstanceOf(SourceCredentialException.class);
        assertThatThrownBy(() -> cipher(1, null).encrypt(7, CredentialPurpose.AI, PASSWORD))
                .isInstanceOf(SourceCredentialException.class);
        assertThat(output).contains("AXCORE_SOURCE_CREDENTIAL_KEY_1");
    }

    @Test
    void ignoresMalformedKeysWithoutLoggingThem(CapturedOutput output) {
        String notBase64 = "not-a-base64-key-value!!";
        String shortKey = encode("only-16-bytes-ok");

        SourceCredentialCipher cipher = cipher(1, Map.of(1, notBase64, 2, shortKey));

        assertThatThrownBy(() -> cipher.encrypt(7, CredentialPurpose.AI, PASSWORD))
                .isInstanceOf(SourceCredentialException.class);
        assertThat(output)
                .contains("base64 형식이 아니라")
                .contains("32바이트가 아니라")
                .doesNotContain(notBase64)
                .doesNotContain(shortKey);
    }

    @Test
    void hidesPasswordInAccountToString() {
        assertThat(new SourceAccount("mes_ai", PASSWORD).toString())
                .contains("mes_ai")
                .doesNotContain(PASSWORD);
    }

    private static SourceCredentialCipher cipher(int currentVersion, Map<Integer, String> keys) {
        return new SourceCredentialCipher(new SourceCredentialProperties(currentVersion, keys));
    }

    private static String encode(String raw) {
        return Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }
}
