package com.axcore.platform.review.dto;

/**
 * 제안이 가리키는 제한 컬럼 하나(S15P31C106-447). 값은 담지 않는다. 화면은 이 목록으로 "실제 값 확인" 버튼을 만든다.
 *
 * @param sourceResourceId 원천 테이블 ID
 * @param sourceName 원천 이름
 * @param resourceKey 원천 테이블({@code 스키마.표})
 * @param column 원천 컬럼 이름(카탈로그 기준)
 */
public record RestrictedColumnResponse(
        long sourceResourceId, String sourceName, String resourceKey, String column) {}
