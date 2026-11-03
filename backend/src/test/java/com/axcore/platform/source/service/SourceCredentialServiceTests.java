package com.axcore.platform.source.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.axcore.platform.TestcontainersConfiguration;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.EncryptedSecret;
import com.axcore.platform.source.domain.SourceAccount;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;

/** data_source_credential에 저장한 암호문을 실제 DB와 설정(환경변수 이름)으로 읽고 푸는지 확인한다. 키는 테스트 전용 값이다. */
@SpringBootTest(
        properties = "AXCORE_SOURCE_CREDENTIAL_KEY_1=dGVzdC1vbmx5LXNvdXJjZS1jcmVkZW50aWFsLWtleSE=")
@ActiveProfiles("api")
@Import(TestcontainersConfiguration.class)
class SourceCredentialServiceTests {

    private static final String PASSWORD = "source-password-for-test";

    @Autowired SourceCredentialService service;
    @Autowired SourceCredentialCipher cipher;
    @Autowired JdbcClient jdbc;
    long dataSourceId;

    @BeforeEach
    void dataSource() {
        dataSourceId =
                jdbc.sql(
                                """
                insert into data_source(code, name, source_kind, connection_config)
                values (:code, '테스트 원천', 'POSTGRES', '{"host":"db","port":5432,"database":"mes"}')
                returning id
                """)
                        .param("code", "SRC_" + UUID.randomUUID())
                        .query(Long.class)
                        .single();
    }

    @Test
    void readsAndDecryptsAccountByPurpose() {
        insert(CredentialPurpose.SYNC, "mes_sync", "sync-" + PASSWORD);
        insert(CredentialPurpose.AI, "mes_ai", PASSWORD);

        assertThat(service.findAccount(dataSourceId, CredentialPurpose.AI))
                .contains(new SourceAccount("mes_ai", PASSWORD));
        assertThat(service.findAccount(dataSourceId, CredentialPurpose.SYNC))
                .contains(new SourceAccount("mes_sync", "sync-" + PASSWORD));
    }

    @Test
    void returnsEmptyWhenAccountIsNotRegistered() {
        insert(CredentialPurpose.SYNC, "mes_sync", PASSWORD);

        assertThat(service.findAccount(dataSourceId, CredentialPurpose.AI)).isEmpty();
        assertThat(service.findAccount(dataSourceId + 1000, CredentialPurpose.SYNC)).isEmpty();
    }

    @Test
    void storesOnlyCiphertext() {
        insert(CredentialPurpose.AI, "mes_ai", PASSWORD);

        String stored =
                jdbc.sql(
                                "select encode(secret_ciphertext, 'escape') from data_source_credential where data_source_id=:id")
                        .param("id", dataSourceId)
                        .query(String.class)
                        .single();
        assertThat(stored).doesNotContain(PASSWORD);
    }

    private void insert(CredentialPurpose purpose, String username, String password) {
        EncryptedSecret secret = cipher.encrypt(dataSourceId, purpose, password);
        jdbc.sql(
                        """
                insert into data_source_credential(data_source_id, purpose, username,
                                                   secret_ciphertext, secret_nonce, key_version)
                values (:dataSourceId, :purpose, :username, :ciphertext, :nonce, :keyVersion)
                """)
                .param("dataSourceId", dataSourceId)
                .param("purpose", purpose.name())
                .param("username", username)
                .param("ciphertext", secret.ciphertext())
                .param("nonce", secret.nonce())
                .param("keyVersion", secret.keyVersion())
                .update();
    }
}
