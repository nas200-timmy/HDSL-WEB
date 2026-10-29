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
package org.jackhuang.hmcl.web.minecraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.web.audit.AuditLog;
import org.jackhuang.hmcl.web.auth.AuthFilter;
import org.jackhuang.hmcl.web.auth.AuthService;
import org.jackhuang.hmcl.web.config.ServerConfig;
import org.jackhuang.hmcl.web.developer.DeveloperAuth;
import org.jackhuang.hmcl.web.developer.DeveloperElevation;
import org.jackhuang.hmcl.web.emergency.EmergencyStop;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.http.RequestSecurity;
import org.jackhuang.hmcl.web.terminal.TerminalTickets;
import org.jackhuang.hmcl.web.terminal.TerminalWebSocket;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/// `/api/mc/*` — the Minecraft surface's HTTP contract.
///
/// The feature switch, the instances and the lifecycle live behind
/// [McFeatureFilter]; this servlet adds the operations: the enable flow (which
/// records the acceptance, never invents it), the stop/keep/close decisions,
/// instance CRUD, start/stop/kill, logs, `server.properties` and the console
/// ticket.
///
/// Everything that starts a process, deletes data or changes the feature needs
/// the developer elevation — the same second password the web shell uses. The
/// one exception is "start an instance": it is a daily, reversible action
/// (`docs/minecraft.md` §5.4).
@NotNullByDefault
public final class McApiServlet extends HttpServlet {

    /// The official EULA. Fetched at runtime and never bundled: the text is
    /// Mojang's, and the panel's job is to show it, not to distribute it.
    static final URI EULA_URI = URI.create("https://www.minecraft.net/en-us/eula");
    private static final Duration EULA_MAX_AGE = Duration.ofHours(24);
    private static final Set<String> MANAGED_PROPERTIES = Set.of(
            "motd", "max-players", "online-mode", "white-list", "enforce-whitelist",
            "difficulty", "gamemode", "pvp", "allow-flight", "spawn-protection",
            "view-distance", "simulation-distance");

    private final ServerConfig config;
    private final McFeatureState feature;
    private final McInstanceManager manager;
    private final McRuntime runtime;
    private final TerminalTickets tickets;
    private final AuthService auth;
    private final DeveloperAuth developerAuth;
    private final DeveloperElevation elevation;
    private final BooleanSupplier tlsActive;
    private final EmergencyStop emergency;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /// The last fetched EULA, with the hash the enable flow has to echo back.
    private volatile @Nullable CachedEula eula;

    private record CachedEula(String text, String hash, String fetchedAt, Instant at) {
    }

    public McApiServlet(ServerConfig config, McFeatureState feature, McInstanceManager manager,
                        McRuntime runtime, TerminalTickets tickets, AuthService auth,
                        DeveloperAuth developerAuth, DeveloperElevation elevation,
                        BooleanSupplier tlsActive, EmergencyStop emergency) {
        this.config = config;
        this.feature = feature;
        this.manager = manager;
        this.runtime = runtime;
        this.tickets = tickets;
        this.auth = auth;
        this.developerAuth = developerAuth;
        this.elevation = elevation;
        this.tlsActive = tlsActive;
        this.emergency = emergency;
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo() == null ? "/" : request.getPathInfo();
        String method = request.getMethod();
        if (emergency.isEngaged() && !"GET".equals(method)) {
            Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "emergency_stop: the panel is in emergency stop; Minecraft actions are frozen");
            return;
        }
        try {
            if ("/feature".equals(path) && "GET".equals(method)) {
                featureJson(response);
                return;
            }
            if ("/eula".equals(path) && "GET".equals(method)) {
                eula(request, response);
                return;
            }
            if ("/feature/enable".equals(path) && "POST".equals(method)) {
                enable(request, response);
                return;
            }
            if ("/feature/stop".equals(path) && "POST".equals(method)) {
                stopFeature(request, response);
                return;
            }
            if ("/feature/confirm-close".equals(path) && "POST".equals(method)) {
                confirmClose(request, response);
                return;
            }
            if ("/feature/cancel-close".equals(path) && "POST".equals(method)) {
                cancelClose(request, response);
                return;
            }
            if ("/feature/decline".equals(path) && "POST".equals(method)) {
                audit("mc.feature.declined", "MC feature", "success", request);
                Json.write(response, Map.of("ok", true));
                return;
            }
            if ("/instances".equals(path)) {
                if ("GET".equals(method)) {
                    listInstances(response);
                    return;
                }
                if ("POST".equals(method)) {
                    createInstance(request, response);
                    return;
                }
            }
            if (path.startsWith("/instances/")) {
                routeInstance(request, response, method, path.substring("/instances/".length()));
                return;
            }
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
        } catch (McException e) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException | IllegalArgumentException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
        }
    }

    // ------------------------------------------------------------------ feature --

    private void featureJson(HttpServletResponse response) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("state", feature.state().name());
        body.addProperty("enabled", feature.enabled());
        body.addProperty("locked", feature.locked());
        body.addProperty("lockRemainingSeconds", feature.lockRemainingSeconds());
        Boolean keep = feature.keepFeatureChoice();
        if (keep == null) {
            body.add("keepFeature", com.google.gson.JsonNull.INSTANCE);
        } else {
            body.addProperty("keepFeature", keep);
        }
        feature.consent().ifPresent(record -> {
            body.addProperty("termsVersion", record.termsVersion());
            body.addProperty("termsAcceptedAt", record.termsAcceptedAt());
            body.addProperty("acceptedBy", record.acceptedBy());
        });
        Json.write(response, body);
    }

    private void eula(HttpServletRequest request, HttpServletResponse response) throws IOException {
        CachedEula cached = eula;
        if (cached == null || Instant.now().isAfter(cached.at().plus(EULA_MAX_AGE))) {
            cached = fetchEula();
            if (cached == null) {
                Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "eula_unavailable: the official EULA could not be fetched; the feature cannot be enabled offline");
                return;
            }
            eula = cached;
        }
        JsonObject body = new JsonObject();
        body.addProperty("text", cached.text());
        body.addProperty("hash", cached.hash());
        body.addProperty("fetchedAt", cached.fetchedAt());
        body.addProperty("source", EULA_URI.toString());
        Json.write(response, body);
    }

    private @Nullable CachedEula fetchEula() {
        try {
            HttpRequest request = HttpRequest.newBuilder(EULA_URI)
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "HDSL-web/" + Metadata.VERSION)
                    .GET()
                    .build();
            HttpResponse<byte[]> answer = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (answer.statusCode() != 200) {
                return null;
            }
            byte[] bytes = answer.body();
            String text = new String(bytes, StandardCharsets.UTF_8);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hash = "sha256:" + HexFormat.of().formatHex(digest.digest(bytes));
            return new CachedEula(text, hash, Instant.now().toString(), Instant.now());
        } catch (Exception e) {
            return null;
        }
    }

    private void enable(HttpServletRequest request, HttpServletResponse response)
            throws IOException, McException {
        if (!requireElevation(request, response)) {
            return;
        }
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        boolean eulaAcknowledged = booleanField(body, "eulaAcknowledged");
        boolean termsAcknowledged = booleanField(body, "termsAcknowledged");
        boolean sawDecline = booleanField(body, "sawDeclineOption");
        String eulaHash = stringField(body, "eulaHash");
        String termsTextHash = stringField(body, "termsTextHash");
        String uiVersion = stringField(body, "uiVersion");
        int termsVersion = intField(body, "termsVersion");

        if (!eulaAcknowledged || !termsAcknowledged) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "both the EULA and the terms must be acknowledged");
            return;
        }
        if (!sawDecline) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "the decline option must have been visible (sawDeclineOption)");
            return;
        }
        if (eulaHash == null || termsTextHash == null || uiVersion == null || termsVersion < 1) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "eulaHash, termsTextHash, termsVersion and uiVersion are required");
            return;
        }
        CachedEula cached = eula;
        if (cached == null) {
            Json.error(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "eula_not_fetched: fetch the EULA first; the panel will not accept a hash it cannot verify");
            return;
        }
        if (!cached.hash().equals(eulaHash)) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "the EULA changed since it was shown; show it again");
            return;
        }

        String now = Instant.now().toString();
        McConsent record = new McConsent(termsVersion, termsTextHash, eulaHash,
                cached.fetchedAt(), now, now,
                actorOf(request) == null ? "unknown" : actorOf(request),
                request.getRemoteAddr(),
                "scroll+checkbox", true, uiVersion,
                request.getHeader("User-Agent"), Metadata.VERSION, null);
        try {
            feature.enable(record);
        } catch (McFeatureState.LockedException e) {
            response.setStatus(HttpServletResponse.SC_CONFLICT);
            Json.write(response, Map.of("error", "mc_locked", "remainingSeconds", e.remainingSeconds()));
            return;
        }
        // One acceptance covers every instance: the EULA is between the operator
        // and Mojang, not per world.
        for (McInstance instance : manager.list()) {
            manager.acceptEula(instance);
        }
        audit("mc.feature.enable", "terms v" + termsVersion + " (" + eulaHash + ")", "success", request);
        featureJson(response);
    }

    private void stopFeature(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireElevation(request, response)) {
            return;
        }
        List<String> ids = new ArrayList<>();
        for (McInstance instance : manager.list()) {
            if (runtime.isRunning(instance.id())) {
                ids.add(instance.id());
                try {
                    runtime.stop(instance.id());
                } catch (McException ignored) {
                    // It is not running any more; nothing to stop.
                }
            }
        }
        boolean started = feature.stopFeature();
        audit("mc.feature.stop", "instances=" + ids.size(), started ? "started" : "already-locked", request);
        featureJson(response);
    }

    private void confirmClose(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireElevation(request, response)) {
            return;
        }
        try {
            feature.confirmClose();
        } catch (IllegalStateException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            return;
        }
        audit("mc.feature.confirm-close", "MC feature", "success", request);
        featureJson(response);
    }

    private void cancelClose(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!requireElevation(request, response)) {
            return;
        }
        try {
            feature.decideKeep();
        } catch (IllegalStateException e) {
            Json.error(response, HttpServletResponse.SC_CONFLICT, e.getMessage());
            return;
        }
        audit("mc.feature.keep", "MC feature", "success", request);
        featureJson(response);
    }

    // ---------------------------------------------------------------- instances --

    private void listInstances(HttpServletResponse response) throws IOException {
        List<JsonObject> items = new ArrayList<>();
        for (McInstance instance : manager.list()) {
            items.add(instanceJson(instance));
        }
        Json.write(response, Map.of("instances", items));
    }

    private void createInstance(HttpServletRequest request, HttpServletResponse response)
            throws IOException, McException {
        if (!requireElevation(request, response)) {
            return;
        }
        if (manager.list().size() >= config.mc.maxInstances) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "the panel already manages " + config.mc.maxInstances + " Minecraft instance(s)");
            return;
        }
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        String name = stringField(body, "name");
        String version = stringField(body, "version");
        if (name == null || name.isBlank() || version == null || version.isBlank()) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "name and version are required");
            return;
        }
        int memoryMb = intField(body, "memoryMb");
        if (memoryMb == 0) {
            memoryMb = 2048;
        }
        if (memoryMb > config.mc.maxMemoryMb) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST,
                    "memoryMb must not exceed " + config.mc.maxMemoryMb);
            return;
        }
        String levelName = stringField(body, "levelName");
        if (levelName == null || levelName.isBlank()) {
            levelName = "world";
        }
        String seed = stringField(body, "seed");

        int port = intField(body, "port");
        int rconPort = intField(body, "rconPort");
        if (port == 0 || rconPort == 0) {
            McPorts.Pair pair = McPorts.allocate(manager::portTaken, config.mc.portStart, config.mc.portEnd);
            port = pair.game();
            rconPort = pair.rcon();
        }

        McInstance instance = manager.create(name, version, port, rconPort, memoryMb, levelName, seed);
        if (feature.consent().isPresent()) {
            manager.acceptEula(instance);
        }
        audit("mc.instance.create", instance.id(), "success", request);
        Json.write(response, HttpServletResponse.SC_CREATED, instanceJson(instance));
    }

    private void routeInstance(HttpServletRequest request, HttpServletResponse response,
                               String method, String rest) throws IOException, McException {
        String[] segments = rest.split("/", -1);
        String id = segments[0];
        McInstance instance = manager.find(id);
        if (instance == null) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }
        if (segments.length == 1) {
            if ("GET".equals(method)) {
                Json.write(response, instanceJson(instance));
                return;
            }
            if ("PATCH".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                JsonObject body = readJson(request);
                String name = body == null ? null : stringField(body, "name");
                if (name == null || name.isBlank()) {
                    Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "name is required");
                    return;
                }
                manager.update(instance.withName(name.trim()));
                audit("mc.instance.rename", id, "success", request);
                Json.write(response, instanceJson(manager.find(id)));
                return;
            }
            if ("DELETE".equals(method)) {
                if (!requireElevation(request, response)) {
                    return;
                }
                if (runtime.isRunning(id)) {
                    Json.error(response, HttpServletResponse.SC_CONFLICT, "stop the server before deleting it");
                    return;
                }
                manager.delete(id);
                audit("mc.instance.delete", id, "success", request);
                Json.write(response, Map.of("ok", true));
                return;
            }
        }
        String action = segments.length > 1 ? segments[1] : "";
        switch (action) {
            case "start" -> {
                runtime.launch(id);
                audit("mc.instance.start", id, "success", request);
                Json.write(response, instanceJson(instance));
            }
            case "stop" -> {
                if (!requireElevation(request, response)) {
                    return;
                }
                runtime.stop(id);
                audit("mc.instance.stop", id, "success", request);
                Json.write(response, instanceJson(instance));
            }
            case "kill" -> {
                if (!requireElevation(request, response)) {
                    return;
                }
                runtime.kill(id);
                audit("mc.instance.kill", id, "success", request);
                Json.write(response, instanceJson(instance));
            }
            case "logs" -> {
                int tail = parseInt(request.getParameter("tail"), 200);
                List<String> lines = manager.tailLog(instance, Math.max(1, Math.min(2000, tail)));
                Json.write(response, Map.of("lines", lines));
            }
            case "console" -> {
                String consoleAction = segments.length > 2 ? segments[2] : "";
                if (!"ticket".equals(consoleAction)) {
                    Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
                    return;
                }
                if (!requireElevation(request, response)) {
                    return;
                }
                if (runtime.console(id).isEmpty()) {
                    Json.error(response, HttpServletResponse.SC_CONFLICT, "the server is not running");
                    return;
                }
                String ticket = tickets.issue(id, McRuntime.BRAND, sessionToken(request));
                JsonObject body = new JsonObject();
                body.addProperty("mode", "pty");
                body.addProperty("protocol", TerminalWebSocket.PROTOCOL);
                body.addProperty("instance", id);
                body.addProperty("brand", McRuntime.BRAND);
                body.addProperty("ticket", ticket);
                body.addProperty("ws", "/ws/terminal/" + ticket);
                Json.write(response, body);
            }
            case "properties" -> properties(request, response, method, instance);
            default -> Json.error(response, HttpServletResponse.SC_NOT_FOUND, "not found");
        }
    }

    private void properties(HttpServletRequest request, HttpServletResponse response,
                            String method, McInstance instance) throws IOException, McException {
        if ("GET".equals(method)) {
            Json.write(response, Map.of("properties", manager.properties(instance).asMap()));
            return;
        }
        if (!"PUT".equals(method)) {
            Json.error(response, HttpServletResponse.SC_METHOD_NOT_ALLOWED, "use GET or PUT");
            return;
        }
        if (!requireElevation(request, response)) {
            return;
        }
        JsonObject body = readJson(request);
        if (body == null) {
            Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "request body must be a JSON object");
            return;
        }
        McProperties properties = manager.properties(instance);
        for (String key : body.keySet()) {
            if (!MANAGED_PROPERTIES.contains(key)) {
                Json.error(response, HttpServletResponse.SC_BAD_REQUEST, "`" + key + "` is not a managed field");
                return;
            }
            properties.set(key, body.get(key).getAsString());
        }
        manager.saveProperties(instance, properties);
        audit("mc.instance.properties", instance.id(), "success", request);
        Json.write(response, Map.of("properties", properties.asMap()));
    }

    // ------------------------------------------------------------------- helpers --

    private JsonObject instanceJson(McInstance instance) {
        McRuntime.Status status = runtime.status(instance.id());
        JsonObject body = new JsonObject();
        body.addProperty("id", instance.id());
        body.addProperty("name", instance.name());
        body.addProperty("version", instance.version());
        body.addProperty("port", instance.port());
        body.addProperty("rconPort", instance.rconPort());
        body.addProperty("memoryMb", instance.memoryMb());
        body.addProperty("levelName", instance.levelName());
        if (instance.seed() != null) {
            body.addProperty("seed", instance.seed());
        }
        body.addProperty("createdAt", instance.createdAt());
        body.addProperty("state", status.state().name());
        if (status.error() != null) {
            body.addProperty("error", status.error());
        }
        body.addProperty("consoleAvailable", runtime.console(instance.id()).isPresent());
        return body;
    }

    /// Step-up for the operations that start processes, delete data or change
    /// the feature. The pattern is the brand page's: HTTPS + developer
    /// elevation, with the reason spelled out when it is missing.
    private boolean requireElevation(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!RequestSecurity.isSecure(request, tlsActive.getAsBoolean(), config)) {
            Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                    "tls_required: the developer elevation is only accepted over HTTPS");
            return false;
        }
        if (!developerAuth.isSet()) {
            Json.error(response, HttpServletResponse.SC_CONFLICT,
                    "developer_password_not_set: set the developer password before enabling the Minecraft feature");
            return false;
        }
        if (elevation.require(keyOf(request), sessionAlive(request))) {
            return true;
        }
        Json.error(response, HttpServletResponse.SC_FORBIDDEN,
                "developer_elevation_required: elevate with the developer password first");
        return false;
    }

    private String keyOf(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return DeveloperElevation.key(token instanceof String value ? value : null, request.getRemoteAddr());
    }

    private boolean sessionAlive(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return !(token instanceof String value) || value.isBlank() || auth.isAlive(value);
    }

    private static @Nullable String sessionToken(HttpServletRequest request) {
        Object token = request.getAttribute(AuthFilter.SESSION_TOKEN_ATTRIBUTE);
        return token instanceof String value ? value : null;
    }

    private static @Nullable String actorOf(HttpServletRequest request) {
        Object session = request.getAttribute(AuthFilter.SESSION_ATTRIBUTE);
        return session instanceof AuthService.Session s ? s.username() : null;
    }

    private void audit(String action, String object, String outcome, HttpServletRequest request) {
        AuditLog.log(AuditLog.Channel.WEB, action, object, outcome, actorOf(request), request.getRemoteAddr());
    }

    private static @Nullable JsonObject readJson(HttpServletRequest request) throws IOException {
        String text = new String(request.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static @Nullable String stringField(JsonObject body, String name) {
        return body.has(name) && body.get(name).isJsonPrimitive() ? body.get(name).getAsString() : null;
    }

    private static boolean booleanField(JsonObject body, String name) {
        return body.has(name) && body.get(name).isJsonPrimitive() && body.get(name).getAsBoolean();
    }

    private static int intField(JsonObject body, String name) {
        try {
            return body.has(name) && body.get(name).isJsonPrimitive() ? body.get(name).getAsInt() : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static int parseInt(@Nullable String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}