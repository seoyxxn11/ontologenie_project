package com.axcore.platform.source.service;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 원천 접속 비밀번호 암호화 키. 키는 버전별로 32바이트를 base64로 넣고(환경변수 AXCORE_SOURCE_CREDENTIAL_KEY_{버전}), 새로 암호화할 때는
 * currentKeyVersion의 키를 쓴다(AXCORE_SOURCE_CREDENTIAL_KEY_VERSION). 값은 저장소에 두지 않는다.
 */
@ConfigurationProperties("axcore.source-credential")
public record SourceCredentialProperties(int currentKeyVersion, Map<Integer, String> keys) {}
