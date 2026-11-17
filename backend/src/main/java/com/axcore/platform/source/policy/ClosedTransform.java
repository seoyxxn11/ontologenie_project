package com.axcore.platform.source.policy;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import java.util.List;
import java.util.Map;

/**
 * 제한 컬럼에서 일반 값을 뽑는 매핑의 판단(S15P31C106-448, 391 예외).
 *
 * <p>속성 민감도는 매핑된 원천 컬럼 등급보다 낮을 수 없다(391). 다만 변환의 마지막 단계가 {@link TransformOperation#MAP_KNOWN}이면 결과는
 * 관리자가 적은 대응표의 바꿀 값 중 하나이거나 빈 값이다. 원천 값이 그대로 나올 길이 없으므로 그 매핑은 제한 컬럼을 담아도 일반 속성에 둘 수 있다. 예: 정비 지시 담당
 * 칸(제한, 부서명과 직원 이름이 섞임)에서 부서 코드만 뽑아 부서와 연결한다.
 *
 * <p>금지 컬럼은 예외가 아니다. 들이지 않는다. 대응표의 바꿀 값에 이름을 적으면 그 값이 일반으로 나간다. 대응표는 관리자가 쓰고 정의 변경 이력에 남는다.
 */
public final class ClosedTransform {

    private ClosedTransform() {}

    /** 이 변환 단계를 거친 값의 등급. 제한 컬럼이고 마지막 단계가 MAP_KNOWN이면 일반이다. 그 밖에는 컬럼 등급 그대로다. */
    public static SourceColumnGrade grade(SourceColumnGrade column, List<TransformStep> steps) {
        if (column == SourceColumnGrade.RESTRICTED
                && steps != null
                && !steps.isEmpty()
                && steps.getLast().op() == TransformOperation.MAP_KNOWN) {
            return SourceColumnGrade.NORMAL;
        }
        return column;
    }

    /** 저장된 매핑의 변환 단계(JSON)로 판단한다. */
    public static SourceColumnGrade gradeOfStored(
            SourceColumnGrade column, List<Map<String, Object>> steps) {
        if (column == SourceColumnGrade.RESTRICTED
                && steps != null
                && !steps.isEmpty()
                && TransformOperation.MAP_KNOWN
                        .name()
                        .equals(String.valueOf(steps.getLast().get("op")))) {
            return SourceColumnGrade.NORMAL;
        }
        return column;
    }
}
