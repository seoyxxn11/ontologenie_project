package com.axcore.platform.check.service;

import com.axcore.platform.check.domain.CheckDataBasis;
import com.axcore.platform.check.repository.CandidateCheckRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@Service
@RequiredArgsConstructor
public class CandidateCheckService {
    private final CandidateCheckRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    @Transactional
    public long enqueue(
            long proposalId, int revision, Long analysisRunId, Set<Long> types, long accountId) {
        var basis =
                new CheckDataBasis(
                        repository.loadBasis(proposalId, revision, types, analysisRunId));
        String key =
                "CANDIDATE:"
                        + proposalId
                        + ":"
                        + revision
                        + ":"
                        + digest(json.writeValueAsString(basis));
        return repository.enqueue(proposalId, revision, accountId, key, basis, Instant.now(clock));
    }

    private static String digest(String text) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", ex);
        }
    }
}
