package com.axcore.platform.ontology.service;

/** 정의 목록 검색어를 ILIKE 부분 일치 패턴으로 바꾼다. 사용자가 입력한 %·_는 글자 그대로 찾는다. */
final class SearchPattern {

    private SearchPattern() {}

    /** 검색어가 비어 있으면 모두 맞는 패턴을 돌려준다. */
    static String contains(String q) {
        if (q == null || q.isBlank()) {
            return "%";
        }
        String escaped = q.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
