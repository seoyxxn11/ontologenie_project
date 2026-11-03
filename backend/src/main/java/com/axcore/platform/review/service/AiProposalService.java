package com.axcore.platform.review.service;

import com.axcore.platform.check.service.CandidateCheckService;
import com.axcore.platform.global.error.BusinessException;
import com.axcore.platform.review.dto.AiProposalRequest;
import com.axcore.platform.review.repository.AiProposalWriteRepository;
import com.axcore.platform.review.repository.ProposalReferenceRepository;
import java.time.Clock;
import java.time.Instant;
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
    private final Clock clock;

    @Transactional
    public List<Long> save(long analysisRunId, List<AiProposalRequest> proposals) {
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
            Instant now = Instant.now(clock);
            for (var proposal : proposals) {
                ids.add(repository.insert(analysisRunId, proposal, now));
            }
        } catch (DataIntegrityViolationException ex) {
            throw BusinessException.invalidField("proposals", "후보의 형식과 값을 확인해 주세요.");
        }
        // 같은 분석의 매핑 후보까지 모두 저장한 뒤 검사 기준을 고정한다.
        for (int i = 0; i < ids.size(); i++) {
            var proposal = proposals.get(i);
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
