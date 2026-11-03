package com.axcore.platform.source.domain;

/**
 * 암호화한 원천 접속 비밀번호. data_source_credential의 secret_ciphertext(GCM 태그 16바이트를 뒤에 붙인 암호문),
 * secret_nonce(12바이트), key_version과 같다.
 */
public record EncryptedSecret(byte[] ciphertext, byte[] nonce, int keyVersion) {}
