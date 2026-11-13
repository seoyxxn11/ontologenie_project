package com.axcore.platform.agent.service;

import com.axcore.platform.agent.service.ObjectPathPlanner.ObjectPaths;
import com.axcore.platform.audit.domain.AgentResultCategory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 객체 유형 사이 경로 후보 조회 도구(S15P31C106-381). {@link ObjectPathPlanner}가 켜져 있을 때만 AI에게 준다
 * (axcore.agent.object-paths.enabled). 정의 정보만 보므로 기준 시점과 조회한 객체가 없다.
 */
public class AgentObjectPathTools {
    static final String OBJECT_PATHS = "OBJECT_PATHS";

    private final AgentQuerySession session;
    private final ObjectPathPlanner planner;

    public AgentObjectPathTools(AgentQuerySession session, ObjectPathPlanner planner) {
        this.session = session;
        this.planner = planner;
    }

    /** 최대 홉 안에 경로가 없으면 NO_DATA다. 유형을 찾지 못하면 입력 오류를 돌려준다. */
    @Tool(
            name = OBJECT_PATHS,
            description =
                    "두 객체 유형 사이에서 관계 유형을 따라 갈 수 있는 경로 후보를 짧은 것부터 최대 5개(최대 4단계) 알려 준다. 데이터는"
                            + " 조회하지 않는다. 찾는 정보가 2단계 이상 떨어진 유형일 때만 부르고, 꼭 불러야 하는 것은 아니다. 도착 유형은"
                            + " 질문에서 찾는 정보의 유형으로 정한다. 후보 중 질문 뜻에 맞는 경로를 골라 각 단계의 linkTypeId·direction으로"
                            + " 관계를 따라간다. fanOut이 MANY인 단계는 한 객체에 여러 건이 연결될 수 있다. truncated가 true면"
                            + " 4단계 안에 보이지 않은 경로가 더 있다. 같은 길이 후보의 순서는 뜻과 관계가 없다.")
    public Map<String, Object> objectPaths(
            @ToolParam(description = "출발 객체 유형의 이름·코드·ID(예: 설비). 기준 객체의 유형을 넣는다") String fromType,
            @ToolParam(description = "도착 객체 유형의 이름·코드·ID(예: 수주). 질문에서 찾는 정보의 유형을 넣는다")
                    String toType) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "fromType", fromType);
        AgentQueryTools.putIfPresent(input, "toType", toType);
        return session.run(
                OBJECT_PATHS,
                "경로 후보",
                input,
                null,
                null,
                () -> {
                    ObjectPaths paths = planner.plan(session.asker(), fromType, toType);
                    return new AgentQuerySession.Outcome(
                            paths,
                            paths.candidates().isEmpty()
                                    ? AgentResultCategory.NO_DATA
                                    : AgentResultCategory.FOUND,
                            List.of(),
                            null);
                });
    }
}
