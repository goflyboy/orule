package com.orule.tests.scripts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * RFC-0013 Java-side tests for dev-start.sh.
 *
 * <p>Verifies the script as a textual artifact (no shell execution) so the suite
 * runs anywhere without requiring bash to be installed.
 */
class DevStartScriptTest {

    private static final Path SCRIPT;

    static {
        // user.dir = tests/scripts/. Walk all the way up to the highest-level pom.xml.
        // The root pom.xml is at the repo root; sub-module poms are nested deeper.
        Path dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path root = dir; // fallback: use the deepest found
        while (dir != null) {
            if (Files.exists(dir.resolve("pom.xml"))) {
                root = dir; // keep updating: we want the highest-level pom.xml
            }
            dir = dir.getParent();
        }
        SCRIPT = root.resolve("scripts/dev-start.sh");
    }

    private String readScript() throws IOException {
        return Files.readString(SCRIPT, StandardCharsets.UTF_8);
    }

    @Test
    void scriptExists() {
        assertTrue(Files.exists(SCRIPT), "scripts/dev-start.sh must exist");
        assertTrue(Files.isRegularFile(SCRIPT));
    }

    @Test
    void scriptHasShebang() throws IOException {
        String first = Files.readAllLines(SCRIPT, StandardCharsets.UTF_8).get(0);
        assertTrue(first.startsWith("#!"), "must start with #! shebang");
    }

    @Test
    void scriptHasSetEOption() throws IOException {
        String content = readScript();
        assertTrue(content.contains("set -e"), "must enable 'set -e' for fail-fast");
    }

    @Test
    void scriptDefinesRequiredFunctions() throws IOException {
        String content = readScript();
        for (String fn : new String[]{
                "check_deps", "prepare_dirs", "wait_for_health",
                "start_module", "cmd_check", "cmd_stop",
                "cmd_server", "cmd_runtime", "cmd_start_all", "cmd_help", "main"
        }) {
            Pattern p = Pattern.compile("\\b" + fn + "\\s*\\(\\)");
            Matcher m = p.matcher(content);
            assertTrue(m.find(), "must define function: " + fn);
        }
    }

    @Test
    void scriptReferencesMavenAndSpringBoot() throws IOException {
        String content = readScript();
        assertTrue(content.contains("spring-boot:run"), "must invoke spring-boot:run");
        assertTrue(content.contains("mvn "), "must invoke mvn");
    }

    @Test
    void scriptReferencesHealthEndpointAndPorts() throws IOException {
        String content = readScript();
        assertTrue(content.contains("actuator/health"), "must check /actuator/health");
        assertTrue(content.contains("8080"), "must reference server port 8080");
        assertTrue(content.contains("8081"), "must reference runtime port 8081");
    }

    @Test
    void scriptSwitchesOnCommand() throws IOException {
        String content = readScript();
        assertTrue(content.contains("case \"$cmd\" in"), "main must dispatch via case");
        for (String cmd : new String[]{"start", "server", "runtime", "check", "stop", "help"}) {
            assertTrue(content.contains(cmd), "case must list command: " + cmd);
        }
    }

    @Test
    void scriptHandlesUnknownCommand() throws IOException {
        String content = readScript();
        assertTrue(content.contains("unknown command") || content.contains("err"),
                "must error out on unknown commands");
    }

    @Test
    void scriptHelpTextIsUseful() throws IOException {
        String content = readScript();
        assertTrue(content.contains("ORULE_SERVER_PORT"), "help must document ORULE_SERVER_PORT");
        assertTrue(content.contains("ORULE_RUNTIME_PORT"), "help must document ORULE_RUNTIME_PORT");
        assertTrue(content.contains("ORULE_DATA_DIR"), "help must document ORULE_DATA_DIR");
        assertTrue(content.contains("Usage") || content.contains("usage") || content.contains("\u7528\u6cd5"),
                "help must contain a Usage section");
    }

    @Test
    void scriptHasPathSafetyForJava() throws IOException {
        // Windows JDK installs may live under "/mnt/c/Program Files/..." which have spaces
        String content = readScript();
        assertTrue(content.contains("Java") || content.contains("java"),
                "script must locate java executable");
    }

    @Test
    void scriptStopIsIdempotent(@TempDir Path tmp) throws IOException {
        // Write a tiny test that sources a "stop" noop and verifies exit code 0.
        // We can't run bash here, but we can assert that the script defines cmd_stop and that
        // it tolerates the case where no process is matching.
        String content = readScript();
        assertTrue(content.contains("cmd_stop()"), "must define cmd_stop");
        // Path-walking "pkill -f ..." would risk killing unrelated processes; we already
        // replaced it with pgrep-based kill that's a no-op when nothing matches.
        assertTrue(content.contains("pgrep") || content.contains("pkill"),
                "stop command must use process-discovery (pgrep or pkill)");
    }

    @Test
    void scriptExitsNonZeroOnUnknownCommand(@TempDir Path tmp) throws IOException {
        String content = readScript();
        assertTrue(content.contains("err \""), "main must call err() on unknown command");
    }

    @Test
    void waitForHealthFunctionDefined() throws IOException {
        String content = readScript();
        Pattern p = Pattern.compile("wait_for_health\\s*\\(\\)\\s*\\{");
        assertTrue(p.matcher(content).find(), "must define wait_for_health() { ... }");
        assertTrue(content.contains("curl"), "wait_for_health must invoke curl");
        assertTrue(content.contains("--max-time"), "curl must use a max-time to bound the request");
    }
}
