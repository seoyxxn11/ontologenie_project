package com.axcore.platform.source.service;

/** 원천 접속 비밀번호를 암·복호화하지 못했다. 키 설정이 없거나 틀렸거나, 저장한 값이 다른 키·원천·용도로 만들어진 경우다. 메시지에 비밀번호와 키를 넣지 않는다. */
public class SourceCredentialException extends RuntimeException {

    public SourceCredentialException(String message) {
        super(message);
    }

    public SourceCredentialException(String message, Throwable cause) {
        super(message, cause);
    }
}
