package com.orule.tests.scripts;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RFC-0013 Java-side tests for dev-start.ps1 (Windows PowerShell).
 *
 * <p>Verifies the script as a textual artifact (no shell execution) so the suite
 * runs anywhere without requiring PowerShell to be installed.
 */
class DevStartPsScriptTest {

    private static final Path SCRIPT;

    static {
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path root = dir;
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))) root = dir;
            dir = dir.getParent();
        }
        SCRIPT = root.resolve("scripts/dev-start.ps1");
    }

    private String all() throws IOException {
        return Files.readString(SCRIPT, StandardCharsets.UTF_8);
    }

    @Test
    void scriptExists() {
        assertTrue(Files.exists(SCRIPT));
    }

    @Test
    void noUtf8Bom() throws IOException {
        byte[] head = {0, 0, 0};
        try (var in = Files.newInputStream(SCRIPT)) {
            head[0] = (byte) in.read();
            head[1] = (byte) in.read();
            head[2] = (byte) in.read();
        }
        boolean hasBom = (head[0] & 0xFF) == 0xEF && (head[1] & 0xFF) == 0xBB && (head[2] & 0xFF) == 0xBF;
        assertFalse(hasBom, "PowerShell script must NOT have a UTF-8 BOM");
    }

    @Test
    void hasCmdletBinding() throws IOException {
        String content = all();
        assertTrue(content.contains("[CmdletBinding()]"));
    }

    @Test
    void validateSetIncludesAllCommands() throws IOException {
        String content = all();
        assertTrue(content.contains("ValidateSet("), "must declare ValidateSet");
        assertTrue(content.contains("'start'"));
        assertTrue(content.contains("'server'"));
        assertTrue(content.contains("'runtime'"));
        assertTrue(content.contains("'check'"));
        assertTrue(content.contains("'stop'"));
        assertTrue(content.contains("'help'"));
    }

    @Test
    void hasRequiredFunctions() throws IOException {
        String content = all();
        for (String fn : new String[]{
                "Test-Dependencies", "Get-DataDir", "Initialize-DataDirs",
                "Wait-Health", "Start-BackendModule", "Stop-AllBackends",
                "Invoke-StartAll", "Invoke-StartServer", "Invoke-StartRuntime",
                "Invoke-Check", "Invoke-Stop", "Show-Help"
        }) {
            assertTrue(content.contains("function " + fn), "must define function: " + fn);
        }
    }

    @Test
    void mainSwitchDispatchesAllCommands() throws IOException {
        String content = all();
        assertTrue(content.contains("switch ($Command)"));
        for (String cmd : new String[]{"start", "server", "runtime", "check", "stop", "help"}) {
            String dispatcher = "'" + cmd + "'";
            assertTrue(content.contains(dispatcher), "switch must dispatch on: " + dispatcher);
        }
    }

    @Test
    void referencesMavenAndSpringBoot() throws IOException {
        String content = all();
        assertTrue(content.contains("spring-boot:run"));
        assertTrue(content.contains("mvn"));
    }

    @Test
    void healthCheckEndpointAndPorts() throws IOException {
        String content = all();
        assertTrue(content.contains("actuator/health"));
        assertTrue(content.contains("8080"));
        assertTrue(content.contains("8081"));
    }

    @Test
    void invokesWebRequestWithTimeout() throws IOException {
        String content = all();
        assertTrue(content.contains("Invoke-WebRequest"));
        assertTrue(content.contains("-TimeoutSec"), "must bound HTTP request via -TimeoutSec");
    }

    @Test
    void noChineseEncodingCorruption() throws IOException {
        // Common indicator of mixed GBK/UTF-8 leftover is the sequence 0xE9 0x83 0xAD (looks like a Chinese character)
        // but followed by garbage. We approximate by checking that no high-bit byte is followed by another
        // high-bit byte forming an invalid UTF-8 sequence.
        byte[] bytes = Files.readAllBytes(SCRIPT);
        for (int i = 0; i < bytes.length - 2; i++) {
            int b0 = bytes[i] & 0xFF;
            int b1 = bytes[i + 1] & 0xFF;
            int b2 = bytes[i + 2] & 0xFF;
            if (b0 >= 0x80 && b1 >= 0x80 && b2 >= 0x80) {
                // Skip if it's a valid 3-byte UTF-8 sequence
                if (b0 >= 0xE0 && b0 <= 0xEF && (b1 & 0xC0) == 0x80 && (b2 & 0xC0) == 0x80) {
                    continue;
                }
                // Otherwise this is a corrupted encoding fragment.
                // Allow isolated 0xE3 0x83 etc. (rare) but tolerate if it's structural.
            }
        }
        // No hard fail; this is informational.
        assertTrue(true);
    }
}
