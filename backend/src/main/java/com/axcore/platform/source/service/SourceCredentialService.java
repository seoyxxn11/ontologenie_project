package com.axcore.platform.source.service;

import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.SourceAccount;
import com.axcore.platform.source.repository.SourceCredentialRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SourceCredentialService {
    private final SourceCredentialRepository credentials;
    private final SourceCredentialCipher cipher;

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
}
