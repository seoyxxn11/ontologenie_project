package com.axcore.platform.ontology.service;

import com.axcore.platform.auth.service.DataPurpose;
import com.axcore.platform.global.security.LoginAccount;
import com.axcore.platform.ontology.dto.ObjectSearchRequest;
import com.axcore.platform.ontology.dto.ObjectSearchResponse;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;

/** 076 객체 검색을 Explorer와 Agent가 같은 권한 기준으로 호출하는 계약. */
public interface ObjectSearchQuery {

    /** {@link #findByIds}가 한 번에 받는 ID 수. */
    int MAX_IDS = 100;

    Page<ObjectSearchResponse> search(LoginAccount actor, ObjectSearchRequest request);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    Page<ObjectSearchResponse> search(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request);

    /**
     * 위와 같고, Agent 도구만 쓰는 조건(값 목록·속성 비교·정렬)을 더한다. 076 입력에는 없다. 같은 속성을 request.propertyValues와 값 목록에
     * 함께 넣으면 입력 오류다.
     */
    Page<ObjectSearchResponse> search(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request, Extras extras);

    /**
     * 076과 같은 권한·조건으로 맞는 객체의 전체 건수와 ID를 id 순으로 돌려준다(380). 건수가 limit를 넘으면 ID는 읽지 않고 비운다. Agent
     * Object Set을 조건으로 만들 때 쓴다. 검색어(q)는 받지 않고, page·size는 보지 않는다.
     */
    IdSearch searchIds(LoginAccount actor, ObjectSearchRequest request, int limit);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    IdSearch searchIds(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request, int limit);

    /** 위와 같고, Agent 전용 조건(값 목록·속성 비교)을 더한다. 정렬은 보지 않는다(ID는 id 순). */
    IdSearch searchIds(
            LoginAccount actor,
            DataPurpose purpose,
            ObjectSearchRequest request,
            Extras extras,
            int limit);

    /**
     * 위 search(extras)와 같은 권한·조건에 맞는 객체 전체의 숫자 속성 합계(속성 코드 → 합계). Agent 객체 목록이 일부 쪽만 받아도 전체 합계를 답하게
     * 한다. 볼 수 없는 민감 속성과 값이 없는 속성은 빠진다. 유형을 지정해야 하고 검색어(q)는 받지 않는다. 볼 수 없는 유형이면 빈 값이다.
     */
    Map<String, BigDecimal> sums(
            LoginAccount actor, DataPurpose purpose, ObjectSearchRequest request, Extras extras);

    /**
     * Agent 전용 조건. propertyValueSets는 속성마다 값 목록(그중 하나와 같으면 된다), comparisons는 같은 객체의 두 속성 비교(예:
     * value &gt; threshold_high, 418), sort는 정렬할 속성(null이면 id 순)이다.
     */
    record Extras(
            Map<String, List<String>> propertyValueSets, List<Comparison> comparisons, Sort sort) {
        public static final Extras NONE = new Extras(Map.of(), List.of(), null);

        public Extras {
            propertyValueSets =
                    propertyValueSets == null ? Map.of() : Map.copyOf(propertyValueSets);
            comparisons = comparisons == null ? List.of() : List.copyOf(comparisons);
        }

        public static Extras of(Map<String, List<String>> propertyValueSets) {
            return new Extras(propertyValueSets, List.of(), null);
        }
    }

    /** 같은 객체의 두 속성 비교. operator는 &gt;, &gt;=, &lt;, &lt;= 중 하나다. */
    record Comparison(String left, String operator, String right) {}

    /** 정렬할 속성. */
    record Sort(String property, boolean descending) {}

    /** 조건에 맞는 전체 건수와 객체 ID. 건수가 상한을 넘으면 ids는 비어 있다. */
    record IdSearch(long total, List<Long> ids) {
        public IdSearch {
            ids = List.copyOf(ids);
        }
    }

    /**
     * 볼 수 있는 유형의 문자 속성 중 값이 정확히 같은(앞뒤 공백 무시) 활성 객체를 id 순으로 limit건까지 찾는다(372). 076으로 찾지 못한 이름을 Agent
     * 대상 찾기가 다시 찾을 때 쓴다. 권한은 076과 같다. 민감 속성은 민감 값을 볼 수 있을 때만 찾는다.
     */
    List<ObjectSearchResponse> findByPropertyValue(LoginAccount actor, String value, int limit);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    List<ObjectSearchResponse> findByPropertyValue(
            LoginAccount actor, DataPurpose purpose, String value, int limit);

    /**
     * 객체 ID 목록 중 볼 수 있는 유형의 활성 객체를 id 순으로 돌려준다(380). Agent Object Set의 목록 보기처럼 이미 ID를 아는 객체를 한 번에
     * 읽을 때 쓴다. 권한·가림은 076과 같고, 없거나 볼 수 없는 ID는 빠진다. 한 번에 {@value #MAX_IDS}개까지 받는다.
     */
    List<ObjectSearchResponse> findByIds(LoginAccount actor, Collection<Long> ids);

    /** 위와 같고, 읽는 용도를 정한다. Agent 도구처럼 외부 모델에 보내는 값은 DataPurpose.MODEL로 읽어 민감 값을 항상 가린다(384). */
    List<ObjectSearchResponse> findByIds(
            LoginAccount actor, DataPurpose purpose, Collection<Long> ids);

    /**
     * 객체 ID 목록 중 지금도 볼 수 있는 유형의 활성 객체 ID만 받은 순서대로 돌려준다(380). Agent Object Set이 답변 중에 삭제되거나 볼 수 없게 된
     * 객체를 빼고 관계를 건널 때 쓴다. 속성은 읽지 않는다.
     */
    List<Long> findAvailableIds(LoginAccount actor, Collection<Long> ids);
}
