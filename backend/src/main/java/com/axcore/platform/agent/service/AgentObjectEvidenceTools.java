package com.axcore.platform.agent.service;

import com.axcore.platform.audit.domain.AgentResultCategory;
import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.ontology.dto.ObjectLinkEvidenceResponse;
import com.axcore.platform.ontology.service.ObjectEvidenceQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 관계 한 건의 원천 근거(078) 조회 도구(326). {@link ObjectEvidenceQuery} 구현(S15P31C106-138)이 있을 때만 AI에게 준다. 근거는
 * 조회 서비스가 요청자의 권한으로 가린 값이다.
 */
public class AgentObjectEvidenceTools {
    static final String OBJECT_LINK_EVIDENCE = "OBJECT_LINK_EVIDENCE";

    private final AgentQuerySession session;
    private final ObjectEvidenceQuery evidence;

    public AgentObjectEvidenceTools(AgentQuerySession session, ObjectEvidenceQuery evidence) {
        this.session = session;
        this.evidence = evidence;
    }

    @Tool(
            name = OBJECT_LINK_EVIDENCE,
            description = "관계 한 건이 어떤 원천 값·문장으로 이어졌는지 근거를 조회한다. 관계 ID는 OBJECT_LINKS 결과의 id를 쓴다.")
    public Map<String, Object> linkEvidence(
            @ToolParam(description = "기준 객체 ID") Long objectId,
            @ToolParam(description = "관계 ID") Long linkId) {
        Map<String, Object> input = new LinkedHashMap<>();
        AgentQueryTools.putIfPresent(input, "objectId", objectId);
        AgentQueryTools.putIfPresent(input, "linkId", linkId);
        return session.run(
                OBJECT_LINK_EVIDENCE,
                "관계 근거",
                input,
                AgentQueryTools.targetTypeOf(objectId),
                objectId,
                () ->
                        AgentQueryTools.noDataIfMissing(
                                () -> {
                                    long id = AgentQueryTools.required(objectId, "objectId");
                                    long link = AgentQueryTools.required(linkId, "linkId");
                                    ObjectLinkEvidenceResponse found =
                                            evidence.linkEvidence(
                                                    session.asker(), DataPurpose.MODEL, id, link);
                                    AgentResultCategory category = evidenceCategory(found);
                                    // 공개한 근거는 관계 양 끝 객체의 표 기준으로 시점을 구한다(145).
                                    return new AgentQuerySession.Outcome(
                                            found,
                                            category,
                                            List.of(Map.of("objectId", id)),
                                            category == AgentResultCategory.FOUND
                                                    ? session.dataAsOfLink(link)
                                                    : null);
                                }));
    }

    private static AgentResultCategory evidenceCategory(ObjectLinkEvidenceResponse evidence) {
        if (evidence.evidenceRestricted()) {
            return AgentResultCategory.DENIED;
        }
        // 원천 키는 객체 식별 정보다. 관계를 뒷받침하는 저장 근거는 별도로 확인한다.
        boolean hasEvidence =
                evidence.rawValue() != null
                        || evidence.transformedValue() != null
                        || !evidence.comparisons().isEmpty()
                        || evidence.quote() != null
                        || evidence.event() != null
                        || evidence.timeExpression() != null;
        return hasEvidence ? AgentResultCategory.FOUND : AgentResultCategory.NO_DATA;
    }
}
