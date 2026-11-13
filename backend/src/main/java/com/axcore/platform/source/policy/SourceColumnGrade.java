package com.axcore.platform.source.policy;

/**
 * 원천 컬럼의 등급(S15P31C106-384·391). 원천 쪽 권한과 publication이 같은 목록으로 만들어지고, 플랫폼은 이 등급으로 매핑·적재·속성 민감도를
 * 강제한다.
 */
public enum SourceColumnGrade {

    /** 들이지 않는다. 매핑·적재 대상이 될 수 없다. 원천 계정 권한에서도 빠진다. */
    FORBIDDEN,

    /** 들이지만 그 값을 담는 속성은 민감(RESTRICTED)이어야 한다. 사람은 허용된 역할만 보고, 외부 모델에는 보내지 않는다. */
    RESTRICTED,

    /** 정책 목록에 없는 컬럼. 서버가 따로 강제하는 것이 없다(분류가 끝났다는 뜻은 아니다). */
    NORMAL
}
