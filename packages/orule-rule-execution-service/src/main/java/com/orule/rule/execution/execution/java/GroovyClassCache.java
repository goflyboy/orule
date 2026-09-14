package com.orule.rule.execution.execution.java;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import groovy.lang.GroovyShell;
import groovy.lang.Script;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * LRU cache for compiled Groovy {@link Script} objects (RFC-0040 §13.3 / ADR-001 Q3).
 *
 * <p>Indexing key: SHA-256 hash of the Java source string (UTF-8 encoded).
 * <p>Default size: 1000 entries, 10-minute idle expiration (overridable via {@code orule.execution.groovy.cache.*}).
 *
 * <p>Concurrency: backed by Caffeine's segmented LRU + bounded queue; safe for high-throughput use.
 *
 * <p>Statistics exposed via {@link #stats()} for Micrometer health/observability
 * (wired in TASK-3.1.1).
 */
@Component
public class GroovyClassCache {

    private static final Logger log = LoggerFactory.getLogger(GroovyClassCache.class);

    private final Cache<String, Script> cache;

    /**
     * Default ctor for {@link java.util.ServiceLoader}; equivalent to
     * {@code new GroovyClassCache(1000, 10)}.
     */
    public GroovyClassCache() {
        this(1000L, 10L);
    }

    public GroovyClassCache(
            @Value("${orule.execution.groovy.cache.max-size:1000}") long maxSize,
            @Value("${orule.execution.groovy.cache.expire-after-access-minutes:10}") long expireMinutes) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterAccess(Duration.ofMinutes(expireMinutes))
                .recordStats()
                .build();
        log.info("GroovyClassCache initialized: maxSize={}, expireAfterAccess={}min", maxSize, expireMinutes);
    }

    /**
     * Compile {@code javaSource} via {@code shell} if not already cached, returning the {@link Script}.
     */
    public Script getOrCompile(String javaSource, GroovyShell shell) {
        String hash = sha256(javaSource);
        return cache.get(hash, k -> shell.parse(javaSource));
    }

    /**
     * Caffeine cache statistics (hits / misses / evictions / size).
     */
    public CacheStats stats() {
        var s = cache.stats();
        return new CacheStats(s.hitCount(), s.missCount(), s.evictionCount(), cache.estimatedSize());
    }

    /**
     * Compute SHA-256 hex digest (64 lowercase chars) of the source string.
     * The hex format keeps it stable for cache keys across JVM restarts.
     */
    static String sha256(String source) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JRE; unreachable under all current JDK versions.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Simple immutable snapshot for observability. */
    public record CacheStats(long hits, long misses, long evictions, long estimatedSize) {
        public double hitRate() {
            long total = hits + misses;
            return total == 0 ? 0.0 : (double) hits / total;
        }
    }
}
