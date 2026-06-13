package io.github.batnam.loyalty.earning.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Validates an Earning Rule's {@code dsl_json} against the constrained grammar
 * {@code earning-rule.schema.json} (bundled on the classpath) at rule-save — the Anti-Corruption gate
 * that keeps non-conforming DSL out of {@code earning_rule} (loyalty-earning.yaml {@code createRule}).
 * Returns the list of validation messages; empty means valid.
 */
@Component
public class DslValidator {

    private static final String SCHEMA_PATH = "/dsl/earning-rule.schema.json";

    private final JsonSchema schema;

    public DslValidator() {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = DslValidator.class.getResourceAsStream(SCHEMA_PATH)) {
            if (in == null) {
                throw new IllegalStateException("DSL schema not found on classpath: " + SCHEMA_PATH);
            }
            this.schema = factory.getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("failed to load DSL schema " + SCHEMA_PATH, e);
        }
    }

    /** @return validation error messages; empty list iff {@code dslJson} conforms to the grammar. */
    public List<String> validate(JsonNode dslJson) {
        Set<ValidationMessage> messages = schema.validate(dslJson);
        return messages.stream().map(ValidationMessage::getMessage).toList();
    }
}
