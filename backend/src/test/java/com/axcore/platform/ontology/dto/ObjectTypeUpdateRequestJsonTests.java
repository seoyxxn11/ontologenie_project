package com.axcore.platform.ontology.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** API 024는 "보내지 않은 칸"과 "null을 보낸 칸"을 다르게 처리한다. JSON이 그 둘을 구분해 들어오는지 확인한다. */
class ObjectTypeUpdateRequestJsonTests {

    private final JsonMapper jsonMapper =
            // Spring Boot처럼 모르는 칸은 무시한다.
            JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    void omittedNullableFieldsAreNotMarkedAsSent() {
        ObjectTypeUpdateRequest request =
                jsonMapper.readValue(
                        "{\"name\": \"프레스 설비\", \"rowVersion\": 1}", ObjectTypeUpdateRequest.class);

        assertThat(request.hasDescription()).isFalse();
        assertThat(request.hasAccessGroup()).isFalse();
        assertThat(request.hasDisplayPropertyId()).isFalse();
        assertThat(request.getName()).isEqualTo("프레스 설비");
        assertThat(request.getRowVersion()).isEqualTo(1L);
    }

    @Test
    void explicitNullIsMarkedAsSent() {
        ObjectTypeUpdateRequest request =
                jsonMapper.readValue(
                        "{\"description\": null, \"accessGroup\": null, \"displayPropertyId\": null,"
                                + " \"rowVersion\": 1}",
                        ObjectTypeUpdateRequest.class);

        assertThat(request.hasDescription()).isTrue();
        assertThat(request.getDescription()).isNull();
        assertThat(request.hasAccessGroup()).isTrue();
        assertThat(request.hasDisplayPropertyId()).isTrue();
        assertThat(request.getDisplayPropertyId()).isNull();
    }

    @Test
    void sentFlagsCannotBeSetFromJson() {
        ObjectTypeUpdateRequest request =
                jsonMapper.readValue(
                        "{\"descriptionSent\": true, \"displayPropertyIdSent\": true,"
                                + " \"rowVersion\": 1}",
                        ObjectTypeUpdateRequest.class);

        assertThat(request.hasDescription()).isFalse();
        assertThat(request.hasDisplayPropertyId()).isFalse();
    }
}
