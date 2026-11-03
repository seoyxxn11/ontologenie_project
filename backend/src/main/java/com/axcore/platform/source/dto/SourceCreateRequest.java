package com.axcore.platform.source.dto;

import com.axcore.platform.source.domain.SourceKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** API 010. code는 CDC 토픽 이름 앞부분이 되므로 영문 대문자·숫자·밑줄만 받는다(예: ERP_MAIN). */
public record SourceCreateRequest(
        @NotBlank
                @Size(max = 100)
                @Pattern(regexp = "[A-Z][A-Z0-9_]*", message = "영문 대문자로 시작하고 대문자·숫자·밑줄만 쓸 수 있습니다.")
                String code,
        @NotBlank @Size(max = 200) String name,
        @NotNull SourceKind sourceKind,
        Boolean syncEnabled,
        @Min(1) Integer syncIntervalMinutes,
        @NotNull Map<String, Object> connectionConfig,
        List<@Valid CredentialRequest> credentials) {}
