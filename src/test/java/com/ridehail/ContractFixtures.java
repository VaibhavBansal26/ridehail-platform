package com.ridehail;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * JSON-shape contract with the admin web panel. The panel's tests validate the same files with its zod schemas, so a
 * change on either side fails a test on that side. Regenerate with {@code mvn test -Dfixtures.write=true} and review the diff.
 */
final class ContractFixtures {
    private ContractFixtures() {}

    static void check(ObjectMapper json, String fixture, JsonNode actual) throws IOException {
        Path file = Path.of("admin-web/fixtures", fixture);
        if (Boolean.getBoolean("fixtures.write")) {
            Files.createDirectories(file.getParent());
            json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), actual);
        }
        JsonNode expected = json.readTree(file.toFile());
        assertThat(sameShape(actual, expected)).as(fixture + "\nactual:   " + shape(actual) + "\nexpected: " + shape(expected)).isTrue();
    }

    /** Same fields and types. An empty array or a null stands for "no example here" and matches anything. */
    static boolean sameShape(JsonNode a, JsonNode b) {
        if (a.isNull() || b.isNull()) return true;
        if (a.isArray() && b.isArray()) return a.isEmpty() || b.isEmpty() || sameShape(a.get(0), b.get(0));
        if (a.isObject() && b.isObject()) {
            if (!Set.copyOf(names(a)).equals(Set.copyOf(names(b)))) return false;
            for (String f : names(a)) if (!sameShape(a.get(f), b.get(f))) return false;
            return true;
        }
        return a.getNodeType() == b.getNodeType();
    }

    private static List<String> names(JsonNode n) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = n.fieldNames();
        it.forEachRemaining(out::add);
        return out;
    }

    /** Field names and JSON types, ignoring values. Arrays are described by their first element. */
    static String shape(JsonNode n) {
        if (n.isObject()) {
            var sb = new StringBuilder("{");
            for (String f : new TreeSet<>(names(n))) sb.append(f).append(':').append(shape(n.get(f))).append(',');
            return sb.append('}').toString();
        }
        if (n.isArray()) return n.isEmpty() ? "[]" : "[" + shape(n.get(0)) + "]";
        if (n.isNull()) return "null";
        return n.isNumber() ? "number" : n.isBoolean() ? "boolean" : "string";
    }
}
