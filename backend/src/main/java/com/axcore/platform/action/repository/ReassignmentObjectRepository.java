package com.axcore.platform.action.repository;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.ontology.repository.ScopedObjectReader;
import com.axcore.platform.ontology.repository.ScopedObjectReader.ScopedObject;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 재배정 계획 검사와 Agent 완료 시각 계산이 읽는 온톨로지 객체. 객체 유형은 코드로, 속성은 properties의 키로 찾는다.
 *
 * <p>직접 SQL로 읽지 않고 공통 읽기 진입점({@link ScopedObjectReader})을 거친다(S15P31C106-384·390). 부르는 사람의 조회
 * 범위({@link DataAccess})를 받아, 볼 수 없는 유형의 객체는 없는 객체처럼 빠지고 볼 수 없는 민감 값은 null이다. 그래서 검사에 필요한 값이 가려지면 그
 * 검사는 "속성이 없음"으로 막힌다(값을 알려 주지 않고 실패하는 쪽을 택한다).
 */
@Component
@RequiredArgsConstructor
public class ReassignmentObjectRepository {

    private final ScopedObjectReader reader;

    /** ID로 객체를 읽는다. 없는 ID와 볼 수 없는 유형의 객체는 결과에 없다. */
    public Map<Long, ObjectRow> findByIds(DataAccess access, Collection<Long> ids) {
        Map<Long, ObjectRow> rows = new LinkedHashMap<>();
        reader.findByIds(access, ids).forEach((id, object) -> rows.put(id, ObjectRow.of(object)));
        return rows;
    }

    /** 유형이 typeCode인 활성 객체 중 properties[key]가 values 중 하나인 것. 볼 수 없는 유형이나 민감 속성이면 빈 목록이다. */
    public List<ObjectRow> findActiveByProperty(
            DataAccess access, String typeCode, String key, Collection<String> values) {
        return reader.findActiveByProperty(access, typeCode, key, values).stream()
                .map(ObjectRow::of)
                .toList();
    }

    /** 객체 한 행. 속성 값은 자료형에 따라 숫자·문자열로 오므로 text·number로 읽는다. 표시 이름과 속성은 가림을 적용한 값이다. */
    public record ObjectRow(
            long id,
            String typeCode,
            String status,
            String displayName,
            Map<String, Object> properties) {

        static ObjectRow of(ScopedObject object) {
            return new ObjectRow(
                    object.id(),
                    object.typeCode(),
                    object.status(),
                    object.displayName(),
                    object.properties());
        }

        public boolean isActive() {
            return "ACTIVE".equals(status);
        }

        /** 속성 값을 문자열로 읽는다. 숫자는 20과 20.0이 같도록 정규화한다. 없으면 null. */
        public String text(String key) {
            Object value = properties.get(key);
            if (value == null) {
                return null;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            }
            return value.toString();
        }

        /** 숫자 속성을 읽는다. DECIMAL은 문자열로 저장될 수 있다. 숫자가 아니거나 없으면 null. */
        public BigDecimal number(String key) {
            String text = text(key);
            if (text == null || text.isBlank()) {
                return null;
            }
            try {
                return new BigDecimal(text.strip());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
