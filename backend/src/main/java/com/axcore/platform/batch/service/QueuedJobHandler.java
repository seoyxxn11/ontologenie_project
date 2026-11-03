package com.axcore.platform.batch.service;

import com.axcore.platform.batch.domain.QueuedJobType;
import java.util.Map;

/**
 * 작업 표의 작업 한 종류를 실제로 처리한다. 도메인마다 작업 종류 하나에 Bean 하나를 둔다. {@link QueuedJobRunner}가 행을 RUNNING으로 바꾼 뒤
 * 아래 순서로 부른다.
 *
 * <ol>
 *   <li>{@link #run}: 트랜잭션 밖에서 오래 걸리는 일(원천 읽기, 계산)을 한다. 플랫폼 DB에 쓰지 않는다.
 *   <li>{@link #complete}: 한 트랜잭션 안에서 결과를 저장하고 행을 SUCCEEDED로 바꾼다. 행이 SUCCEEDED가 아니면 실패로 처리한다.
 * </ol>
 *
 * <p>둘 중 하나가 예외를 던지면 행은 FAILED가 된다. 화면에 보일 실패 이유는 {@link QueuedJobFailure}로 던진다.
 *
 * @param <R> run이 만들어 complete에 넘기는 결과
 */
public interface QueuedJobHandler<R> {

    QueuedJobType type();

    R run(long jobId);

    void complete(long jobId, R result);

    /** 감사 기록 결과 줄의 result에 남길 요약. 건수 같은 값만 넣고 원천 행 값은 넣지 않는다. */
    default Map<String, Object> auditResult(R result) {
        return Map.of();
    }
}
