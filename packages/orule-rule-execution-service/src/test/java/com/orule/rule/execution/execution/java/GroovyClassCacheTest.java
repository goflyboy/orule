package com.orule.rule.execution.execution.java;

import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RFC-0040 TASK-1.2.4 acceptance tests for {@link GroovyClassCache}.
 */
@DisplayName("GroovyClassCache")
class GroovyClassCacheTest {

    private GroovyClassCache cache;
    private GroovyShell shell;

    @BeforeEach
    void setUp() {
        // Bypass @Value: explicit constructor invocation with default-friendly args.
        // size=100 / expire=10 min, but tests only need 2-3 entries.
        cache = new GroovyClassCache(100L, 10L);
        shell = new GroovyShell();
    }

    @Test
    @DisplayName("returns compiled Script for same source (cache hit)")
    void cacheHit() {
        String source = "return 1 + 1";

        Script first = cache.getOrCompile(source, shell);
        Script second = cache.getOrCompile(source, shell);

        // Same compiled Script instance — proves the cache returned the same object.
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("compiles independently for distinct source strings")
    void distinctSource() {
        Script s1 = cache.getOrCompile("return 1 + 1", shell);
        Script s2 = cache.getOrCompile("return 2 + 2", shell);

        assertThat(s1).isNotSameAs(s2);
    }

    @Test
    @DisplayName("records hits and misses in stats")
    void stats() {
        cache.getOrCompile("return 1", shell);             // miss
        cache.getOrCompile("return 2", shell);             // miss
        cache.getOrCompile("return 1", shell);             // hit
        cache.getOrCompile("return 2", shell);             // hit
        cache.getOrCompile("return 1", shell);             // hit

        var stats = cache.stats();
        assertThat(stats.hits()).isEqualTo(3L);
        assertThat(stats.misses()).isEqualTo(2L);
        assertThat(stats.hitRate()).isEqualTo(0.6);
    }

    @Test
    @DisplayName("sha256 is deterministic and hex-formatted")
    void sha256Deterministic() {
        String s1 = GroovyClassCache.sha256("rule");
        String s2 = GroovyClassCache.sha256("rule");
        assertThat(s1).isEqualTo(s2);
        assertThat(s1).hasSize(64).matches("[0-9a-f]+");
        assertThat(GroovyClassCache.sha256("rule"))
                .isNotEqualTo(GroovyClassCache.sha256("Rule"));
    }
}
