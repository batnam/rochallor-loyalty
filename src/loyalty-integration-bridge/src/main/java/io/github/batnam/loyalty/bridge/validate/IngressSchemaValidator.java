package io.github.batnam.loyalty.bridge.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Validates an inbound ingress message against its JSON Schema (: JSON Schema on MSK, no
 * registry — the schema is bundled and validated in-process). Loaded once at startup.
 */
@Component
public class IngressSchemaValidator {

    private final JsonSchema cardSpendSchema;
    private final JsonSchema paymentSchema;
    private final JsonSchema reversalSchema;
    private final JsonSchema lifecycleSchema;
    private final JsonSchema termDepositSchema;

    public IngressSchemaValidator() throws IOException {
        this.cardSpendSchema = load("schema/loyalty.ingress.card_spend.v1.json");
        this.termDepositSchema = load("schema/loyalty.ingress.term_deposit.v1.json");
        this.paymentSchema = load("schema/loyalty.ingress.payment.v1.json");
        this.reversalSchema = load("schema/loyalty.ingress.reversal.v1.json");
        this.lifecycleSchema = load("schema/loyalty.ingress.customer_lifecycle.v1.json");
    }

    private static JsonSchema load(String classpath) throws IOException {
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            return factory.getSchema(in);
        }
    }

    /** @return empty set when valid; otherwise the validation failures. */
    public Set<ValidationMessage> validateCardSpend(JsonNode node) {
        return cardSpendSchema.validate(node);
    }

    public Set<ValidationMessage> validatePayment(JsonNode node) {
        return paymentSchema.validate(node);
    }

    public Set<ValidationMessage> validateReversal(JsonNode node) {
        return reversalSchema.validate(node);
    }

    public Set<ValidationMessage> validateLifecycle(JsonNode node) {
        return lifecycleSchema.validate(node);
    }

    public Set<ValidationMessage> validateTermDeposit(JsonNode node) {
        return termDepositSchema.validate(node);
    }
}
