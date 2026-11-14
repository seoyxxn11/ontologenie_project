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

    /**
     * 모든 유형과 관계. 범위를 거르지 않으므로 밖에 내놓지 않고 {@link #getVisibleGraph} 안에서만 쓴다. 그 트랜잭션(REPEATABLE_READ)
     * 안에서 노드와 선을 같은 스냅샷으로 읽어, 조회 중 추가된 관계가 응답에 없는 노드를 가리키지 않는다.
     */
    private ModelGraphResponse graph() {
        return new ModelGraphResponse(
                objectTypes.findModelGraphNodes(), graphQuery.findModelGraphEdges());
    }

    /**
     * 볼 수 있는 유형과, 양 끝이 모두 볼 수 있는 유형인 관계만 남긴다. 숨긴 유형의 이름·연결 건수를 알리지 않는다. 관리자도 자기 데이터 범위로 받는다(427):
     * 연결 건수는 객체 데이터라 정의가 아니다.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ModelGraphResponse getVisibleGraph(DataAccess access) {
        ModelGraphResponse all = graph();
        return new ModelGraphResponse(
                all.nodes().stream().filter(node -> access.canView(node.id())).toList(),
                all.edges().stream()
                        .filter(
                                edge ->
                                        access.canView(edge.sourceTypeId())
                                                && access.canView(edge.targetTypeId()))
                        .toList());
    }
}
