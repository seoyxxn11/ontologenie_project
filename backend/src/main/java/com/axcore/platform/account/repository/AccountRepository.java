package com.axcore.platform.account.repository;

import com.axcore.platform.account.domain.Account;
import com.axcore.platform.account.domain.AccountStatus;
import com.axcore.platform.account.domain.RoleCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @EntityGraph(attributePaths = "role")
    Optional<Account> findByLoginId(String loginId);

    @EntityGraph(attributePaths = "role")
    Optional<Account> findWithRoleById(Long id);

    boolean existsByRole_Code(RoleCode code);

    boolean existsByLoginId(String loginId);

    /**
     * 계정 목록 (API 004). pattern은 로그인 ID나 표시 이름에 대소문자 없이 맞춰 보는 LIKE 패턴이다. 검색어가 없으면 {@code %}를 넘긴다.
     * 상태·역할이 null이면 거르지 않는다. 만든 순서로 돌려준다.
     */
    @EntityGraph(attributePaths = "role")
    @Query(
            value =
                    """
                    select a from Account a
                    where (lower(a.loginId) like :pattern escape '\\'
                           or lower(a.displayName) like :pattern escape '\\')
                      and (:status is null or a.status = :status)
                      and (:roleCode is null or a.role.code = :roleCode)
                    order by a.id
                    """,
            countQuery =
                    """
                    select count(a) from Account a
                    where (lower(a.loginId) like :pattern escape '\\'
                           or lower(a.displayName) like :pattern escape '\\')
                      and (:status is null or a.status = :status)
                      and (:roleCode is null or a.role.code = :roleCode)
                    """)
    Page<Account> search(
            @Param("pattern") String pattern,
            @Param("status") AccountStatus status,
            @Param("roleCode") RoleCode roleCode,
            Pageable pageable);

    /**
     * 활성 ADMIN 계정 ID를 잠그고 읽는다. 두 관리자가 동시에 서로를 비활성화해도 마지막 관리자가 남게, 검사가 끝날 때까지 다른 변경을 기다리게 한다(API
     * 007).
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    select a.id from account a
                    where a.status = 'ACTIVE'
                      and a.role_id = (select r.id from role r where r.code = 'ADMIN')
                    for update
                    """)
    List<Long> lockActiveAdminIds();

    /**
     * 실패 횟수를 1 올리고, maxFailures에 닿으면 잠근다. 동시에 틀려도 횟수가 빠지지 않게 DB에서 한 번에 바꾼다. 로그인 기록은 관리자 수정이 아니므로
     * row_version과 수정 시각·계정은 바꾸지 않는다.
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    """
                    update account
                    set failed_login_count = failed_login_count + 1,
                        status = case when failed_login_count + 1 >= :maxFailures
                                      then 'LOCKED' else status end
                    where id = :id and status = 'ACTIVE'
                    """)
    int recordLoginFailure(@Param("id") Long id, @Param("maxFailures") int maxFailures);

    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    """
                    update account
                    set failed_login_count = 0, last_login_at = :loggedInAt
                    where id = :id
                    """)
    int recordLoginSuccess(@Param("id") Long id, @Param("loggedInAt") Instant loggedInAt);
}
