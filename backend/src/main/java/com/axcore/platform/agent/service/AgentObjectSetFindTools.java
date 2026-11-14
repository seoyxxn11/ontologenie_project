package com.axcore.platform.agent.service;

import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 목표 유형 찾기 도구(OBJECT_SET_FIND). AI는 시작 객체와 찾는 유형, 그 유형의 조건만 말하고 경로·방향은 서버가 정한다. 건너기를 AI가 단계마다 고르면
 * 조건을 중간 단계에 걸거나 방향을 거꾸로 쓰는 실수가 반복돼(GMS M1·G2·S5) 구조로 막는다. 설정
 * (axcore.agent.object-set-find.enabled)으로 켰을 때만 준다. 구현은 {@link AgentObjectSetTools#find}다.
 */
public class AgentObjectSetFindTools {
    private final AgentObjectSetTools sets;

    public AgentObjectSetFindTools(AgentObjectSetTools sets) {
        this.sets = sets;
    }

    @Tool(
            name = AgentObjectSetTools.OBJECT_SET_FIND,
            description =
                    "참고 객체에서 다른 유형의 객체를 찾는다. 시작 객체와 찾는 유형만 주면 서버가 관계 경로를 찾아 건넌다(최대 4단계)."
                            + " 조건(propertyValues·기간)은 찾는 유형에만 걸린다. 결과의 paths에는 경로마다 경로(route)·건수·objectSetId·찾은"
                            + " 객체의 값별 건수(valueCounts)·끝난 값으로 보이는 후보(finishedLike)가 온다. 결과가 있는 경로가 하나면"
                            + " objectSetId와 count가 맨 위에도 온다. 객체는 objectSetId로 OBJECT_SET_LIST를 불러 본다. 조건을 바꾸거나 경로"
                            + " 하나만 보려면 같은 objectIds·targetType에 propertyValues·pathIndex를 넣어 이 도구를 다시 부른다. 찾은 Object"
                            + " Set을 거르려고 관계 따라가기를 쓰지 않는다. 조건 값은 저장된 값만 받는다. 가장 짧은 경로만 본다.")
    public Map<String, Object> objectSetFind(
            @ToolParam(description = "시작 객체 ID 목록('참고 객체 ID', 최대 20개, 같은 유형)") List<Long> objectIds,
            @ToolParam(description = "찾는 객체 유형의 이름·코드·ID(예: 수주)") String targetType,
            @ToolParam(
                            description =
                                    "찾는 유형의 속성 조건. 속성 코드를 키로, 저장된 값 목록을 값으로 둔다. 값이 여럿이면 그중 하나와 같으면"
                                            + " 된다(예: {\"status\": [\"생산중\", \"접수\"]})",
                            required = false)
                    Map<String, Object> propertyValues,
            @ToolParam(description = "찾는 유형을 기간으로 거를 날짜·시각 속성 코드", required = false)
                    String dateProperty,
            @ToolParam(description = "기간 시작일(yyyy-MM-dd, 이 날 포함)", required = false) String from,
            @ToolParam(description = "기간 종료일(yyyy-MM-dd, 이 날 포함)", required = false) String to,
            @ToolParam(
                            description = "경로 번호. 앞 결과의 paths 중 하나만 다시 볼 때 넣는다. 비우면 모든 경로를 본다",
                            required = false)
                    Integer pathIndex) {
        // 값 하나만 보내도(예: {"status": "진행"}) 목록으로 받는다. 형식이 달라 입력을 읽지 못하는 일을 막는다.
        return sets.find(objectIds, targetType, propertyValues, dateProperty, from, to, pathIndex);
    }
}
