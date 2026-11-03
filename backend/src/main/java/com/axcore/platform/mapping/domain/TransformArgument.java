package com.axcore.platform.mapping.domain;

/**
 * 변환 함수의 인자 설명. 변환 단계 JSON에서는 이름과 관계없이 {@code arg} 하나로 들어간다.
 *
 * @param name 인자의 뜻을 나타내는 이름(화면 입력칸 구분용)
 * @param type 인자의 JSON 자료형: string, integer, object
 * @param label 화면에 보일 한글 이름
 */
public record TransformArgument(String name, String type, String label) {}
