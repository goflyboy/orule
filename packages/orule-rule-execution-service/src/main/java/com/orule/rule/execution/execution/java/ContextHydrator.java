package com.orule.rule.execution.execution.java;

import com.orule.rule.execution.api.dto.ExecutionInput;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType;
import com.orule.rule.execution.execution.java.metadata.ResolvedObjectType.ResolvedAttribute;
import groovy.lang.GroovyObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bind-time Map → Groovy POJO conversion and JSON-friendly output conversion
 * (RFC-0043 §4.1 / RFC-0045 §4.5).
 *
 * <p>v0.2 generalization:
 * <ul>
 *   <li>Top-level object slots are hydrated based on the per-slot
 *       {@link ResolvedObjectType} from {@code ExecutionInput.resolvedObjectTypes()},
 *       not by name ({@code customer} / {@code order} hard-coding).</li>
 *   <li>Attributes that reference an {@code ENUM} ObjectType are converted from
 *       raw String values to Groovy enum constants. There is no special-case
 *       for {@code tier} or any other field name.</li>
 *   <li>List / Map attributes and slots are NOT recursively hydrated; only the
 *       top-level slot matching an ObjectType spec is hydrated.</li>
 *   <li>{@code dehydrateValue} uses reflection on getters so it works for any
 *       Groovy class emitted by {@link DomainTypePrefixGenerator}.</li>
 * </ul>
 */
public final class ContextHydrator {

    private static final Logger log = LoggerFactory.getLogger(ContextHydrator.class);

    private ContextHydrator() {}

    /**
     * Hydrate known top-level slots in {@code input.context()} based on
     * {@code input.resolvedObjectTypes()}.
     */
    public static Map<String, Object> hydrate(ExecutionInput input, Class<?> scriptClass) {
        if (input == null || input.context() == null || input.context().isEmpty()) {
            return input.context();
        }
        if (input.resolvedObjectTypes() == null || input.resolvedObjectTypes().isEmpty()) {
            return input.context();
        }
        if (scriptClass == null) {
            return input.context();
        }

        Map<String, ResolvedObjectType> bySlot = new java.util.HashMap<>();
        for (ResolvedObjectType ot : input.resolvedObjectTypes()) {
            if (ot.slotName() != null) {
                bySlot.put(ot.slotName(), ot);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>(input.context());
        for (Map.Entry<String, Object> e : input.context().entrySet()) {
            ResolvedObjectType spec = bySlot.get(e.getKey());
            if (spec == null || !"CLASS".equals(spec.kind())) {
                continue;
            }
            Class<?> targetClass = findDomainClass(scriptClass, spec.programCode());
            if (targetClass == null) {
                log.warn("Slot {} references programCode={} but script prefix has no such class; skip hydrate",
                        e.getKey(), spec.programCode());
                continue;
            }
            Object value = e.getValue();
            if (value instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) m;
                out.put(e.getKey(), hydrateObject(targetClass, spec, scriptClass, map));
            }
            // list / primitive / null: leave as-is (h4a: list/map not hydrated recursively).
        }
        return out;
    }

    /**
     * Build a Groovy POJO instance of {@code targetClass} from {@code fields},
     * resolving any attribute whose type is an {@code ENUM} ObjectType.
     */
    static Object hydrateObject(Class<?> targetClass, ResolvedObjectType spec,
                                 Class<?> scriptClass, Map<String, Object> fields) {
        Object instance = newInstance(targetClass, scriptClass);
        if (!(instance instanceof GroovyObject go)) {
            return fields;
        }
        Map<String, Class<?>> enumByCode = new java.util.HashMap<>();
        if (spec.attributes() != null) {
            for (ResolvedAttribute a : spec.attributes()) {
                if ("object".equals(a.dataType())) {
                    Class<?> enumClass = findDomainClass(scriptClass, a.refCode());
                    if (enumClass != null && enumClass.isEnum()) {
                        enumByCode.put(a.programCode(), enumClass);
                    }
                }
            }
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object value = e.getValue();
            Class<?> enumClass = enumByCode.get(e.getKey());
            if (enumClass != null) {
                value = toEnum(enumClass, value);
            }
            try {
                go.setProperty(e.getKey(), value);
            } catch (RuntimeException ex) {
                log.debug("hydrateObject: skip property {} on {}: {}", e.getKey(), targetClass.getSimpleName(), ex.getMessage());
            }
        }
        return instance;
    }

    /** Convert Groovy domain objects / enums back to JSON-friendly maps and
     *  strings so Jackson does not serialize {@code metaClass}. */
    public static Map<String, Object> dehydrate(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return context;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : context.entrySet()) {
            out.put(e.getKey(), dehydrateValue(e.getValue()));
        }
        return out;
    }

    static Object dehydrateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof GroovyObject go) {
            return groovyObjectToMap(go);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                mapped.put(String.valueOf(e.getKey()), dehydrateValue(e.getValue()));
            }
            return mapped;
        }
        if (value instanceof List<?> list) {
            List<Object> mapped = new ArrayList<>(list.size());
            for (Object item : list) {
                mapped.add(dehydrateValue(item));
            }
            return mapped;
        }
        return value;
    }

    private static Map<String, Object> groovyObjectToMap(GroovyObject go) {
        Map<String, Object> mapped = new LinkedHashMap<>();
        for (Method m : go.getClass().getMethods()) {
            if (m.getParameterCount() != 0) {
                continue;
            }
            if (m.getDeclaringClass() == Object.class) {
                continue;
            }
            String name = m.getName();
            String prop = null;
            if (name.startsWith("get") && name.length() > 3
                    && Character.isUpperCase(name.charAt(3))) {
                prop = Character.toLowerCase(name.charAt(3)) + name.substring(4);
            } else if (name.startsWith("is") && name.length() > 2
                    && Character.isUpperCase(name.charAt(2))
                    && (m.getReturnType() == boolean.class || m.getReturnType() == Boolean.class)) {
                prop = Character.toLowerCase(name.charAt(2)) + name.substring(3);
            }
            if (prop == null || "class".equals(prop) || "metaClass".equals(prop)) {
                continue;
            }
            try {
                Object raw = m.invoke(go);
                mapped.put(prop, dehydrateValue(raw));
            } catch (ReflectiveOperationException ignored) {
                // Skip properties that cannot be read on this instance.
            }
        }
        return mapped;
    }

    static Class<?> findDomainClass(Class<?> scriptClass, String simpleName) {
        if (scriptClass == null || simpleName == null) {
            return null;
        }
        for (Class<?> nested : scriptClass.getDeclaredClasses()) {
            if (simpleName.equals(nested.getSimpleName())) {
                return nested;
            }
        }
        ClassLoader loader = scriptClass.getClassLoader();
        if (loader == null) {
            return null;
        }
        try {
            return Class.forName(scriptClass.getName() + "$" + simpleName, false, loader);
        } catch (ClassNotFoundException ignored) {
            try {
                return Class.forName(simpleName, false, loader);
            } catch (ClassNotFoundException missing) {
                return null;
            }
        }
    }

    private static Object newInstance(Class<?> type, Class<?> scriptClass) {
        try {
            Constructor<?> noArg = type.getDeclaredConstructor();
            noArg.setAccessible(true);
            return noArg.newInstance();
        } catch (ReflectiveOperationException ignored) {
            try {
                Constructor<?> enclosing = type.getDeclaredConstructor(scriptClass);
                enclosing.setAccessible(true);
                return enclosing.newInstance(scriptClass.getDeclaredConstructor().newInstance());
            } catch (ReflectiveOperationException ex) {
                return null;
            }
        }
    }

    private static Object toEnum(Class<?> enumClass, Object value) {
        if (value == null || enumClass.isInstance(value)) {
            return value;
        }
        String name = String.valueOf(value);
        for (Object constant : enumClass.getEnumConstants()) {
            if (name.equals(((Enum<?>) constant).name())) {
                return constant;
            }
        }
        return value;
    }
}
