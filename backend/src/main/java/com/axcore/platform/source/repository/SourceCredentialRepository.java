package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.EncryptedSecret;
import com.axcore.platform.source.domain.StoredCredential;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class SourceCredentialRepository {
    private final JdbcClient jdbc;

    public Optional<StoredCredential> find(long dataSourceId, CredentialPurpose purpose) {
        return jdbc.sql(
                        """
                select username, secret_ciphertext, secret_nonce, key_version
                from data_source_credential where data_source_id=:dataSourceId and purpose=:purpose
                """)
                .param("dataSourceId", dataSourceId)
                .param("purpose", purpose.name())
                .query(
                        (rs, row) ->
                                new StoredCredential(
                                        rs.getString("username"),
                                        new EncryptedSecret(
                                                rs.getBytes("secret_ciphertext"),
                                                rs.getBytes("secret_nonce"),
                                                rs.getInt("key_version"))))
                .optional();
    }
}
