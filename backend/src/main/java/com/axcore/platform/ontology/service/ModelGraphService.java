package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataAccess;
import com.axcore.platform.ontology.dto.ModelGraphResponse;
import com.axcore.platform.ontology.repository.ModelGraphQueryRepository;
import com.axcore.platform.ontology.repository.ObjectTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ModelGraphService {

    private final ObjectTypeRepository objectTypes;
    private final ModelGraphQueryRepository graphQuery;

    /** 노드와 선을 같은 스냅샷에서 읽어 조회 중 추가된 관계가 응답에 없는 노드를 가리키지 않도록 한다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelGraphResponse getGraph() {
        return new ModelGraphResponse(
                objectTypes.findModelGraphNodes(), graphQuery.findModelGraphEdges());
    }

    /** 볼 수 있는 유형과, 양 끝이 모두 볼 수 있는 유형인 관계만 남긴다. 숨긴 유형의 이름·관계 수를 알리지 않는다. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelGraphResponse getVisibleGraph(DataAccess access) {
        return new ModelGraphResponse(
                objectTypes.findModelGraphNodes().stream()
                        .filter(node -> access.canView(node.id()))
                        .toList(),
                graphQuery.findModelGraphEdges().stream()
                        .filter(
                                edge ->
                                        access.canView(edge.sourceTypeId())
                                                && access.canView(edge.targetTypeId()))
                        .toList());
    }
}
