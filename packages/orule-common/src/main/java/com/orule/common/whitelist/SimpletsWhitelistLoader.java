package com.orule.common.whitelist;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;

/**
 * SimpleTS 白名单加载器（RFC-0018 §3.10 + ADR-012-Aprime §4.3）。
 *
 * <p>从 classpath 资源 {@code simplets-whitelist.json} 反序列化为
 * {@link SimpletsWhitelist}，供 orule-runtime Groovy 沙箱启动期使用。
 *
 * <h2>加载语义</h2>
 * <ul>
 *   <li>资源缺失 → 抛 {@link IllegalStateException}（fail-fast，不允许 silent 失败）</li>
 *   <li>JSON 格式错误 → 抛 {@link IllegalStateException}（同上）</li>
 *   <li>必需字段缺失 → 抛 {@link IllegalStateException}</li>
 *   <li>version 为空 → 抛 {@link IllegalStateException}</li>
 * </ul>
 *
 * <h2>单例 vs 多次实例</h2>
 * <p>{@link #fromClasspath()} 每次调用都重新解析（适合 CLI / 一次性场景）。
 * 长期持有请用 {@link #fromClasspath(String)} 一次解析后缓存。
 *
 * <h2>多资源优先级</h2>
 * <p>默认从 classpath 根加载。可通过 {@link #fromClasspath(String)} 指定
 * 其它 classpath 路径（如 {@code classpath*:simplets-whitelist.json} 用于多 jar）。
 */
@Slf4j
public final class SimpletsWhitelistLoader {

    /** 默认资源路径（与 orule-llm-studio sync-whitelist.mjs 写入路径对齐）。 */
    public static final String DEFAULT_RESOURCE_PATH = "simplets-whitelist.json";

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        // ISO-8601 字面量（含 Z 后缀）→ Instant
        .configure(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);

    private SimpletsWhitelistLoader() {}

    /**
     * 从默认 classpath 资源 {@value #DEFAULT_RESOURCE_PATH} 加载。
     */
    public static SimpletsWhitelist fromClasspath() {
        return fromClasspath(DEFAULT_RESOURCE_PATH);
    }

    /**
     * 从指定 classpath 资源路径加载。
     *
     * @param resourcePath classpath 路径（如 {@code "simplets-whitelist.json"} 或
     *                     {@code "config/some-whitelist.json"}）
     * @throws IllegalStateException 资源缺失 / JSON 错误 / 必需字段缺失
     */
    public static SimpletsWhitelist fromClasspath(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        log.debug("Loading SimpletsWhitelist from classpath: {}", resourcePath);

        try (InputStream in = openResource(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException(
                    "SimpleTS 白名单资源未找到: " + resourcePath +
                    "（源端 orule-llm-studio 应通过 sync-whitelist.mjs 同步此资源）");
            }
            SimpletsWhitelist wl = MAPPER.readValue(in, SimpletsWhitelist.class);
            validate(wl, resourcePath);
            log.info("Loaded SimpletsWhitelist version={} statementKinds={} globalFunctions={} methodSignatures={}",
                wl.version(), wl.statementKinds().size(), wl.globalFunctions().size(),
                wl.methodSignatures().size());
            return wl;
        } catch (IOException e) {
            throw new IllegalStateException(
                "SimpleTS 白名单 JSON 解析失败: " + resourcePath, e);
        }
    }

    /**
     * 必需字段完整性校验。
     */
    private static void validate(SimpletsWhitelist wl, String resourcePath) {
        if (wl.version() == null || wl.version().isBlank()) {
            throw new IllegalStateException(
                "SimpleTS 白名单 version 字段缺失或为空: " + resourcePath);
        }
        requireNonEmpty(wl.statementKinds(), "statementKinds", resourcePath);
        requireNonEmpty(wl.expressionKinds(), "expressionKinds", resourcePath);
        requireNonEmpty(wl.binaryOps(), "binaryOps", resourcePath);
        requireNonEmpty(wl.unaryOps(), "unaryOps", resourcePath);
        requireNonEmpty(wl.globalFunctions(), "globalFunctions", resourcePath);
        requireNonEmpty(wl.methodSignatures(), "methodSignatures", resourcePath);

        // methodSignatures 的 methodName 必须 ⊆ globalFunctions（防漂移）
        for (var sig : wl.methodSignatures()) {
            if (!wl.globalFunctions().contains(sig.methodName())) {
                throw new IllegalStateException(
                    "methodSignatures.methodName 不在 globalFunctions 中: " +
                    sig.methodName() + "（" + resourcePath + "）");
            }
        }
    }

    private static void requireNonEmpty(java.util.List<?> list, String name, String resourcePath) {
        if (list == null || list.isEmpty()) {
            throw new IllegalStateException(
                "SimpleTS 白名单 " + name + " 字段缺失或为空: " + resourcePath);
        }
    }

    /**
     * 打开 classpath 资源（兼容单 classloader 与多 classloader）。
     *
     * <p>优先用当前线程 classloader，fallback 到 class loader。
     */
    private static InputStream openResource(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SimpletsWhitelistLoader.class.getClassLoader();
        InputStream in = cl.getResourceAsStream(resourcePath);
        if (in != null) return in;
        // fallback：去掉前导斜杠（getResourceAsStream 不接受）
        if (resourcePath.startsWith("/")) {
            return SimpletsWhitelistLoader.class.getResourceAsStream(resourcePath.substring(1));
        }
        // 再 fallback：class.getResourceAsStream 相对路径
        return SimpletsWhitelistLoader.class.getResourceAsStream("/" + resourcePath);
    }
}
