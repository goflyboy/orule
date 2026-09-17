package com.orule.common.whitelist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SimpletsWhitelistLoader 集成测试（RFC-0018 §3.10 + ADR-012-Aprime §4.3）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>默认资源加载成功（classpath:simplets-whitelist.json）</li>
 *   <li>字段完整性：所有 list 字段非空</li>
 *   <li>version 字段有效（semver）</li>
 *   <li>methodSignatures.methodName ⊆ globalFunctions（防漂移）</li>
 *   <li>updatedAt 是 ISO-8601 时间</li>
 *   <li>具体值断言（与 A 端 TS exportToJSON 对齐）</li>
 *   <li>资源缺失 → IllegalStateException（fail-fast）</li>
 *   <li>null 路径 → NullPointerException</li>
 *   <li>version 为空 → IllegalStateException</li>
 *   <li>statementKinds 为空 → IllegalStateException</li>
 *   <li>methodSignature.methodName 不在 globalFunctions → IllegalStateException（防漂移）</li>
 * </ol>
 */
class SimpletsWhitelistLoaderTest {

    /** 测试用的临时文件名计数器，避免并发 / 顺序问题。 */
    private static final java.util.concurrent.atomic.AtomicInteger TEMP_COUNTER =
        new java.util.concurrent.atomic.AtomicInteger(0);

    @Test
    @DisplayName("默认资源加载成功")
    void load_default() {
        SimpletsWhitelist wl = SimpletsWhitelistLoader.fromClasspath();
        assertNotNull(wl);
        assertEquals("1.0.0", wl.version());
    }

    @Test
    @DisplayName("字段完整性：所有 list 字段非空")
    void load_fieldIntegrity() {
        SimpletsWhitelist wl = SimpletsWhitelistLoader.fromClasspath();
        assertFalse(wl.statementKinds().isEmpty(), "statementKinds");
        assertFalse(wl.expressionKinds().isEmpty(), "expressionKinds");
        assertFalse(wl.binaryOps().isEmpty(), "binaryOps");
        assertFalse(wl.unaryOps().isEmpty(), "unaryOps");
        assertFalse(wl.globalFunctions().isEmpty(), "globalFunctions");
        assertFalse(wl.methodSignatures().isEmpty(), "methodSignatures");
    }

    @Test
    @DisplayName("version 字段有效（semver 字符串）")
    void load_version() {
        SimpletsWhitelist wl = SimpletsWhitelistLoader.fromClasspath();
        assertTrue(wl.version().matches("\\d+\\.\\d+\\.\\d+"),
            "version 必须是 semver，实际：" + wl.version());
    }

    @Test
    @DisplayName("methodSignatures.methodName ⊆ globalFunctions")
    void load_methodNameSubset() {
        SimpletsWhitelist wl = SimpletsWhitelistLoader.fromClasspath();
        for (var sig : wl.methodSignatures()) {
            assertTrue(wl.globalFunctions().contains(sig.methodName()),
                "methodSignatures.methodName 必须 ∈ globalFunctions: " + sig.methodName());
        }
    }

    @Test
    @DisplayName("updatedAt 是 ISO-8601 时间（且 ≤ now）")
    void load_updatedAt() {
        SimpletsWhitelist wl = SimpletsWhitelistLoader.fromClasspath();
        assertNotNull(wl.updatedAt());
        assertFalse(wl.updatedAt().isAfter(Instant.now()), "updatedAt 必须在过去");
    }

    @Test
    @DisplayName("具体值断言（与 A 端 TS exportToJSON 对齐）")
    void load_specificValues() {
        SimpletsWhitelist wl = SimpletsWhitelistLoader.fromClasspath();

        // statementKinds
        assertTrue(wl.statementKinds().contains("IfStmt"));
        assertTrue(wl.statementKinds().contains("ForStmt"));
        assertTrue(wl.statementKinds().contains("DeclareStmt"));
        assertTrue(wl.statementKinds().contains("AssignStmt"));

        // expressionKinds
        assertTrue(wl.expressionKinds().contains("BinaryExpr"));
        assertTrue(wl.expressionKinds().contains("MemberAccess"));
        assertTrue(wl.expressionKinds().contains("EnumRef"));

        // binaryOps
        assertTrue(wl.binaryOps().contains("+"));
        assertTrue(wl.binaryOps().contains("=="));
        assertTrue(wl.binaryOps().contains("&&"));
        assertTrue(wl.binaryOps().contains("||"));

        // unaryOps（精确匹配 2 个）
        assertEquals(2, wl.unaryOps().size());
        assertTrue(wl.unaryOps().contains("-"));
        assertTrue(wl.unaryOps().contains("!"));

        // globalFunctions（10 个）
        assertEquals(15, wl.globalFunctions().size());
        assertTrue(wl.globalFunctions().contains("get"));
        assertTrue(wl.globalFunctions().contains("containsKey"));
        assertTrue(wl.globalFunctions().contains("keySet"));
        assertTrue(wl.expressionKinds().contains("IndexAccess"));

        // methodSignatures：now → LocalDateTime + 无参
        var nowSig = findSig(wl, "now");
        assertEquals("java.time.LocalDateTime", nowSig.returnType());
        assertEquals(0, nowSig.paramTypes().size());

        // methodSignatures：len → int
        var lenSig = findSig(wl, "len");
        assertEquals("int", lenSig.returnType());

        // methodSignatures：contains → boolean + CharSequence
        var containsSig = findSig(wl, "contains");
        assertEquals("boolean", containsSig.returnType());
        assertEquals(1, containsSig.paramTypes().size());
        assertEquals("java.lang.CharSequence", containsSig.paramTypes().get(0));
    }

    // === 失败路径 ===

    @Test
    @DisplayName("资源缺失 → IllegalStateException（fail-fast）")
    void load_resourceNotFound() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> SimpletsWhitelistLoader.fromClasspath("non-existent-whitelist.json"));
        assertTrue(ex.getMessage().contains("白名单资源未找到"),
            "异常消息应包含『白名单资源未找到』，实际：" + ex.getMessage());
    }

    @Test
    @DisplayName("null 路径 → NullPointerException")
    void load_nullPath() {
        assertThrows(NullPointerException.class,
            () -> SimpletsWhitelistLoader.fromClasspath(null));
    }

    @Test
    @DisplayName("version 为空 → IllegalStateException")
    void load_blankVersion() throws Exception {
        String filename = nextTempFilename();
        String path = writeTempJson(filename, """
            { "version": "", "statementKinds":["x"], "expressionKinds":["x"],
              "binaryOps":["x"], "unaryOps":["x"], "globalFunctions":["a"],
              "methodSignatures":[{"methodName":"a","returnType":"r","paramTypes":[]}]
            }""");
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SimpletsWhitelistLoader.fromClasspath(path));
            assertTrue(ex.getMessage().contains("version"),
                "异常消息应包含『version』，实际：" + ex.getMessage());
        } finally {
            deleteTemp(filename);
        }
    }

    @Test
    @DisplayName("statementKinds 为空 → IllegalStateException")
    void load_emptyStatementKinds() throws Exception {
        String filename = nextTempFilename();
        String path = writeTempJson(filename, """
            { "version":"1.0.0", "statementKinds":[], "expressionKinds":["x"],
              "binaryOps":["x"], "unaryOps":["x"], "globalFunctions":["a"],
              "methodSignatures":[{"methodName":"a","returnType":"r","paramTypes":[]}]
            }""");
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SimpletsWhitelistLoader.fromClasspath(path));
            assertTrue(ex.getMessage().contains("statementKinds"),
                "异常消息应包含『statementKinds』，实际：" + ex.getMessage());
        } finally {
            deleteTemp(filename);
        }
    }

    @Test
    @DisplayName("methodSignature.methodName 不在 globalFunctions → IllegalStateException（防漂移）")
    void load_methodNameDrift() throws Exception {
        String filename = nextTempFilename();
        String path = writeTempJson(filename, """
            { "version":"1.0.0",
              "statementKinds":["IfStmt"], "expressionKinds":["Literal"],
              "binaryOps":["+"], "unaryOps":["-"],
              "globalFunctions":["now"],
              "methodSignatures":[{"methodName":"now","returnType":"int","paramTypes":[]},
                                 {"methodName":"unknownFunc","returnType":"void","paramTypes":[]}]
            }""");
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SimpletsWhitelistLoader.fromClasspath(path));
            assertTrue(ex.getMessage().contains("unknownFunc"),
                "异常消息应包含『unknownFunc』，实际：" + ex.getMessage());
        } finally {
            deleteTemp(filename);
        }
    }

    // === Helpers ===

    private static SimpletsWhitelist.MethodSignature findSig(SimpletsWhitelist wl, String name) {
        return wl.methodSignatures().stream()
            .filter(s -> name.equals(s.methodName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("未找到签名：" + name));
    }

    /** 生成唯一临时文件名。 */
    private static String nextTempFilename() {
        return "whitelist-test-" + TEMP_COUNTER.incrementAndGet() + "-" + UUID.randomUUID() + ".json";
    }

    /**
     * 把 JSON 写到 maven test classpath 目标目录（target/test-classes/{filename}.json），
     * 这样 classloader 才能直接 getResourceAsStream 找到。
     */
    private String writeTempJson(String filename, String json) throws Exception {
        Path testClasses = Path.of("target/test-classes");
        Path target;
        if (Files.isDirectory(testClasses)) {
            target = testClasses.resolve(filename);
        } else {
            target = Path.of("src/test/resources/" + filename);
            Files.createDirectories(target.getParent());
        }
        Files.writeString(target, json);
        return filename;
    }

    private void deleteTemp(String filename) throws Exception {
        Path testClasses = Path.of("target/test-classes");
        Path target = testClasses.resolve(filename);
        if (Files.exists(target)) {
            Files.delete(target);
        } else {
            Files.deleteIfExists(Path.of("src/test/resources/" + filename));
        }
    }
}
