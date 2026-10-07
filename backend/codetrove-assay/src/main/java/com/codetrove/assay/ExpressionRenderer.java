package com.codetrove.assay;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import org.springframework.stereotype.Component;

@Component
class ExpressionRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^{}]+)}");
    private static final Pattern CONTEXT = Pattern.compile(
        "context\\.([A-Za-z][A-Za-z0-9_]*)\\.([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*)"
    );
    private static final Pattern RANDOM_LONG = Pattern.compile("#randomLong\\((-?\\d+),(-?\\d+)\\)");
    private final ObjectMapper objectMapper;

    ExpressionRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    static boolean isSupported(String value) {
        Matcher matcher = TOKEN.matcher(value);
        int end = 0;
        while (matcher.find()) {
            if (!supportedToken(matcher.group(1))) {
                return false;
            }
            end = matcher.end();
        }
        return !value.contains("${") || end > 0 && !value.substring(end).contains("${");
    }

    JsonNode render(JsonNode input, Map<String, JsonNode> context) {
        if (input == null || input.isNull()) {
            return input;
        }
        if (input.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            input.properties().forEach(entry -> result.set(entry.getKey(), render(entry.getValue(), context)));
            return result;
        }
        if (input.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            input.forEach(item -> result.add(render(item, context)));
            return result;
        }
        if (!input.isTextual()) {
            return input.deepCopy();
        }
        return renderText(input.asText(), context);
    }

    String renderString(String input, Map<String, JsonNode> context) {
        JsonNode result = renderText(input, context);
        if (!result.isValueNode()) {
            throw new AssayDefinitionException("EXPRESSION_ERROR", "Complex expression cannot become text");
        }
        return result.asText();
    }

    private JsonNode renderText(String value, Map<String, JsonNode> context) {
        Matcher matcher = TOKEN.matcher(value);
        if (matcher.matches()) {
            return resolve(matcher.group(1), context);
        }
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            JsonNode resolved = resolve(matcher.group(1), context);
            if (!resolved.isValueNode()) {
                throw new AssayDefinitionException(
                    "EXPRESSION_ERROR",
                    "Complex value cannot be interpolated into text"
                );
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(resolved.asText()));
        }
        matcher.appendTail(buffer);
        return TextNode.valueOf(buffer.toString());
    }

    private JsonNode resolve(String token, Map<String, JsonNode> context) {
        if ("#uuid()".equals(token)) {
            return TextNode.valueOf(UUID.randomUUID().toString());
        }
        Matcher random = RANDOM_LONG.matcher(token);
        if (random.matches()) {
            long minimum = Long.parseLong(random.group(1));
            long maximum = Long.parseLong(random.group(2));
            if (minimum > maximum) {
                throw new AssayDefinitionException(
                    "EXPRESSION_ERROR",
                    "randomLong minimum exceeds maximum"
                );
            }
            try {
                long value = minimum == maximum
                    ? minimum
                    : ThreadLocalRandom.current().nextLong(minimum, Math.addExact(maximum, 1));
                return JsonNodeFactory.instance.numberNode(value);
            } catch (ArithmeticException exception) {
                throw new AssayDefinitionException(
                    "EXPRESSION_ERROR",
                    "randomLong range is unsupported"
                );
            }
        }
        Matcher reference = CONTEXT.matcher(token);
        if (reference.matches()) {
            JsonNode value = context.get(reference.group(1));
            if (value == null) {
                throw new AssayDefinitionException(
                    "EXPRESSION_ERROR",
                    "Context value does not exist: " + reference.group(1)
                );
            }
            for (String segment : reference.group(2).split("\\.")) {
                value = value.path(segment);
                if (value.isMissingNode()) {
                    throw new AssayDefinitionException(
                        "EXPRESSION_ERROR",
                        "Context path does not exist: " + token
                    );
                }
            }
            return value.deepCopy();
        }
        throw new AssayDefinitionException("EXPRESSION_ERROR", "Unsupported expression token");
    }

    private static boolean supportedToken(String token) {
        return "#uuid()".equals(token) || RANDOM_LONG.matcher(token).matches()
            || CONTEXT.matcher(token).matches();
    }
}
