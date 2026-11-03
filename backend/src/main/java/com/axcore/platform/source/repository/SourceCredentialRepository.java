package com.axcore.platform.source.repository;

import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.EncryptedSecret;
import com.axcore.platform.source.domain.StoredCredential;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
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

    /**
     * 용도별 계정을 넣거나 바꾼다(원천·용도마다 한 행). 바꿀 때는 계정명·암호문·nonce·key_version·rotated_at을 갈고 created_by는 처음
     * 등록한 사람으로 둔다.
     */
    public void save(
            long dataSourceId,
            CredentialPurpose purpose,
            String username,
            EncryptedSecret secret,
            Long createdBy,
            Instant now) {
        jdbc.sql(
                        """
                insert into data_source_credential
                    (data_source_id, purpose, username, secret_ciphertext, secret_nonce, key_version,
                     rotated_at, created_by)
                values (:dataSourceId, :purpose, :username, :ciphertext, :nonce, :keyVersion, :now, :createdBy)
                on conflict (data_source_id, purpose) do update
                set username = excluded.username,
                    secret_ciphertext = excluded.secret_ciphertext,
                    secret_nonce = excluded.secret_nonce,
                    key_version = excluded.key_version,
                    rotated_at = excluded.rotated_at
                """)
                .param("dataSourceId", dataSourceId)
                .param("purpose", purpose.name())
                .param("username", username)
                .param("ciphertext", secret.ciphertext())
                .param("nonce", secret.nonce())
                .param("keyVersion", secret.keyVersion())
                .param("now", Timestamp.from(now))
                .param("createdBy", createdBy, Types.BIGINT)
                .update();
    }
}
