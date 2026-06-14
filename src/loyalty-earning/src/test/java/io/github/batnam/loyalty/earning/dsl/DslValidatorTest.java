package io.github.batnam.loyalty.earning.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DslValidator} — the schema gate the Earn Source Registry runs at rule-save
 * (loyalty-earning.yaml {@code createRule}: "dslJson is schema-validated … at save time"). Valid DSL
 * passes; structurally invalid DSL is rejected with messages, before any rule is persisted.
 */
class DslValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final DslValidator validator = new DslValidator();

    private List<String> validate(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            return validator.validate(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void acceptsAWellFormedRule() {
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND","hitPolicy":"FIRST",
             "rows":[{"when":{"amount":{"gte":1}},"earn":{"type":"RATE","perAmount":10000,"points":1}}],
             "caps":{"perEventMax":500,"perMemberPerDay":1000}}""";
        assertThat(validate(dsl)).isEmpty();
    }

    @Test
    void rejectsMissingRequiredRows() {
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND"}""";
        assertThat(validate(dsl)).isNotEmpty();
    }

    @Test
    void rejectsUnknownTopLevelProperty() {
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND","bogusField":true,
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":1}}]}""";
        assertThat(validate(dsl)).isNotEmpty();
    }

    @Test
    void rejectsUnknownConditionOperator() {
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":{"approx":5}},"earn":{"type":"FIXED","points":1}}]}""";
        assertThat(validate(dsl)).isNotEmpty();
    }

    @Test
    void rejectsRateEarnMissingPerAmount() {
        // schema allOf: type=RATE requires perAmount + points.
        String dsl = """
            {"dslVersion":1,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"RATE","points":1}}]}""";
        assertThat(validate(dsl)).isNotEmpty();
    }

    @Test
    void rejectsWrongDslVersion() {
        String dsl = """
            {"dslVersion":2,"earnSource":"CARD_SPEND",
             "rows":[{"when":{"amount":"*"},"earn":{"type":"FIXED","points":1}}]}""";
        assertThat(validate(dsl)).isNotEmpty();
    }
}
