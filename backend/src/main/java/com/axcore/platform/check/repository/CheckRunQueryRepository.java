package com.axcore.platform.check.repository;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** 검사 결과 조회에 필요한 실행 존재 여부와 상태를 읽는다. */
@Repository
@RequiredArgsConstructor
public class CheckRunQueryRepository {

    private final JdbcClient jdbcClient;

    public Optional<String> findStatusById(long checkRunId) {
        return jdbcClient
                .sql(
                        """
                        select status
                        from check_run
                        where id = :checkRunId
                        """)
                .param("checkRunId", checkRunId)
                .query(String.class)
                .optional();
    }
}
