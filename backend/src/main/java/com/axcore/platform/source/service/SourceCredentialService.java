package com.axcore.platform.source.service;

import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.repository.SourceCredentialRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SourceCredentialService {
    private final SourceCredentialRepository credentials;
    private final SourceCredentialCipher cipher;
    private final Clock clock;

    /**
     * 원천의 용도별 계정을 풀어 돌려준다. 등록된 계정이 없으면 빈 값이고, 풀 수 없으면 {@link SourceCredentialException}이다. 결과는
     * 로그·응답에 넣지 않는다.
     */
    @Transactional(readOnly = true)
    public Optional<SourceAccount> findAccount(long dataSourceId, CredentialPurpose purpose) {
        return credentials
                .find(dataSourceId, purpose)
                .map(
                        stored ->
                                new SourceAccount(
                                        stored.username(),
                                        cipher.decrypt(dataSourceId, purpose, stored.secret())));
    }

    /**
     * 비밀번호를 현재 키로 암호화해 용도별 계정을 넣거나 바꾼다(API 010·012). 원문은 저장하지 않는다. 키가 없으면 {@link
     * SourceCredentialException}이고 호출한 트랜잭션이 함께 롤백된다.
     */
    @Transactional
    public void store(
            long dataSourceId,
            CredentialPurpose purpose,
            String username,
            String password,
            Long createdBy) {
        credentials.save(
                dataSourceId,
                purpose,
                username,
                cipher.encrypt(dataSourceId, purpose, password),
                createdBy,
                Instant.now(clock));
    }
}
