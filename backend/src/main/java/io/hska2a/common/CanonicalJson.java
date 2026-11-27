package io.hska2a.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;

/** HSP canonical JSON: object keys sorted recursively, arrays keep order, compact output. */
public final class CanonicalJson {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {}

    public static JsonNode sort(JsonNode node) {
        if (node == null) return null;
        if (node.isObject()) {
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            ObjectNode out = JsonNodeFactory.instance.objectNode();
            for (String n : names) out.set(n, sort(node.get(n)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode();
            node.forEach(child -> out.add(sort(child)));
            return out;
        }
        return node;
    }

    public static String write(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(sort(node));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String write(Object value) {
        return write(MAPPER.valueToTree(value));
    }

    public static String sha256Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
