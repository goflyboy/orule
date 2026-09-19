package com.orule.rule.execution.execution.java;

import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType.ResolvedAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders ObjectType metadata into a Groovy script prefix (RFC-0045 §4.2).
 *
 * <p>Output order:
 * <ol>
 *   <li>{@code kind=ENUM} declarations, sorted by {@code programCode}.</li>
 *   <li>{@code kind=CLASS} declarations in <b>topological order</b>:
 *       attributes referencing another ObjectType are emitted after the
 *       referenced type. Cycles fall back to {@code programCode} lexicographic
 *       order with a {@code log.warn}.</li>
 * </ol>
 *
 * <p>Returns empty string when the input list is null or empty; this matches
 * the RFC-0043 compatibility path where no metadata means "verbatim source".
 */
public final class DomainTypePrefixGenerator {

    private static final Logger log = LoggerFactory.getLogger(DomainTypePrefixGenerator.class);

    private DomainTypePrefixGenerator() {}

    public static String render(List<ResolvedObjectType> objectTypes) {
        if (objectTypes == null || objectTypes.isEmpty()) {
            return "";
        }
        String enums = objectTypes.stream()
                .filter(ResolvedObjectType::isEnum)
                .sorted(Comparator.comparing(ResolvedObjectType::programCode))
                .map(DomainTypePrefixGenerator::renderEnum)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));

        List<ResolvedObjectType> classes = objectTypes.stream()
                .filter(ResolvedObjectType::isClass)
                .collect(Collectors.toCollection(ArrayList::new));
        topologicalSort(classes);

        String bodies = classes.stream()
                .map(DomainTypePrefixGenerator::renderClass)
                .filter(s -> !s.isBlank())
                .collect(Collectors.joining("\n\n"));

        StringBuilder sb = new StringBuilder();
        if (!enums.isEmpty()) {
            sb.append(enums).append("\n\n");
        }
        if (!bodies.isEmpty()) {
            sb.append(bodies).append("\n");
        }
        return sb.toString();
    }

    /** Render an ENUM ObjectType as {@code enum X { A, B, C }}. */
    static String renderEnum(ResolvedObjectType ot) {
        if (ot == null || ot.enumValues() == null || ot.enumValues().isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("enum ");
        sb.append(ot.programCode()).append(" { ");
        List<String> names = ot.enumValues().stream()
                .map(ResolvedObjectType.EnumValue::code)
                .collect(Collectors.toList());
        // Sort enum constants by sortOrder ascending, then by code; null sortOrder → end.
        List<ResolvedObjectType.EnumValue> sorted = new ArrayList<>(ot.enumValues());
        sorted.sort(Comparator.comparing(ResolvedObjectType.EnumValue::sortOrder,
                Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ResolvedObjectType.EnumValue::code));
        sb.append(sorted.stream()
                .map(ResolvedObjectType.EnumValue::code)
                .collect(Collectors.joining(", ")));
        sb.append(" }");
        return sb.toString();
    }

    /** Render a CLASS ObjectType as {@code class X { ... }}.
     * Attribute field types are derived from {@link ResolvedAttribute#dataType()}
     * and {@link ResolvedAttribute#refCode()}.
     */
    static String renderClass(ResolvedObjectType ot) {
        if (ot == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder("class ");
        sb.append(ot.programCode()).append(" {\n");
        if (ot.attributes() != null) {
            for (ResolvedAttribute a : ot.attributes()) {
                sb.append("    ").append(groovyFieldType(a)).append(" ").append(a.programCode()).append("\n");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Map a metadata attribute to a Groovy field type.
     *
     * <ul>
     *   <li>{@code primitive} → Java boxed name from {@code refCode} (string|number|boolean|date).</li>
     *   <li>{@code object}    → raw {@code refCode} (programCode; will resolve to a sibling
     *       declared class/enum in the same prefix).</li>
     *   <li>{@code list}      → {@code List<refCode>} (capitalized).</li>
     *   <li>{@code map}       → {@code Map<keyType, refCode2>} (capitalized).</li>
     * </ul>
     */
    static String groovyFieldType(ResolvedAttribute a) {
        if (a == null || a.dataType() == null) {
            return "Object";
        }
        switch (a.dataType()) {
            case "primitive":
                return primitiveTypeName(a.refCode());
            case "object":
                return a.refCode() == null ? "Object" : a.refCode();
            case "list":
                String el = a.refCode() == null ? "Object" : capitalize(a.refCode());
                return "List<" + el + ">";
            case "map":
                String key = primitiveTypeName(a.refCode());
                String val = a.refCode2() == null ? "Object" : (isPrimitive(a.refCode2()) ? primitiveTypeName(a.refCode2()) : capitalize(a.refCode2()));
                return "Map<" + key + ", " + val + ">";
            default:
                return "Object";
        }
    }

    private static String primitiveTypeName(String code) {
        if (code == null) {
            return "Object";
        }
        switch (code) {
            case "string":  return "String";
            case "number":  return "Integer";
            case "boolean": return "Boolean";
            case "date":    return "Date";
            default:        return "Object";
        }
    }

    private static boolean isPrimitive(String code) {
        return "string".equals(code) || "number".equals(code)
                || "boolean".equals(code) || "date".equals(code);
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /**
     * Stable sort by topological dependency: emit each class after the
     * ObjectTypes it references via {@code object} attributes. Cycles fall
     * back to lexicographic order and a {@code log.warn}.
     */
    static void topologicalSort(List<ResolvedObjectType> classes) {
        if (classes == null || classes.size() < 2) {
            return;
        }
        Map<String, ResolvedObjectType> byCode = new HashMap<>();
        for (ResolvedObjectType c : classes) {
            byCode.put(c.programCode(), c);
        }
        List<ResolvedObjectType> ordered = new ArrayList<>(classes.size());
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new HashSet<>();
        for (ResolvedObjectType c : classes) {
            if (!visited.contains(c.programCode())) {
                visit(c, byCode, visited, onStack, ordered);
            }
        }
        // Stable tie-break by programCode; visited-already nodes re-sort.
        ordered.sort(Comparator.comparing(ResolvedObjectType::programCode));
        // Apply topological order while preserving declared order for ties.
        Set<String> seen = new LinkedHashSet<>();
        List<ResolvedObjectType> out = new ArrayList<>(classes.size());
        for (String code : ordered.stream().map(ResolvedObjectType::programCode).toList()) {
            if (seen.add(code)) {
                ResolvedObjectType match = byCode.get(code);
                if (match != null) {
                    out.add(match);
                }
            }
        }
        // Append any leftovers (shouldn't happen, defensive).
        for (ResolvedObjectType c : classes) {
            if (!seen.contains(c.programCode())) {
                out.add(c);
            }
        }
        classes.clear();
        classes.addAll(out);
    }

    private static void visit(ResolvedObjectType node,
                              Map<String, ResolvedObjectType> byCode,
                              Set<String> visited,
                              Set<String> onStack,
                              List<ResolvedObjectType> ordered) {
        if (node == null) {
            return;
        }
        if (onStack.contains(node.programCode())) {
            log.warn("DomainTypePrefixGenerator: detected cycle involving programCode={}; falling back to lexicographic order",
                    node.programCode());
            return;
        }
        if (visited.contains(node.programCode())) {
            return;
        }
        onStack.add(node.programCode());
        if (node.attributes() != null) {
            for (ResolvedAttribute a : node.attributes()) {
                if ("object".equals(a.dataType())) {
                    ResolvedObjectType dep = byCode.get(a.refCode());
                    if (dep != null) {
                        visit(dep, byCode, visited, onStack, ordered);
                    }
                }
            }
        }
        onStack.remove(node.programCode());
        visited.add(node.programCode());
        ordered.add(node);
    }
}
