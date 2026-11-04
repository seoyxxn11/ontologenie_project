package com.axcore.platform.account.domain;

import com.axcore.platform.global.entity.BaseAuditEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 로그인 계정. 공개 가입은 없고(D01) 지우지 않고 DISABLED로 둔다(D06). */
@Getter
@Entity
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account extends BaseAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String loginId;

    private String displayName;

    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id")
    private Role role;

    @Enumerated(EnumType.STRING)
    private AccountStatus status;

    private boolean mustChangePassword;

    private Instant passwordChangedAt;

    private int failedLoginCount;

    private Instant lastLoginAt;

    @Version private int rowVersion;

    private Account(String loginId, String displayName, String passwordHash, Role role) {
        this.loginId = loginId;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = AccountStatus.ACTIVE;
        this.mustChangePassword = true;
    }

    /** 새 계정은 임시 비밀번호 상태로 만든다. 첫 로그인 뒤 비밀번호를 바꿔야 업무 API를 쓸 수 있다(D03). */
    public static Account create(
            String loginId, String displayName, String passwordHash, Role role) {
        return new Account(loginId, displayName, passwordHash, role);
    }

    /** 비밀번호를 바꾸면 임시 비밀번호 상태가 끝난다(D03). */
    public void changePassword(String passwordHash, Instant changedAt) {
        this.passwordHash = passwordHash;
        this.mustChangePassword = false;
        this.passwordChangedAt = changedAt;
    }

    public void rename(String displayName) {
        this.displayName = displayName;
    }

    public void changeRole(Role role) {
        this.role = role;
    }

    /** ACTIVE로 바꾸면 로그인 실패 횟수를 0으로 되돌린다. 5회 실패로 잠긴 계정은 이 방법으로만 푼다(API 007, D3). */
    public void changeStatus(AccountStatus status) {
        this.status = status;
        if (status == AccountStatus.ACTIVE) {
            this.failedLoginCount = 0;
        }
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }
}
