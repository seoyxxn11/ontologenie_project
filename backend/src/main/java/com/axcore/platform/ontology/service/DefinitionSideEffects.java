package com.axcore.platform.ontology.service;

import com.axcore.platform.audit.domain.ChangeEntry;
import com.axcore.platform.ontology.repository.DefinitionSnapshotRepository;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 정의 서비스가 요청 대상이 아닌 정의 행을 덩달아 바꿀 때 그 전후를 모은다(391). 예를 들어 속성 매핑이 원천 정책 때문에 속성을 민감으로 올리고 대표 속성을 비우는
 * 경우다. 직접 편집 기록({@link DefinitionEditRecorder})과 후보 승인 반영이 이 변경을 감사 기록에 함께 남긴다.
 *
 * <p>모은 값은 트랜잭션에 묶여 있고 트랜잭션이 끝나면 버린다. 트랜잭션 밖에서는 아무것도 모으지 않는다.
 */
@Component
@RequiredArgsConstructor
public class DefinitionSideEffects {

    private static final Object KEY = DefinitionSideEffects.class;

    private final DefinitionSnapshotRepository snapshots;
    private final EntityManager entityManager;

    /** 정의 행을 바꾸기 직전에 부른다. 같은 트랜잭션에서 처음 부를 때의 값을 변경 전으로 둔다. */
    public void beforeUpdate(String table, long id) {
        Map<Row, Map<String, Object>> pending = pending();
        Row row = new Row(table, id);
        if (pending == null || pending.containsKey(row)) {
            return;
        }
        entityManager.flush();
        snapshots.find(table, id).ifPresent(before -> pending.put(row, before));
    }

    /** 모은 행의 변경 전후. 값이 그대로인 행은 뺀다. 부르면 모은 것을 비운다. */
    public List<ChangeEntry> drain() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return List.of();
        }
        @SuppressWarnings("unchecked")
        Map<Row, Map<String, Object>> pending =
                (Map<Row, Map<String, Object>>) TransactionSynchronizationManager.getResource(KEY);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        entityManager.flush();
        List<ChangeEntry> changes = new ArrayList<>();
        pending.forEach(
                (row, before) ->
                        snapshots
                                .find(row.table(), row.id())
                                .filter(after -> !Objects.equals(before, after))
                                .ifPresent(
                                        after ->
                                                changes.add(
                                                        ChangeEntry.update(
                                                                row.table(),
                                                                Map.of("id", row.id()),
                                                                before,
                                                                after))));
        pending.clear();
        return changes;
    }

    private static Map<Row, Map<String, Object>> pending() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Map<Row, Map<String, Object>> pending =
                (Map<Row, Map<String, Object>>) TransactionSynchronizationManager.getResource(KEY);
        if (pending == null) {
            pending = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(KEY, pending);
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
                        }
                    });
        }
        return pending;
    }

    private record Row(String table, long id) {}
}
