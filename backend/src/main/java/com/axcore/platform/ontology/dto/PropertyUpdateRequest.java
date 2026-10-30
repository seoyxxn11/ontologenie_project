package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * API 028. name·dataType·isRequired는 필수다. 선택 칸은 보내지 않으면 그대로 둔다. 비울 수 있는 칸(unit, temporalRole,
 * description)은 null을 보내면 비운다. 그래서 {@link ObjectTypeUpdateRequest}처럼 setter가 받은 칸을 기록한다. code는 바꿀 수
 * 없어 보내면 400이다(값이 null이어도).
 */
@Getter
public class PropertyUpdateRequest {

    @NotBlank
    @Size(max = 200)
    private String name;

    @NotNull
    @Pattern(
            regexp = "STRING|INTEGER|DECIMAL|DATE|TIME|DATETIME|BOOLEAN|JSON",
            message = "허용되지 않는 dataType 값입니다.")
    private String dataType;

    @NotNull private Boolean isRequired;

    private Boolean isIdentifier;

    @Size(max = 50)
    private String unit;

    private Map<String, Object> constraints;

    @Pattern(regexp = "NORMAL|RESTRICTED", message = "허용되지 않는 sensitivity 값입니다.")
    private String sensitivity;

    @Pattern(regexp = "AS_OF|EVENT_TIME", message = "허용되지 않는 temporalRole 값입니다.")
    private String temporalRole;

    private String description;

    @NotNull @PositiveOrZero private Long rowVersion;

    // getter를 만들면 Jackson이 이 칸도 요청 JSON으로 채울 수 있어 bean 이름이 아닌 has* 메서드로만 읽는다.
    @Getter(AccessLevel.NONE)
    private boolean codeSent;

    @Getter(AccessLevel.NONE)
    private boolean unitSent;

    @Getter(AccessLevel.NONE)
    private boolean temporalRoleSent;

    @Getter(AccessLevel.NONE)
    private boolean descriptionSent;

    public boolean hasCode() {
        return codeSent;
    }

    public boolean hasUnit() {
        return unitSent;
    }

    public boolean hasTemporalRole() {
        return temporalRoleSent;
    }

    public boolean hasDescription() {
        return descriptionSent;
    }

    /** 값은 쓰지 않는다. 보냈다는 사실만 남겨 서비스가 400으로 거절한다. */
    public void setCode(String code) {
        this.codeSent = true;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public void setIsRequired(Boolean isRequired) {
        this.isRequired = isRequired;
    }

    public void setIsIdentifier(Boolean isIdentifier) {
        this.isIdentifier = isIdentifier;
    }

    public void setUnit(String unit) {
        this.unit = unit;
        this.unitSent = true;
    }

    public void setConstraints(Map<String, Object> constraints) {
        this.constraints = constraints;
    }

    public void setSensitivity(String sensitivity) {
        this.sensitivity = sensitivity;
    }

    public void setTemporalRole(String temporalRole) {
        this.temporalRole = temporalRole;
        this.temporalRoleSent = true;
    }

    public void setDescription(String description) {
        this.description = description;
        this.descriptionSent = true;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }
}
