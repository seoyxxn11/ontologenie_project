package com.axcore.platform.review.service;

import com.axcore.platform.check.domain.CandidateCheckTarget;
import com.axcore.platform.check.service.CandidateCheckService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.review.dto.AiProposalRequest;
import com.axcore.platform.review.repository.AiProposalWriteRepository;
import com.axcore.platform.review.repository.ProposalReferenceRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AiProposalService {
    private final AiProposalWriteRepository repository;
    private final CandidateCheckService checks;
    private final ProposalReferenceRepository references;
    private final AiProposalSourcePolicy sourcePolicy;

    @Transactional
    public List<Long> save(long analysisRunId, List<AiProposalRequest> received) {
        // 금지·민감 컬럼의 예시 값을 지우고 금지 컬럼을 매핑하는 후보를 표시한 뒤 저장한다(399).
        List<AiProposalRequest> proposals = received.stream().map(sourcePolicy::apply).toList();
        for (int i = 0; i < proposals.size(); i++) {
            Set<Integer> numbers = new HashSet<>();
            for (var option : proposals.get(i).options()) {
                if (!numbers.add(option.optionNo())) {
                    throw BusinessException.invalidField(
                            "proposals[" + i + "].options", "선택지 번호는 중복될 수 없습니다.");
                }
            }
        }
        List<Long> ids = new ArrayList<>();
        try {
            for (var proposal : proposals) {
                ids.add(repository.insert(analysisRunId, proposal));
            }
        } catch (DataIntegrityViolationException ex) {
            throw BusinessException.invalidField("proposals", "후보의 형식과 값을 확인해 주세요.");
        }
        // 같은 분석의 매핑 후보까지 모두 저장한 뒤 검사 기준을 고정한다. 검사 대상이 아닌 종류는 접수하지 않는다.
        for (int i = 0; i < ids.size(); i++) {
            var proposal = proposals.get(i);
            if (!CandidateCheckTarget.requiresCheck(proposal.itemKind())) {
                continue;
            }
            Set<Long> types = new HashSet<>();
            collectTypes(analysisRunId, proposal.itemKind(), proposal.payload(), types);
            for (var option : proposal.options()) {
                collectTypes(analysisRunId, proposal.itemKind(), option.payload(), types);
            }
            checks.enqueue(ids.get(i), 1, analysisRunId, types, null);
        }
        return List.copyOf(ids);
    }

    private void collectTypes(
            long analysisRunId, String kind, Map<String, Object> payload, Set<Long> types) {
        if (kind.equals("OBJECT_TYPE") && payload.get("code") instanceof String code) {
            references.objectType(code).ifPresent(type -> types.add(type.id()));
        }
        collectObjectTypeCodes(payload, types);
        if (payload.get("linkTypeCode") instanceof String code) {
            references
                    .relationByCode(code)
                    .ifPresent(
                            relation -> {
                                types.add(relation.source().id());
                                types.add(relation.target().id());
                            });
            for (var definition : references.pendingDefinitions(analysisRunId, "LINK_TYPE", code)) {
                collectObjectTypeCodes(definition, types);
            }
        }
        // 관계 후보에 함께 담긴 관계 유형(payload.linkType)의 양 끝 유형도 검사 기준에 넣는다.
        if (payload.get("linkType") instanceof Map<?, ?> linkType) {
            for (String key : List.of("sourceTypeCode", "targetTypeCode")) {
                if (linkType.get(key) instanceof String code) {
                    references.objectType(code).ifPresent(type -> types.add(type.id()));
                }
            }
        }
        Object id = payload.get("linkTypeId");
        if (id instanceof Integer || id instanceof Long) {
            references
                    .relationById(((Number) id).longValue())
                    .ifPresent(
                            relation -> {
                                types.add(relation.source().id());
                                types.add(relation.target().id());
                            });
        }
    }

    private void collectObjectTypeCodes(Map<String, Object> payload, Set<Long> types) {
        for (String key : List.of("objectTypeCode", "sourceTypeCode", "targetTypeCode")) {
            if (payload.get(key) instanceof String code) {
                references.objectType(code).ifPresent(type -> types.add(type.id()));
            }
        }
    }
}
