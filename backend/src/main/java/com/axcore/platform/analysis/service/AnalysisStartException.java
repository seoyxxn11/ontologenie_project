package com.axcore.platform.analysis.service;

/** AI에 분석 시작을 알리지 못했다. 메시지는 019에 실패 사유로 그대로 보이므로 접속 정보를 넣지 않는다. */
public class AnalysisStartException extends RuntimeException {

    public AnalysisStartException(String message) {
        super(message);
    }
}
