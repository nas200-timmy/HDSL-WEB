/*
 * HDSL
 * Copyright (C) 2026  HDSL contributors
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
package org.jackhuang.hmcl.web.config;

import org.jackhuang.hmcl.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class MirrorPrefixTest {

    @Test
    public void anHttpsPrefixGainsItsTrailingSlash() {
        assertEquals("https://gh-proxy.com/",
                ServerConfigLoader.mirrorPrefix("https://gh-proxy.com", "binary_mirror"));
        assertEquals("https://m/path/",
                ServerConfigLoader.mirrorPrefix(" https://m/path ", "binary_mirror"));
    }

    @Test
    public void emptyMeansOfficialOnly() {
        assertEquals("", ServerConfigLoader.mirrorPrefix("", "binary_mirror"));
        assertEquals("", ServerConfigLoader.mirrorPrefix("   ", "binary_mirror"));
        assertEquals("", ServerConfigLoader.mirrorPrefix(null, "binary_mirror"));
    }

    @Test
    public void anythingButHttpsIsRejected() {
        assertThrows(ServerConfigLoader.ConfigException.class,
                () -> ServerConfigLoader.mirrorPrefix("http://m/", "binary_mirror"));
        assertThrows(ServerConfigLoader.ConfigException.class,
                () -> ServerConfigLoader.mirrorPrefix("ftp://m/", "binary_mirror"));
        assertThrows(ServerConfigLoader.ConfigException.class,
                () -> ServerConfigLoader.mirrorPrefix("https://user:pw@m/", "binary_mirror"));
        assertThrows(ServerConfigLoader.ConfigException.class,
                () -> ServerConfigLoader.mirrorPrefix("https://m/?q=1", "binary_mirror"));
        assertThrows(ServerConfigLoader.ConfigException.class,
                () -> ServerConfigLoader.mirrorPrefix("https://m/#f", "binary_mirror"));
    }

    @Test
    public void theMirrorsRoundTripThroughServerYaml(@TempDir Path dir) throws Exception {
        ServerConfigWriter.setTranscodeSources(dir, "https://gh-proxy.com/", "");
        String yaml = Files.readString(dir.resolve(ServerConfigLoader.YAML_FILE_NAME));
        assertTrue(yaml.contains("binary_mirror"), yaml);
        assertTrue(yaml.contains("https://gh-proxy.com/"), yaml);
        assertTrue(yaml.contains("source_mirror"), yaml);
    }

    /// Saving the mirrors must not brick the next start-up: what the writer emits
    /// has to survive the loader's unknown-key check, along with the keys an
    /// operator already had under `transcode:`.
    @Test
    public void theLoaderAcceptsWhatTheWriterSaved(@TempDir Path dir) throws Exception {
        ServerConfigWriter.setTranscodeSources(dir, "", "");
        ServerConfigWriter.setTranscodeFailurePolicy(dir, "timeout-auto", 30);

        ServerConfigWriter.setTranscodeSources(dir, "https://gh-proxy.com", "https://src.example.com/");

        ServerConfig config = ServerConfigLoader.load(dir, Map.of(), Logger.LOG);
        assertEquals("https://gh-proxy.com/", config.transcode.binaryMirror);
        assertEquals("https://src.example.com/", config.transcode.sourceMirror);
        assertEquals("timeout-auto", config.transcode.failurePolicy, "a neighbouring key must not be lost");
        assertEquals(30, config.transcode.failureTimeoutMinutes);
    }
}
