package com.axcore.platform.source.domain;

/** 복호화한 원천 접속 계정. 원천에 접속하거나 AI에 넘길 때만 쓰고 로그·응답·DB에 남기지 않는다. */
public record SourceAccount(String username, String password) {

    // 실수로 로그에 찍혀도 비밀번호가 드러나지 않게 한다.
    @Override
    public String toString() {
        return "SourceAccount[username=" + username + ", password=****]";
    }
}
