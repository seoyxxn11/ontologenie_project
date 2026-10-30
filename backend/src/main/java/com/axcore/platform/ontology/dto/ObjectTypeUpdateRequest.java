package com.axcore.platform.ontology.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;

/**
 * API 024. 보낸 필드만 바꾼다. 비울 수 없는 name·synonyms는 null이면 그대로 둔다. 비울 수 있는 칸(description, accessGroup,
 * displayPropertyId)은 "보내지 않음"과 "null을 보내 비움"을 구분해야 해서, record 대신 setter가 받은 칸을 기록한다. Jackson은 빠진
 * Optional 칸도 빈 Optional로 채워 둘을 구분하지 못한다.
 */
@Getter
public class ObjectTypeUpdateRequest {

    @Size(max = 200)
    @Pattern(regexp = "(?s).*\\S.*", message = "공백일 수 없습니다.")
    private String name;

    private String description;

    private List<@NotBlank String> synonyms;

    @Size(max = 100)
    private String accessGroup;

    @Min(1)
    private Long displayPropertyId;

    @NotNull @PositiveOrZero private Long rowVersion;

    // getter를 만들면 Jackson이 이 칸도 요청 JSON으로 채울 수 있어 bean 이름이 아닌 has* 메서드로만 읽는다.
    @Getter(AccessLevel.NONE)
    private boolean descriptionSent;

    @Getter(AccessLevel.NONE)
    private boolean accessGroupSent;

    @Getter(AccessLevel.NONE)
    private boolean displayPropertyIdSent;

    public boolean hasDescription() {
        return descriptionSent;
    }

    public boolean hasAccessGroup() {
        return accessGroupSent;
    }

    public boolean hasDisplayPropertyId() {
        return displayPropertyIdSent;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
        this.descriptionSent = true;
    }

    public void setSynonyms(List<String> synonyms) {
        this.synonyms = synonyms;
    }

    public void setAccessGroup(String accessGroup) {
        this.accessGroup = accessGroup;
        this.accessGroupSent = true;
    }

    public void setDisplayPropertyId(Long displayPropertyId) {
        this.displayPropertyId = displayPropertyId;
        this.displayPropertyIdSent = true;
    }

    public void setRowVersion(Long rowVersion) {
        this.rowVersion = rowVersion;
    }
}
