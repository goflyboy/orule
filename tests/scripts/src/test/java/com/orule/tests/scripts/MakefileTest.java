package com.orule.tests.scripts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-0013 Java-side tests for the developer Makefile.
 */
class MakefileTest {

    private static Path locateRepoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path root = dir;
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))) root = dir;
            dir = dir.getParent();
        }
        return root;
    }

    private static Path makefile() {
        return locateRepoRoot().resolve("scripts/Makefile");
    }

    private static String content() throws IOException {
        return Files.readString(makefile(), StandardCharsets.UTF_8);
    }

    @Test
    void makefileExists() {
        assertTrue(Files.exists(makefile()), "scripts/Makefile must exist");
    }

    @Test
    void declaresAllPhonyTargets() throws IOException {
        String c = content();
        assertTrue(c.contains(".PHONY: help build server runtime web start stop clean test"),
                ".PHONY must declare all targets in a single line");
    }

    @Test
    void usesMaven() throws IOException {
        String c = content();
        assertTrue(c.contains("mvn -B -DskipTests clean install"));
        assertTrue(c.contains("mvn -B test"));
    }

    @Test
    void delegatesToDevStart() throws IOException {
        String c = content();
        assertTrue(c.contains("./scripts/dev-start.sh server"));
        assertTrue(c.contains("./scripts/dev-start.sh runtime"));
        assertTrue(c.contains("./scripts/dev-start.sh stop"));
    }

    @Test
    void cleansTargetBuilds() throws IOException {
        String c = content();
        assertTrue(c.contains("packages/*/target"));
    }

    @Test
    void helpTextShowsAllTargets() throws IOException {
        String c = content();
        assertTrue(c.contains("make build"));
        assertTrue(c.contains("make test"));
        assertTrue(c.contains("make server"));
        assertTrue(c.contains("make runtime"));
        assertTrue(c.contains("make start"));
        assertTrue(c.contains("make stop"));
        assertTrue(c.contains("make clean"));
    }
}
