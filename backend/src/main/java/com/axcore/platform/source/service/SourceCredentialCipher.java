package com.axcore.platform.source.service;

import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.EncryptedSecret;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 원천 접속 비밀번호(data_source_credential)를 AES-256-GCM으로 암·복호화한다(S15P31C106-64·275). AAD가 {@code
 * {data_source_id}:{purpose}}라서 암호문을 다른 원천·용도의 행에 옮겨 붙이면 풀리지 않는다. 키가 없거나 형식이 틀려도 앱은 뜨고, 암·복호화할 때만
 * 실패한다.
 */
@Slf4j
@Component
@EnableConfigurationProperties(SourceCredentialProperties.class)
public class SourceCredentialCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final Map<Integer, SecretKey> keys;
    private final int currentKeyVersion;

    public SourceCredentialCipher(SourceCredentialProperties properties) {
        this.currentKeyVersion = properties.currentKeyVersion();
        this.keys = readKeys(properties.keys());
        if (!keys.containsKey(currentKeyVersion)) {
            log.warn(
                    "원천 접속 정보 암호화 키(버전 {})가 없어 암·복호화를 할 수 없습니다. AXCORE_SOURCE_CREDENTIAL_KEY_{}를 확인하세요.",
                    currentKeyVersion,
                    currentKeyVersion);
        }
    }

    /** 현재 키 버전으로 암호화한다. nonce는 호출마다 새로 만든다. */
    public EncryptedSecret encrypt(long dataSourceId, CredentialPurpose purpose, String secret) {
        Objects.requireNonNull(secret, "secret");
        SecretKey key = key(currentKeyVersion);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(dataSourceId, purpose));
            byte[] ciphertext = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return new EncryptedSecret(ciphertext, nonce, currentKeyVersion);
        } catch (GeneralSecurityException e) {
            throw new SourceCredentialException("원천 접속 정보를 암호화하지 못했습니다.", e);
        }
    }

    /** 저장된 key_version의 키로 푼다. 이전 버전 키로 저장한 값도 그 키가 설정에 남아 있으면 풀린다. */
    public String decrypt(long dataSourceId, CredentialPurpose purpose, EncryptedSecret secret) {
        SecretKey key = key(secret.keyVersion());
        if (secret.nonce() == null || secret.nonce().length != NONCE_BYTES) {
            throw new SourceCredentialException("저장된 원천 접속 정보의 nonce 길이가 맞지 않습니다.");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, secret.nonce()));
            cipher.updateAAD(aad(dataSourceId, purpose));
            return new String(cipher.doFinal(secret.ciphertext()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new SourceCredentialException("원천 접속 정보를 풀지 못했습니다. 저장할 때와 키·원천·용도가 다릅니다.", e);
        } catch (GeneralSecurityException e) {
            throw new SourceCredentialException("원천 접속 정보를 풀지 못했습니다.", e);
        }
    }

    private SecretKey key(int version) {
        SecretKey key = keys.get(version);
        if (key == null) {
            throw new SourceCredentialException("원천 접속 정보 암호화 키(버전 " + version + ")가 설정되지 않았습니다.");
        }
        return key;
    }

    private static byte[] aad(long dataSourceId, CredentialPurpose purpose) {
        return (dataSourceId + ":" + purpose.name()).getBytes(StandardCharsets.UTF_8);
    }

    // 잘못된 키는 버리고 경고만 남긴다. 키 값은 로그에 넣지 않는다.
    private static Map<Integer, SecretKey> readKeys(Map<Integer, String> encodedKeys) {
        Map<Integer, SecretKey> result = new HashMap<>();
        if (encodedKeys == null) {
            return result;
        }
        encodedKeys.forEach(
                (version, encoded) -> {
                    if (encoded == null || encoded.isBlank()) {
                        return;
                    }
                    byte[] raw;
                    try {
                        raw = Base64.getDecoder().decode(encoded.strip());
                    } catch (IllegalArgumentException e) {
                        log.warn("원천 접속 정보 암호화 키(버전 {})가 base64 형식이 아니라 쓰지 않습니다.", version);
                        return;
                    }
                    if (raw.length != KEY_BYTES) {
                        log.warn("원천 접속 정보 암호화 키(버전 {})가 {}바이트가 아니라 쓰지 않습니다.", version, KEY_BYTES);
                        return;
                    }
                    result.put(version, new SecretKeySpec(raw, "AES"));
                });
        return Map.copyOf(result);
    }
}
