package com.axcore.platform.source.domain;

/** data_source_credential 한 행. 비밀번호는 암호문으로만 들고 있다. */
public record StoredCredential(String username, EncryptedSecret secret) {}
