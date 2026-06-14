package io.github.batnam.loyalty.earning.dsl;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Parses a {@code dsl_json} {@link JsonNode} into a typed {@link RuleDsl}. Assumes the JSON has
 * already passed {@code DslValidator} (schema-valid); applies the schema's documented defaults
 * (hitPolicy FIRST, rounding FLOOR, tierMultiplier false, balances both). Pure — no I/O.
 */
public final class DslParser {

    private DslParser() {
    }

    public static RuleDsl parse(JsonNode dsl) {
        int dslVersion = dsl.path("dslVersion").asInt(1);
        String earnSource = dsl.path("earnSource").asText();
        RuleDsl.HitPolicy hitPolicy = RuleDsl.HitPolicy.valueOf(dsl.path("hitPolicy").asText("FIRST"));
        boolean tierMultiplier = dsl.path("tierMultiplier").asBoolean(false);
        RuleDsl.Rounding rounding = RuleDsl.Rounding.valueOf(dsl.path("rounding").asText("FLOOR"));

        List<RuleDsl.Row> rows = new ArrayList<>();
        for (JsonNode rowNode : dsl.path("rows")) {
            rows.add(parseRow(rowNode));
        }
        return new RuleDsl(dslVersion, earnSource, hitPolicy, tierMultiplier, rounding, rows,
                parseCaps(dsl.path("caps")));
    }

    private static RuleDsl.Row parseRow(JsonNode rowNode) {
        Map<String, Condition> when = new LinkedHashMap<>();
        JsonNode whenNode = rowNode.path("when");
        for (Iterator<String> it = whenNode.fieldNames(); it.hasNext(); ) {
            String field = it.next();
            when.put(field, parseCondition(whenNode.get(field)));
        }
        return new RuleDsl.Row(when, parseEarn(rowNode.path("earn")));
    }

    private static Condition parseCondition(JsonNode node) {
        if (node.isObject()) {
            String op = node.fieldNames().next();   // schema guarantees exactly one operator key
            JsonNode v = node.get(op);
            return switch (op) {
                case "eq" -> new Condition.Eq(value(v));
                case "ne" -> new Condition.Ne(value(v));
                case "in" -> new Condition.In(valueList(v));
                case "nin" -> new Condition.Nin(valueList(v));
                case "gt" -> new Condition.Gt(v.asDouble());
                case "gte" -> new Condition.Gte(v.asDouble());
                case "lt" -> new Condition.Lt(v.asDouble());
                case "lte" -> new Condition.Lte(v.asDouble());
                case "between" -> new Condition.Between(v.get(0).asDouble(), v.get(1).asDouble());
                default -> throw new IllegalArgumentException("unknown DSL operator: " + op);
            };
        }
        // Scalar shorthand: "*" wildcard, else equality.
        if (node.isTextual() && "*".equals(node.asText())) {
            return new Condition.Wildcard();
        }
        return new Condition.Eq(value(node));
    }

    private static RuleDsl.Earn parseEarn(JsonNode earnNode) {
        RuleDsl.EarnType type = RuleDsl.EarnType.valueOf(earnNode.path("type").asText());
        Double perAmount = earnNode.has("perAmount") ? earnNode.get("perAmount").asDouble() : null;
        double points = earnNode.path("points").asDouble();
        Set<RuleDsl.Balance> balances = EnumSet.noneOf(RuleDsl.Balance.class);
        if (earnNode.has("balances")) {
            for (JsonNode b : earnNode.get("balances")) {
                balances.add(RuleDsl.Balance.valueOf(b.asText()));
            }
        } else {
            balances = EnumSet.allOf(RuleDsl.Balance.class);   // schema default: both
        }
        return new RuleDsl.Earn(type, perAmount, points, balances);
    }

    private static RuleDsl.Caps parseCaps(JsonNode caps) {
        if (caps.isMissingNode() || caps.isNull()) {
            return RuleDsl.Caps.NONE;
        }
        return new RuleDsl.Caps(
                intOrNull(caps, "perEventMax"),
                intOrNull(caps, "perMemberPerDay"),
                intOrNull(caps, "perMemberPerMonth"),
                intOrNull(caps, "perMemberPerRule"));
    }

    private static Integer intOrNull(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asInt();
    }

    private static Object value(JsonNode node) {
        if (node.isTextual()) return node.asText();
        if (node.isBoolean()) return node.asBoolean();
        if (node.isNumber()) return node.asDouble();
        return node.asText();
    }

    private static List<Object> valueList(JsonNode array) {
        List<Object> out = new ArrayList<>();
        for (JsonNode n : array) {
            out.add(value(n));
        }
        return out;
    }
}
