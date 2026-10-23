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
package org.jackhuang.hmcl.web.config;

import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/// Writes changes back to `server.yaml`.
///
/// [ServerConfigLoader] is read-only by design: it validates on the way in and
/// answers with a typed [ServerConfig]. The one writer is the certificate
/// upload, which has to make its choice survive a restart. The writer edits
/// the **parsed** document — a `Map` — rather than the text: keys this build
/// does not know ride along untouched, and a malformed or half-written file
/// fails the write instead of being silently replaced.
///
/// Comments and key order of the old file do not survive a rewrite; the values
/// do. That is the documented trade of a YAML updater without a comment model.
@NotNullByDefault
public final class ServerConfigWriter {
    private ServerConfigWriter() {
    }

    /// Records an uploaded TLS identity in `server.yaml`: `https.enabled` on
    /// and the file(s) of the uploaded kind, with the other kind's keys
    /// removed so a restart loads what was just uploaded (PKCS#12 wins the
    /// load order, so a stale `pkcs12_file` would shadow a PEM upload).
    ///
    /// @param dataDir        the data directory `server.yaml` lives in
    /// @param pem            whether the upload is a PEM pair (PKCS#12 when false)
    /// @param certFile       `cert_file` value for a PEM upload
    /// @param keyFile        `key_file` value for a PEM upload
    /// @param pkcs12File     `pkcs12_file` value for a PKCS#12 upload
    /// @param pkcs12Password `pkcs12_password` value for a PKCS#12 upload
    /// @throws IOException when the file cannot be read or written
    public static void enableTls(Path dataDir, boolean pem,
                                 @Nullable String certFile, @Nullable String keyFile,
                                 @Nullable String pkcs12File, @Nullable String pkcs12Password)
            throws IOException {
        Path yamlFile = dataDir.resolve(ServerConfigLoader.YAML_FILE_NAME);
        Map<String, Object> root = readExisting(yamlFile);

        Object httpsValue = root.get("https");
        Map<String, Object> https;
        if (httpsValue instanceof Map<?, ?> map) {
            https = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                https.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        } else {
            https = new LinkedHashMap<>();
        }
        https.put("enabled", true);
        if (pem) {
            https.put("cert_file", certFile);
            https.put("key_file", keyFile);
            https.remove("pkcs12_file");
            https.remove("pkcs12_password");
        } else {
            https.put("pkcs12_file", pkcs12File);
            https.put("pkcs12_password", pkcs12Password);
            https.remove("cert_file");
            https.remove("key_file");
        }
        root.put("https", https);

        writeAtomically(yamlFile, root);
    }

    /// Switches `server.yaml` to HTTPS backed by the generated self-signed
    /// pair: `https.enabled` on and every uploaded/configured file reference
    /// removed, so a restart loads (or regenerates) the pair under
    /// `<data>/certs/self-signed*.pem`. Files already on disk are left alone.
    ///
    /// @param dataDir the data directory `server.yaml` lives in
    /// @throws IOException when the file cannot be read or written
    public static void enableSelfSigned(Path dataDir) throws IOException {
        Path yamlFile = dataDir.resolve(ServerConfigLoader.YAML_FILE_NAME);
        Map<String, Object> root = readExisting(yamlFile);

        Object httpsValue = root.get("https");
        Map<String, Object> https = new LinkedHashMap<>();
        if (httpsValue instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                https.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        https.put("enabled", true);
        https.remove("cert_file");
        https.remove("key_file");
        https.remove("pkcs12_file");
        https.remove("pkcs12_password");
        root.put("https", https);

        writeAtomically(yamlFile, root);
    }

    /// Records the Web Shell switch in `server.yaml` (`web_shell.enabled`), so
    /// the developer page's choice survives a restart. Other keys in the
    /// section ride along untouched.
    ///
    /// @param dataDir the data directory `server.yaml` lives in
    /// @param enabled whether instances may open a terminal
    /// @throws IOException when the file cannot be read or written
    public static void updateWebShell(Path dataDir, boolean enabled) throws IOException {
        Path yamlFile = dataDir.resolve(ServerConfigLoader.YAML_FILE_NAME);
        Map<String, Object> root = readExisting(yamlFile);

        Object current = root.get("web_shell");
        Map<String, Object> section = new LinkedHashMap<>();
        if (current instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                section.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        section.put("enabled", enabled);
        root.put("web_shell", section);

        writeAtomically(yamlFile, root);
    }

    /// Reads the existing document, or an empty map when there is none.
    private static Map<String, Object> readExisting(Path yamlFile) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        if (!Files.isRegularFile(yamlFile)) {
            return root;
        }
        LoadSettings settings = LoadSettings.builder().setLabel(yamlFile.toString()).build();
        Load load = new Load(settings);
        Object parsed;
        try (InputStream in = Files.newInputStream(yamlFile)) {
            parsed = load.loadFromInputStream(in);
        } catch (YamlEngineException e) {
            throw new IOException("Failed to parse " + yamlFile + " for update: " + e.getMessage(), e);
        }
        if (parsed instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                root.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return root;
    }

    /// Writes the document by replacing the file, so a half-written
    /// `server.yaml` is never what a restart reads. The staging file carries
    /// owner-only permissions: the document can hold a PKCS#12 password.
    private static void writeAtomically(Path yamlFile, Map<String, Object> root) throws IOException {
        Dump dump = new Dump(DumpSettings.builder().build());
        String body = dump.dumpToString(root);
        Files.createDirectories(yamlFile.getParent());
        Path staging = yamlFile.resolveSibling(ServerConfigLoader.YAML_FILE_NAME + ".hdsl");
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(staging), StandardCharsets.UTF_8)) {
            writer.write(body);
        }
        try {
            Files.setPosixFilePermissions(staging, Set.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException e) {
            // No POSIX permission model here (Windows): the move below still
            // applies, there is just nothing stricter to ask for.
        }
        Files.move(staging, yamlFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
