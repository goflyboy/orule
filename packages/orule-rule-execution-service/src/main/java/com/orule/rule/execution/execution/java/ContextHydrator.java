package com.orule.rule.execution.execution.java;

import groovy.lang.GroovyObject;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bind-time Map ? Groovy POJO conversion for the Customer/Order demo domain
 * (RFC-0043 ?4.1 / T-5), plus JSON-friendly output conversion.
 *
 * <p>Only the well-known top-level slots {@code customer} and {@code order}
 * are hydrated so {@code customer.tier == CustomerTier.VIP} holds without a
 * typed local (scenario A1). List/Map values stay as JSON maps; typed locals
 * such as {@code Customer vip = customersById["alice"]} still copy out of the
 * map and must be assigned back (RFC-0043 ?4.3).
 *
 * <p>This is not a generic ObjectInst / BeanMapper framework.
 */
public final class ContextHydrator {

    private static final List<String> CUSTOMER_FIELDS = List.of("name", "tier", "tagged");
    private static final List<String> ORDER_FIELDS = List.of("totalAmount", "discount");

    private ContextHydrator() {}

    public static Map<String, Object> hydrate(Map<String, Object> context, Class<?> scriptClass) {
        if (context == null || context.isEmpty() || scriptClass == null) {
            return context;
        }
        Class<?> customerClass = findDomainClass(scriptClass, "Customer");
        Class<?> orderClass = findDomainClass(scriptClass, "Order");
        Class<?> tierClass = findDomainClass(scriptClass, "CustomerTier");
        if (customerClass == null && orderClass == null) {
            return context;
        }

        Map<String, Object> out = new LinkedHashMap<>(context);
        Object customer = context.get("customer");
        if (customer instanceof Map<?, ?> customerMap && customerClass != null) {
            out.put("customer", toGroovyObject(customerClass, scriptClass, stringMap(customerMap), tierClass));
        }
        Object order = context.get("order");
        if (order instanceof Map<?, ?> orderMap && orderClass != null) {
            out.put("order", toGroovyObject(orderClass, scriptClass, stringMap(orderMap), tierClass));
        }
        return out;
    }

    /**
     * Convert Groovy domain objects / enums back to JSON-friendly maps and
     * strings so Jackson does not serialize {@code metaClass}.
     */
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

    static Class<?> findDomainClass(Class<?> scriptClass, String simpleName) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Map<?, ?> map) {
        return (Map<String, Object>) map;
    }

    private static Object toGroovyObject(Class<?> type, Class<?> scriptClass,
                                         Map<String, Object> fields, Class<?> tierClass) {
        Object instance = newInstance(type, scriptClass);
        if (!(instance instanceof GroovyObject go)) {
            return fields;
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            Object value = e.getValue();
            if ("tier".equals(e.getKey()) && tierClass != null && tierClass.isEnum()) {
                value = toEnum(tierClass, value);
            }
            go.setProperty(e.getKey(), value);
        }
        return instance;
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

    private static Object dehydrateValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Enum<?> e) {
            return e.name();
        }
        if (value instanceof GroovyObject go) {
            List<String> fields = domainFields(go.getClass().getSimpleName());
            if (fields != null) {
                Map<String, Object> mapped = new LinkedHashMap<>();
                for (String field : fields) {
                    mapped.put(field, dehydrateValue(go.getProperty(field)));
                }
                return mapped;
            }
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

    private static List<String> domainFields(String simpleName) {
        if ("Customer".equals(simpleName)) {
            return CUSTOMER_FIELDS;
        }
        if ("Order".equals(simpleName)) {
            return ORDER_FIELDS;
        }
        return null;
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
