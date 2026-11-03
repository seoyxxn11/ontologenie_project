package com.axcore.platform.batch.service;

/**
 * 작업이 실패했고 그 이유를 화면에 보여 줘도 될 때 던진다. 메시지는 작업 표의 error_message와 감사 기록에 그대로 남는다. 비밀값과 원천 행 값을 넣지 않는다.
 * 다른 예외는 일반 문구로 바꿔 남기고 자세한 내용은 서버 로그에만 쓴다.
 */
public class QueuedJobFailure extends RuntimeException {

    public QueuedJobFailure(String message) {
        super(message);
    }

    public QueuedJobFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
