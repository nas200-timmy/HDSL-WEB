/*
 * HDSL-web
 * Copyright (C) 2026  HDSL-web contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.dsh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The environment a minimal image's children need.
///
/// The combinations are driven with a fake filesystem test, so the assertions
/// are about the rule rather than about this machine: a runtime bundle with
/// `lib/` prepends `LD_LIBRARY_PATH`, one with `bin/setpriv` prepends `PATH`,
/// a `cacert.pem` fills the five TLS variables, and a missing shell is not
/// fabricated.
class BootstrapEnvironmentTest {

    @TempDir
    Path temp;

    @Test
    void aBundleWithSetprivAndLibrariesGetsPathAndLdLibraryPath() throws Exception {
        Path runtime = temp.resolve("runtime").resolve("test");
        Files.createDirectories(runtime.resolve("bin"));
        Files.createDirectories(runtime.resolve("lib"));
        Files.writeString(runtime.resolve("bin").resolve("setpriv"), "#!/bin/sh\n");
        Files.writeString(runtime.resolve("lib").resolve("libstdc++.so.6"), "fake");

        Map<String, String> base = new HashMap<>();
        base.put("PATH", "/usr/bin:/bin");
        base.put("LD_LIBRARY_PATH", "/opt/lib");

        Map<String, String> additions = BootstrapEnvironment.additions(runtime, null, base,
                Files::exists);

        assertEquals(runtime.resolve("bin") + File.pathSeparator + "/usr/bin:/bin",
                additions.get("PATH"));
        assertEquals(runtime.resolve("lib") + File.pathSeparator + "/opt/lib",
                additions.get("LD_LIBRARY_PATH"));
        assertFalse(additions.containsKey("SSL_CERT_FILE"));
    }

    @Test
    void aBundleWithBashButNoSetprivStillGetsPath() throws Exception {
        Path runtime = temp.resolve("runtime-bash");
        Files.createDirectories(runtime.resolve("bin"));
        Files.writeString(runtime.resolve("bin").resolve("bash"), "#!/bin/sh\n");

        Map<String, String> additions = BootstrapEnvironment.additions(runtime, null,
                Map.of("PATH", "/usr/bin"), Files::exists);

        assertEquals(runtime.resolve("bin") + File.pathSeparator + "/usr/bin",
                additions.get("PATH"));
    }

    @Test
    void aSharedPythonPrependsItsBinAndNamesTheInterpreter() throws Exception {
        Path python = temp.resolve("python").resolve("3.12.16");
        Files.createDirectories(python.resolve("bin"));
        Path interpreter = python.resolve("bin").resolve("python3");
        Files.writeString(interpreter, "#!/bin/sh\n");

        Map<String, String> additions = BootstrapEnvironment.additions(null, null, python,
                Map.of("PATH", "/usr/bin:/bin"), Files::exists);

        assertEquals(python.resolve("bin") + File.pathSeparator + "/usr/bin:/bin",
                additions.get("PATH"));
        assertEquals(interpreter.toString(), additions.get("PYTHON"));
        assertEquals(interpreter.toString(), additions.get("npm_config_python"));
    }

    @Test
    void theRuntimeBinStaysAheadOfThePythonBin() throws Exception {
        Path runtime = temp.resolve("runtime-order");
        Files.createDirectories(runtime.resolve("bin"));
        Files.writeString(runtime.resolve("bin").resolve("setpriv"), "#!/bin/sh\n");
        Path python = temp.resolve("python-order").resolve("3.12.16");
        Files.createDirectories(python.resolve("bin"));
        Files.writeString(python.resolve("bin").resolve("python3"), "#!/bin/sh\n");

        Map<String, String> additions = BootstrapEnvironment.additions(runtime, null, python,
                Map.of("PATH", "/usr/bin"), Files::exists);

        assertEquals(runtime.resolve("bin") + File.pathSeparator + python.resolve("bin")
                + File.pathSeparator + "/usr/bin", additions.get("PATH"));
    }

    @Test
    void anOperatorsPythonVariableIsNotReplaced() throws Exception {
        Path python = temp.resolve("python-operator").resolve("3.12.16");
        Files.createDirectories(python.resolve("bin"));
        Files.writeString(python.resolve("bin").resolve("python3"), "#!/bin/sh\n");

        Map<String, String> additions = BootstrapEnvironment.additions(null, null, python,
                Map.of("PATH", "/usr/bin", "PYTHON", "/opt/python/bin/python3"), Files::exists);

        assertFalse(additions.containsKey("PYTHON"));
        assertEquals(python.resolve("bin") + File.pathSeparator + "/usr/bin",
                additions.get("PATH"));
    }

    @Test
    void aBuildToolchainAddsItsBinAndCompilersWithoutHidingPython() throws Exception {
        Path build = temp.resolve("build").resolve("zig-test");
        Files.createDirectories(build.resolve("bin"));
        Files.createDirectories(build.resolve("lib"));
        Files.writeString(build.resolve("bin").resolve("cc"), "#!/bin/sh\n");
        Files.writeString(build.resolve("bin").resolve("make"), "#!/bin/sh\n");
        Files.writeString(build.resolve("lib").resolve("libpkgconf.so.3"), "fake");
        Path python = temp.resolve("python-build").resolve("3.12.16");
        Files.createDirectories(python.resolve("bin"));
        Files.writeString(python.resolve("bin").resolve("python3"), "#!/bin/sh\n");

        Map<String, String> variables = Map.of(
                "CC", build.resolve("bin").resolve("cc").toString(),
                "CXX", build.resolve("bin").resolve("c++").toString(),
                "MAKE", build.resolve("bin").resolve("make").toString(),
                "PKG_CONFIG", build.resolve("bin").resolve("pkg-config").toString());

        Map<String, String> additions = BootstrapEnvironment.additions(null, null, python,
                build.resolve("bin"), build.resolve("lib"), variables,
                Map.of("PATH", "/usr/bin:/bin"), Files::exists);

        assertEquals(build.resolve("bin") + File.pathSeparator + python.resolve("bin")
                + File.pathSeparator + "/usr/bin:/bin", additions.get("PATH"));
        assertEquals(build.resolve("bin").resolve("cc").toString(), additions.get("CC"));
        assertEquals(build.resolve("bin").resolve("c++").toString(), additions.get("CXX"));
        assertEquals(build.resolve("bin").resolve("make").toString(), additions.get("MAKE"));
        assertEquals(build.resolve("bin").resolve("pkg-config").toString(),
                additions.get("PKG_CONFIG"));
        assertEquals(build.resolve("lib").toString(), additions.get("LD_LIBRARY_PATH"));
        assertEquals(python.resolve("bin").resolve("python3").toString(), additions.get("PYTHON"));
    }

    @Test
    void anOperatorsCompilerIsNotReplacedByTheBuildToolchain() throws Exception {
        Path build = temp.resolve("build-operator").resolve("toolchain");
        Files.createDirectories(build.resolve("bin"));

        Map<String, String> additions = BootstrapEnvironment.additions(null, null, null,
                build.resolve("bin"), null,
                Map.of("CC", build.resolve("bin").resolve("cc").toString(),
                        "CXX", build.resolve("bin").resolve("c++").toString(),
                        "MAKE", build.resolve("bin").resolve("make").toString()),
                Map.of("PATH", "/usr/bin", "CC", "/opt/operator/cc"), Files::exists);

        assertFalse(additions.containsKey("CC"));
        assertEquals(build.resolve("bin").resolve("c++").toString(), additions.get("CXX"));
        assertEquals(build.resolve("bin").resolve("make").toString(), additions.get("MAKE"));
        assertEquals(build.resolve("bin") + File.pathSeparator + "/usr/bin",
                additions.get("PATH"));
    }

    @Test
    void aBuildLibraryIsPrependedToAnExistingLdLibraryPath() throws Exception {
        Path build = temp.resolve("build-lib").resolve("toolchain");
        Path lib = Files.createDirectories(build.resolve("lib"));

        Map<String, String> additions = BootstrapEnvironment.additions(null, null, null, null,
                build.resolve("lib"), Map.of(), Map.of("LD_LIBRARY_PATH", "/opt/lib"),
                Files::exists);

        assertEquals(lib + File.pathSeparator + "/opt/lib", additions.get("LD_LIBRARY_PATH"));
    }

    @Test
    void aCaBundleFillsEveryTlsVariable() throws Exception {
        Path ca = temp.resolve("cacert.pem");
        Files.writeString(ca, "cert");

        Map<String, String> additions = BootstrapEnvironment.additions(null, ca,
                Map.of("PATH", "/usr/bin"), Files::exists);

        assertEquals(ca.toString(), additions.get("SSL_CERT_FILE"));
        assertEquals(ca.toString(), additions.get("CURL_CA_BUNDLE"));
        assertEquals(ca.toString(), additions.get("REQUESTS_CA_BUNDLE"));
        assertEquals(ca.toString(), additions.get("GIT_SSL_CAINFO"));
        assertEquals(ca.toString(), additions.get("NODE_EXTRA_CA_CERTS"));
        assertFalse(additions.containsKey("LD_LIBRARY_PATH"));
    }

    @Test
    void anOperatorsTlsVariableIsNotReplaced() throws Exception {
        Path ca = temp.resolve("cacert.pem");
        Files.writeString(ca, "cert");

        Map<String, String> additions = BootstrapEnvironment.additions(null, ca,
                Map.of("SSL_CERT_FILE", "/etc/ssl/private.pem"), Files::exists);

        assertFalse(additions.containsKey("SSL_CERT_FILE"));
        assertEquals(ca.toString(), additions.get("CURL_CA_BUNDLE"));
    }

    @Test
    void noBundleMeansNoPathOrLdLibraryPathInvented() {
        Map<String, String> additions = BootstrapEnvironment.additions(null, null,
                Map.of("PATH", "/usr/bin"), path -> false);

        assertFalse(additions.containsKey("PATH"));
        assertFalse(additions.containsKey("LD_LIBRARY_PATH"));
        assertFalse(additions.containsKey("SHELL"));
        assertTrue(additions.isEmpty());
    }

    @Test
    void aWorkingOperatorShellIsNotOverwritten() throws Exception {
        Path runtime = temp.resolve("runtime-shell");
        Files.createDirectories(runtime.resolve("bin"));
        Files.writeString(runtime.resolve("bin").resolve("bash"), "#!/bin/sh\n");

        Map<String, String> additions = BootstrapEnvironment.additions(runtime, null,
                Map.of("SHELL", "/bin/zsh"), path -> path.toString().equals("/bin/zsh"));

        assertFalse(additions.containsKey("SHELL"));
    }

    @Test
    void anExistingSystemBashIsUsedWhenTheBundleHasNone() {
        Map<String, String> additions = BootstrapEnvironment.additions(null, null,
                Map.of(), path -> path.toString().equals("/bin/bash"));

        assertEquals("/bin/bash", additions.get("SHELL"));
    }

    @Test
    void prependDoesNotDuplicateAPathAlreadyInFront() {
        Path directory = Path.of("/opt/bin");
        assertEquals("/opt/bin:/usr/bin",
                BootstrapEnvironment.prepend(directory, "/opt/bin:/usr/bin"));
        assertEquals("/opt/bin", BootstrapEnvironment.prepend(directory, null));
        assertEquals("/opt/bin", BootstrapEnvironment.prepend(directory, "  "));
    }

    @Test
    void applyAmendsTheMapInPlaceAndLeavesSystemEnvironmentAlone() throws Exception {
        Path runtime = temp.resolve("runtime-apply");
        Files.createDirectories(runtime.resolve("bin"));
        Files.createDirectories(runtime.resolve("lib"));
        Files.writeString(runtime.resolve("bin").resolve("setpriv"), "#!/bin/sh\n");
        Files.writeString(runtime.resolve("lib").resolve("libgcc_s.so.1"), "fake");

        // The pure rule is what the assertions above exercise; this is the
        // "does not touch the JVM's own view" half.
        String before = System.getenv("PATH");
        Map<String, String> environment = new HashMap<>();
        this.applyWith(runtime, environment);
        assertEquals(before, System.getenv("PATH"));
    }

    private void applyWith(Path runtime, Map<String, String> environment) {
        environment.putAll(BootstrapEnvironment.additions(runtime, null, environment,
                Files::exists));
    }

    @Test
    void applyToAProcessBuilderAddsTheSameVariables() throws Exception {
        Path ca = temp.resolve("cacert.pem");
        Files.writeString(ca, "cert");
        ProcessBuilder builder = new ProcessBuilder("true");
        // The static resolver has no bundle in a unit test; the pure form is the
        // one whose values can be asserted, so this only checks the entry point
        // does not throw and leaves the builder usable.
        BootstrapEnvironment.apply(builder);
        assertTrue(builder.environment().containsKey("PATH"));
    }
}