package com.axcore.platform.ontology.repository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 민감에서 내린 속성의 마지막 시각(S15P31C106-431). 저장된 사본을 가릴 때 {@code DataAccess.asOf}가 쓴다.
 *
 * <p>JdbcClient를 쓴다. 키가 (유형, 속성 코드) 둘이고 내릴 때마다 시각만 덮어쓰는(upsert) 작은 기록 표라, 같은 auth 범위
 * 계산(DataScopeRepository)처럼 SQL로 다룬다.
 */
@Slf4j
@Repository
public class PropertyRestrictionHistoryRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate afterCommit;

    public PropertyRestrictionHistoryRepository(
            JdbcClient jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.afterCommit = new TransactionTemplate(transactionManager);
        this.afterCommit.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 이 속성을 지금 민감에서 내렸다고 남긴다. 이미 있으면 더 늦은 시각으로 바꾼다. 시각은 트랜잭션 시작(now())이 아니라 이 문장을 실행한 때이고, 커밋한 뒤에 한
     * 번 더 늦춘다. 내리는 트랜잭션이 커밋되기 전(아직 민감인 동안)에 다른 트랜잭션이 만든 사본도 그 전으로 보기 위해서다(431).
     */
    public void recordDowngrade(long objectTypeId, String propertyCode) {
        upsert(objectTypeId, propertyCode);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            // 커밋이 끝난 변경이라 실패를 호출한 쪽에 돌려주지 않는다. 문장 실행 때의 기록이 이미 있어 잃는 것은 늦추는 몫뿐이다.
                            try {
                                afterCommit.executeWithoutResult(
                                        status -> upsert(objectTypeId, propertyCode));
                            } catch (RuntimeException e) {
                                log.warn(
                                        "민감에서 내린 시각을 커밋 뒤에 늦추지 못했습니다: 유형 {} 속성 {}",
                                        objectTypeId,
                                        propertyCode,
                                        e);
                            }
                        }
                    });
        }
    }

    private void upsert(long objectTypeId, String propertyCode) {
        jdbc.sql(
                        """
                        insert into property_restriction_history as h (object_type_id, property_code, restricted_until)
                        values (:type, :code, clock_timestamp())
                        on conflict (object_type_id, property_code)
                        do update set restricted_until = greatest(h.restricted_until, excluded.restricted_until)
                        """)
                .param("type", objectTypeId)
                .param("code", propertyCode)
                .update();
    }

    /** 유형 ID → 속성 코드 → 마지막으로 민감에서 내린 시각. */
    public Map<Long, Map<String, Instant>> findAll() {
        Map<Long, Map<String, Instant>> history = new HashMap<>();
        jdbc.sql(
                        "select object_type_id, property_code, restricted_until from property_restriction_history")
                .query(
                        rs -> {
                            history.computeIfAbsent(rs.getLong(1), ignored -> new HashMap<>())
                                    .put(
                                            rs.getString(2),
                                            rs.getObject(3, OffsetDateTime.class).toInstant());
                        });
        return history;
    }
}
