package com.axcore.platform.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axcore.platform.check.domain.TransformOperation;
import com.axcore.platform.check.domain.TransformStep;
import com.axcore.platform.check.service.ValueTransformer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * AI(Python)와 함께 쓰는 변환 예시 파일을 백엔드 변환기로 확인한다. 기준은 백엔드이므로 여기서 실패하면 예시나 백엔드 중 하나가 틀린 것이다. AI
 * 검사(Jenkins)는 ai/ 폴더만 마운트하므로 파일은 ai/ 아래에 둔다.
 */
class TransformCasesTests {

    private static final Path CASES =
            Path.of("..", "ai", "tests", "fixtures", "transform_cases.json");

    private final ValueTransformer transformer = new ValueTransformer();

    @Test
    void matchesSharedCases() throws Exception {
        // "_comment" 같은 설명 필드는 건너뛴다.
        JsonMapper mapper =
                JsonMapper.builder()
                        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .build();
        CaseFile file = mapper.readValue(Files.readString(CASES), CaseFile.class);

        assertThat(file.cases()).isNotEmpty();
        for (Case c : file.cases()) {
            List<TransformStep> steps =
                    c.steps().stream()
                            .map(
                                    step ->
                                            new TransformStep(
                                                    TransformOperation.valueOf(
                                                            (String) step.get("op")),
                                                    step.get("arg")))
                            .toList();

            if (Boolean.TRUE.equals(c.error())) {
                assertThatThrownBy(() -> transformer.transform(c.input(), steps))
                        .as(c.name())
                        .isInstanceOf(ValueTransformer.TransformException.class);
            } else {
                assertThat(transformer.transform(c.input(), steps))
                        .as(c.name())
                        .isEqualTo(c.output());
            }
        }
    }

    private record CaseFile(List<Case> cases) {}

    private record Case(
            String name,
            List<Map<String, Object>> steps,
            String input,
            String output,
            Boolean error) {}
}
