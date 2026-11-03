package com.axcore.platform.source.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 원천 시스템(ERP·MES 등)과 연결 상태 (FN-SRC-01). code는 CDC 토픽 이름의 앞부분({code}.{스키마}.{테이블})이라 만든 뒤 바꾸지 않는다. 접속
 * 계정은 data_source_credential에 따로 둔다.
 */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DataSource extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;

    private String name;

    @Enumerated(EnumType.STRING)
    private SourceKind sourceKind;

    /** 비밀이 아닌 접속 설정(host, port, database). 비밀값 키는 DB 제약도 막는다. */
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> connectionConfig;

    @Enumerated(EnumType.STRING)
    private ConnectionStatus connectionStatus;

    private Instant lastCheckedAt;

    private boolean syncEnabled;

    private Integer syncIntervalMinutes;

    @Version private int rowVersion;

    private DataSource(
            String code,
            String name,
            SourceKind sourceKind,
            Map<String, Object> connectionConfig,
            boolean syncEnabled,
            Integer syncIntervalMinutes) {
        this.code = code;
        this.name = name;
        this.sourceKind = sourceKind;
        this.connectionConfig = new LinkedHashMap<>(connectionConfig);
        this.connectionStatus = ConnectionStatus.UNVERIFIED;
        this.syncEnabled = syncEnabled;
        this.syncIntervalMinutes = syncIntervalMinutes;
    }

    /** API 010. 등록 직후에는 연결을 확인하지 않았으므로 UNVERIFIED다. */
    public static DataSource register(
            String code,
            String name,
            SourceKind sourceKind,
            Map<String, Object> connectionConfig,
            boolean syncEnabled,
            Integer syncIntervalMinutes) {
        return new DataSource(
                code, name, sourceKind, connectionConfig, syncEnabled, syncIntervalMinutes);
    }

    public void rename(String name) {
        this.name = name;
    }

    /** 접속 설정이 바뀌면 이전 검사 결과를 믿을 수 없으므로 연결 상태를 UNVERIFIED로 되돌린다(API 012). 같은 값이면 그대로 둔다. */
    public void changeConnectionConfig(Map<String, Object> connectionConfig) {
        if (this.connectionConfig.equals(connectionConfig)) {
            return;
        }
        this.connectionConfig = new LinkedHashMap<>(connectionConfig);
        resetConnectionStatus();
    }

    /** 접속 계정을 바꿨을 때처럼 다시 검사해야 하는 경우. 마지막 검사 시각은 남겨 둔다. */
    public void resetConnectionStatus() {
        this.connectionStatus = ConnectionStatus.UNVERIFIED;
    }
}
