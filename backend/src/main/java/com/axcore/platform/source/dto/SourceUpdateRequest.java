package com.axcore.platform.source.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/** API 012. 보낸 필드만 바꾼다(null은 그대로). 수집 주기는 072에서 바꾼다. */
public record SourceUpdateRequest(
        @Size(max = 200) @Pattern(regexp = "(?s).*\\S.*", message = "공백일 수 없습니다.") String name,
        Map<String, Object> connectionConfig,
        List<@Valid CredentialRequest> credentials,
        @NotNull @PositiveOrZero Long rowVersion) {}
