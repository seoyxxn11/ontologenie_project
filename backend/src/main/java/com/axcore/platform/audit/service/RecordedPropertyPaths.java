package com.axcore.platform.audit.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 감사 기록(실행 입력·결과·변경 내용)을 남기는 쪽이 그 기록의 자리를 알린다. 실행 응답(API 091)과 실행 이력 조회(092·097·098), 재배정 계획
 * 조회(093~095)는 알린 자리만 보여 주는 허용 목록으로 가린다(S15P31C106-205·390·404).
 *
 * <ul>
 *   <li>{@link Kind#PROPERTY}: 객체 속성 값이 든 자리. 보는 사람이 그 유형을 볼 수 없거나 민감 값을 볼 수 없으면 가린다.
 *   <li>{@link Kind#PLAIN}: 객체 속성 값이 아닌 자리(ID·건수·정의 등). 그대로 보여 준다. 키가 비면 기록 전체다.
 * </ul>
 *
 * <p>알리지 않은 기록(Handler 입력·변경 표)과, 알린 기록 안의 알리지 않은 자리는 값을 가린다. PLAIN 안에 알린 PROPERTY 자리는 PROPERTY 규칙을
 * 따른다. 기록하는 값의 모양을 바꾸면 이 자리도 함께 바꾼다. 같은 자리를 두 번 알리거나 PROPERTY 자리 아래에 다른 자리를 알리면 시작할 때 실패한다.
 */
public interface RecordedPropertyPaths {

    List<Path> recordedPropertyPaths();

    /**
     * Handler 키별로, 그 Handler의 차단 문구에 이름·일반 속성 값이 들어갈 수 있는 객체 유형 코드(404). 차단 문구는 자리별로 가릴 수 없어, 보는
     * 사람이 이 유형을 모두 볼 수 있을 때만 보인다({@link AuditValueMasker#maskBlockedReason}). 업무 조건으로 막는 Handler는
     * 반드시 알린다.
     */
    default Map<String, Set<String>> blockedReasonObjectTypes() {
        return Map.of();
    }

    /** 값이 든 곳. INPUT·RESULT는 Handler 키, CHANGE는 변경된 표 이름으로 기록을 고른다. RESULT는 성공한 실행의 결과다. */
    enum Section {
        INPUT,
        RESULT,
        CHANGE
    }

    /** 자리의 종류. */
    enum Kind {
        PROPERTY,
        PLAIN
    }

    /**
     * 기록 하나 안의 자리 하나. keys는 JSON 안의 경로이고 {@code "*"}는 목록의 모든 원소다. 예: {@code ["source_snapshot",
     * "operations", "*", "status"]}. PROPERTY만 objectTypeCode·propertyCode가 있다.
     */
    record Path(
            Section section,
            String owner,
            List<String> keys,
            Kind kind,
            String objectTypeCode,
            String propertyCode) {

        public Path {
            keys = List.copyOf(keys);
            if (!keys.isEmpty() && "*".equals(keys.getLast())) {
                throw new IllegalArgumentException("경로는 값이 든 키로 끝나야 합니다: " + keys);
            }
            if (keys.isEmpty() && kind == Kind.PROPERTY) {
                throw new IllegalArgumentException("기록 전체는 PLAIN으로만 알릴 수 있습니다: " + owner);
            }
            if ((kind == Kind.PROPERTY) != (objectTypeCode != null && propertyCode != null)) {
                throw new IllegalArgumentException("속성 자리만 유형·속성 코드를 가집니다: " + keys);
            }
        }

        /** Handler 입력 안의 객체 속성 값. */
        public static Path input(
                String handlerKey, List<String> keys, String objectTypeCode, String propertyCode) {
            return new Path(
                    Section.INPUT, handlerKey, keys, Kind.PROPERTY, objectTypeCode, propertyCode);
        }

        /** 변경된 표 행 안의 객체 속성 값. */
        public static Path change(
                String tableName, List<String> keys, String objectTypeCode, String propertyCode) {
            return new Path(
                    Section.CHANGE, tableName, keys, Kind.PROPERTY, objectTypeCode, propertyCode);
        }

        /** Handler 입력 안의 속성 값이 아닌 자리. */
        public static Path plainInput(String handlerKey, List<String> keys) {
            return new Path(Section.INPUT, handlerKey, keys, Kind.PLAIN, null, null);
        }

        /** Handler 결과 안의 객체 속성 값. */
        public static Path result(
                String handlerKey, List<String> keys, String objectTypeCode, String propertyCode) {
            return new Path(
                    Section.RESULT, handlerKey, keys, Kind.PROPERTY, objectTypeCode, propertyCode);
        }

        /** Handler 결과 안의 속성 값이 아닌 자리. */
        public static Path plainResult(String handlerKey, List<String> keys) {
            return new Path(Section.RESULT, handlerKey, keys, Kind.PLAIN, null, null);
        }

        /** 변경된 표 행 안의 속성 값이 아닌 자리. 키가 비면 행 전체다. */
        public static Path plainChange(String tableName, List<String> keys) {
            return new Path(Section.CHANGE, tableName, keys, Kind.PLAIN, null, null);
        }
    }
}
