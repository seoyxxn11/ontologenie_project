package com.axcore.platform.ontology.repository;

/**
 * 연결 대상이 되는 객체의 정의(Jira 387). 검사(191 {@code CheckObjectReader}), 연결 재계산(181 {@link
 * RuleLinkRepository}), 확정 연결 저장(329·366 {@link ConfirmedLinkRepository})이 같은 조건을 써서, 같은 관계를 두고 서로
 * 다른 객체 범위로 판단하지 않게 한다.
 *
 * <p>연결 대상: 객체가 ACTIVE(원천에서 지우지 않음)이고, 수집한 매핑이 꺼져 있지 않다. 매핑을 끄면(API 036 {@code isActive=false}) 그
 * 매핑의 객체는 ACTIVE로 남지만 더 갱신되지 않는다. 매핑을 교체하면 같은 원천 행의 옛 객체·새 객체가 같이 남는다. 그래서 꺼진 매핑의 객체는 연결 대상에서 뺀다.
 *
 * <p>매핑이 없는 플랫폼 객체(원천 키 없음)는 ACTIVE면 대상이다. 지금은 만드는 코드가 없다. 검사는 원천 자원에서 수집한 객체만 판정하므로 이 조건에 더해 원천
 * 자원으로 좁힌다.
 *
 * <p>SQL 조건 문자열로 두는 이유: 세 곳 모두 {@code object_instance}를 JdbcClient로 읽는다(Entity 없음, 각 Repository
 * 설명). 매핑은 하위 조회로 보므로 부르는 쪽이 join하지 않아도 된다.
 */
public final class LinkableObjects {

    private LinkableObjects() {}

    /**
     * 별칭이 {@code alias}인 {@code object_instance} 행이 연결 대상인지의 SQL 조건.
     *
     * @param alias 상수 별칭만 넘긴다(SQL에 그대로 들어간다)
     */
    public static String condition(String alias) {
        return "("
                + alias
                + ".status = 'ACTIVE' and not exists (select 1 from object_source_mapping lm"
                + " where lm.id = "
                + alias
                + ".source_mapping_id and not lm.is_active))";
    }
}
