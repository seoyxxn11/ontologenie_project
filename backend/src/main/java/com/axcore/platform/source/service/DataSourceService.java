package com.axcore.platform.source.service;

import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.global.error.CommonErrorCode;
import com.axcore.platform.source.domain.ConnectionStatus;
import com.axcore.platform.source.domain.CredentialPurpose;
import com.axcore.platform.source.domain.DataSource;
import com.axcore.platform.source.domain.SourceKind;
import com.axcore.platform.source.dto.CredentialRequest;
import com.axcore.platform.source.dto.SourceCreateRequest;
import com.axcore.platform.source.dto.SourceDetailResponse;
import com.axcore.platform.source.dto.SourceResponse;
import com.axcore.platform.source.dto.SourceResponse.CredentialResponse;
import com.axcore.platform.source.dto.SourceUpdateRequest;
import com.axcore.platform.source.repository.DataSourceRepository;
import com.axcore.platform.source.repository.SourceQueryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 원천 정보 (API 009~012, FN-SRC-01). 백엔드는 원천 DB에 접속하지 않는다. 접속 계정 비밀번호는 {@link
 * SourceCredentialService}로 암호화해 저장하고 응답에는 용도·계정명만 내보낸다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DataSourceService {

    private static final Set<String> CONFIG_KEYS = Set.of("host", "port", "database");
    private static final Set<String> SECRET_KEYS = Set.of("password", "secret", "api_key");

    private final DataSourceRepository dataSourceRepository;
    private final SourceQueryRepository queryRepository;
    private final SourceCredentialService credentialService;
    private final EntityManager entityManager;

    /** API 009. */
    public Page<SourceResponse> search(
            SourceKind sourceKind, ConnectionStatus connectionStatus, Pageable pageable) {
        Page<DataSource> page = dataSourceRepository.search(sourceKind, connectionStatus, pageable);
        Map<Long, List<CredentialResponse>> credentials =
                credentials(page.getContent().stream().map(DataSource::getId).toList());
        return page.map(
                source ->
                        SourceResponse.from(
                                source, credentials.getOrDefault(source.getId(), List.of())));
    }

    /** API 011. */
    public SourceDetailResponse get(Long id) {
        DataSource source = findDataSource(id);
        return SourceDetailResponse.of(
                response(source), queryRepository.findLatestCheck(id).orElse(null));
    }

    /**
     * API 010. 코드는 전체에서 유일하다. 동시에 같은 코드로 등록하면 DB 제약이 하나만 남긴다. 계정 비밀번호는 암호화해 저장하고, 암호화에 실패하면 원천도
     * 저장하지 않는다.
     */
    @Transactional
    public SourceResponse create(SourceCreateRequest request, Long accountId) {
        Map<String, Object> config =
                normalizeConfig(request.connectionConfig(), request.sourceKind());
        boolean syncEnabled = Boolean.TRUE.equals(request.syncEnabled());
        if (syncEnabled && request.syncIntervalMinutes() == null) {
            throw BusinessException.invalidField(
                    "syncIntervalMinutes", "syncEnabled가 true이면 수집 주기(분)가 필요합니다.");
        }
        List<CredentialRequest> credentials =
                validateCredentials(request.credentials(), request.sourceKind(), true);
        if (dataSourceRepository.existsByCode(request.code())) {
            throw new BusinessException(CommonErrorCode.CONFLICT, Map.of("field", "code"));
        }
        DataSource source =
                DataSource.register(
                        request.code(),
                        request.name(),
                        request.sourceKind(),
                        config,
                        syncEnabled,
                        request.syncIntervalMinutes());
        dataSourceRepository.saveAndFlush(source);
        storeCredentials(source.getId(), credentials, accountId);
        return response(source);
    }

    /**
     * API 012. 접속 설정이 실제로 바뀌거나 계정을 바꾸면 연결 상태를 UNVERIFIED로 되돌린다. 이름만 바꾸면 그대로다. 계정은 보낸 용도만 바꾸고 나머지는
     * 둔다.
     */
    @Transactional
    public SourceResponse update(Long id, SourceUpdateRequest request, Long accountId) {
        if (request.name() == null
                && request.connectionConfig() == null
                && (request.credentials() == null || request.credentials().isEmpty())) {
            throw BusinessException.invalidField(
                    "name", "name·connectionConfig·credentials 중 하나 이상 보내 주세요.");
        }
        DataSource source = findDataSource(id);
        List<CredentialRequest> credentials =
                validateCredentials(request.credentials(), source.getSourceKind(), false);
        if (request.rowVersion() != source.getRowVersion()) {
            throw new BusinessException(
                    CommonErrorCode.VERSION_CONFLICT,
                    Map.of(
                            "field", "rowVersion",
                            "expected", request.rowVersion(),
                            "actual", source.getRowVersion()));
        }
        if (request.name() != null) {
            source.rename(request.name());
        }
        if (request.connectionConfig() != null) {
            source.changeConnectionConfig(
                    normalizeConfig(request.connectionConfig(), source.getSourceKind()));
        }
        if (!credentials.isEmpty()) {
            storeCredentials(id, credentials, accountId);
            source.resetConnectionStatus();
            // 계정만 바뀌어 원천 행 값이 그대로여도 rowVersion을 올려, 이 화면을 보던 다른 사람의 수정이 덮어쓰지 않게 한다.
            entityManager.lock(source, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
        }
        // 응답의 rowVersion·수정 시각이 이번 변경을 반영하도록 먼저 DB에 쓴다.
        dataSourceRepository.flush();
        return response(source);
    }

    DataSource findDataSource(Long id) {
        return dataSourceRepository
                .findById(id)
                .orElseThrow(
                        () ->
                                new BusinessException(
                                        CommonErrorCode.RESOURCE_NOT_FOUND,
                                        Map.of("resource", "data_source", "id", id)));
    }

    private SourceResponse response(DataSource source) {
        return SourceResponse.from(
                source,
                credentials(List.of(source.getId())).getOrDefault(source.getId(), List.of()));
    }

    private Map<Long, List<CredentialResponse>> credentials(List<Long> dataSourceIds) {
        return queryRepository.findCredentials(dataSourceIds).stream()
                .collect(
                        Collectors.groupingBy(
                                SourceQueryRepository.Credential::dataSourceId,
                                LinkedHashMap::new,
                                Collectors.mapping(
                                        credential ->
                                                new CredentialResponse(
                                                        credential.purpose(),
                                                        credential.username()),
                                        Collectors.toList())));
    }

    private void storeCredentials(
            long dataSourceId, List<CredentialRequest> credentials, Long accountId) {
        for (CredentialRequest credential : credentials) {
            credentialService.store(
                    dataSourceId,
                    CredentialPurpose.valueOf(credential.purpose()),
                    credential.username(),
                    credential.password(),
                    accountId);
        }
    }

    /**
     * 용도마다 계정은 하나다. 파일·문서 원천에는 접속 계정이 없다. 등록(required)할 때 POSTGRES는 SYNC·AI 계정이 모두 있어야 한다. 수정은 보낸
     * 용도만 바꾸므로 하나만 보내도 된다.
     */
    private static List<CredentialRequest> validateCredentials(
            List<CredentialRequest> credentials, SourceKind sourceKind, boolean required) {
        List<CredentialRequest> list = credentials == null ? List.of() : credentials;
        Set<String> purposes = new HashSet<>();
        for (CredentialRequest credential : list) {
            if (!purposes.add(credential.purpose())) {
                throw BusinessException.invalidField("credentials", "같은 용도의 계정을 두 번 보낼 수 없습니다.");
            }
        }
        if (sourceKind != SourceKind.POSTGRES && !list.isEmpty()) {
            throw BusinessException.invalidField("credentials", "파일·문서 원천에는 접속 계정을 넣지 않습니다.");
        }
        if (required
                && sourceKind == SourceKind.POSTGRES
                && !purposes.containsAll(Set.of("SYNC", "AI"))) {
            throw BusinessException.invalidField(
                    "credentials", "POSTGRES 원천은 SYNC·AI 계정이 모두 필요합니다.");
        }
        return list;
    }

    /**
     * 접속 설정은 host·port·database만 받는다. 비밀값 키는 이름이 무엇이든 거절하고, POSTGRES는 세 값이 모두 필요하다. 값이 null인 키는 빼고
     * 저장한다.
     */
    private static Map<String, Object> normalizeConfig(
            Map<String, Object> config, SourceKind sourceKind) {
        for (String key : config.keySet()) {
            if (SECRET_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw BusinessException.invalidField(
                        "connectionConfig",
                        "비밀값은 connectionConfig에 넣을 수 없습니다. 계정은 credentials로 보냅니다.");
            }
            if (!CONFIG_KEYS.contains(key)) {
                throw BusinessException.invalidField(
                        "connectionConfig", "host·port·database만 넣을 수 있습니다.");
            }
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        Object host = config.get("host");
        Object port = config.get("port");
        Object database = config.get("database");
        if (host != null) {
            normalized.put("host", text(host, "connectionConfig.host"));
        }
        if (port != null) {
            if (!(port instanceof Integer number) || number < 1 || number > 65535) {
                throw BusinessException.invalidField(
                        "connectionConfig.port", "1~65535 사이의 정수여야 합니다.");
            }
            normalized.put("port", number);
        }
        if (database != null) {
            normalized.put("database", text(database, "connectionConfig.database"));
        }
        if (sourceKind == SourceKind.POSTGRES && normalized.size() != CONFIG_KEYS.size()) {
            throw BusinessException.invalidField(
                    "connectionConfig", "POSTGRES 원천은 host·port·database가 모두 필요합니다.");
        }
        return normalized;
    }

    private static String text(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw BusinessException.invalidField(field, "비어 있지 않은 문자열이어야 합니다.");
        }
        return text.strip();
    }
}
