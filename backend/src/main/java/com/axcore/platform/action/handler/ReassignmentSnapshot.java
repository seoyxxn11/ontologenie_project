package com.axcore.platform.action.handler;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 재배정 계획 항목의 스냅샷(reassignment_plan_item.source_snapshot)과 계획의 선정 근거(selection_basis)에 들어가는 필드.
 * Handler가 이 필드로 저장하고, 조회(093~095)는 이 필드만 골라 내보낸다(S15P31C106-404). 저장된 값에 다른 필드가 있어도 응답에는 나가지 않는다.
 */
public final class ReassignmentSnapshot {

    public static final String WORK_ORDER_NO = "workOrderNo";
    public static final String WORK_ORDER_STATUS = "workOrderStatus";
    public static final String OPERATIONS = "operations";
    public static final String OBJECT_ID = "objectId";
    public static final String SEQUENCE = "sequence";
    public static final String EQUIPMENT_CODE = "equipmentCode";
    public static final String STATUS = "status";

    /** 선정 근거에서 내보내는 유일한 필드. 대체 설비를 고른 이유 한 문장이다. */
    public static final String REASON = "reason";

    private static final List<String> SNAPSHOT_FIELDS = List.of(WORK_ORDER_NO, WORK_ORDER_STATUS);
    private static final List<String> OPERATION_FIELDS =
            List.of(OBJECT_ID, SEQUENCE, EQUIPMENT_CODE, STATUS);

    private ReassignmentSnapshot() {}

    /** 저장된 스냅샷에서 정한 필드만 고른다. 없는 필드는 넣지 않는다. 원래 값은 바꾸지 않는다. */
    public static Map<String, Object> explicit(Map<String, Object> stored) {
        Map<String, Object> picked = new LinkedHashMap<>();
        if (stored == null) {
            return picked;
        }
        pick(stored, SNAPSHOT_FIELDS, picked);
        if (stored.get(OPERATIONS) instanceof List<?> operations) {
            List<Map<String, Object>> pickedOperations = new ArrayList<>();
            for (Object operation : operations) {
                Map<String, Object> one = new LinkedHashMap<>();
                if (operation instanceof Map<?, ?> map) {
                    pick(map, OPERATION_FIELDS, one);
                }
                pickedOperations.add(one);
            }
            picked.put(OPERATIONS, pickedOperations);
        }
        return picked;
    }

    /** 선정 근거에서 이유 문장만 고른다. 문자열이 아니면 빈 값이다. */
    public static Map<String, Object> selectionBasis(Map<String, Object> stored) {
        if (stored != null && stored.get(REASON) instanceof String reason) {
            return Map.of(REASON, reason);
        }
        return Map.of();
    }

    private static void pick(Map<?, ?> from, List<String> fields, Map<String, Object> to) {
        for (String field : fields) {
            if (from.containsKey(field)) {
                to.put(field, from.get(field));
            }
        }
    }
}
