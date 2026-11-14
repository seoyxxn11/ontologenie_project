package com.axcore.platform.mapping.repository;

/**
 * 원천 컬럼 하나가 담기는 속성(403).
 *
 * @param resourceKey 원천 표 키(소문자, {@code 스키마.표})
 * @param columnName 원천 컬럼 이름
 * @param objectTypeCode 속성의 객체 유형 코드
 * @param propertyCode 속성 코드
 */
public record MappedSourceColumn(
        String resourceKey, String columnName, String objectTypeCode, String propertyCode) {}
