package com.axcore.platform.mapping.repository;

/**
 * 원천 표 하나를 담는 객체 유형(403).
 *
 * @param resourceKey 원천 표 키(소문자, {@code 스키마.표})
 * @param objectTypeCode 객체 유형 코드
 */
public record MappedSourceTable(String resourceKey, String objectTypeCode) {}
