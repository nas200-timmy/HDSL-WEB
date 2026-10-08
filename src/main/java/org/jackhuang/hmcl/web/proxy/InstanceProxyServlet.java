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
package org.jackhuang.hmcl.web.proxy;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jackhuang.hmcl.dsh.DshInstance;
import org.jackhuang.hmcl.dsh.DshInstanceManager;
import org.jackhuang.hmcl.dsh.DshProcess;
import org.jackhuang.hmcl.dsh.DshProcessManager;
import org.jackhuang.hmcl.web.http.Json;
import org.jackhuang.hmcl.web.task.TaskService;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/// The reverse proxy behind `/i/*`: one dsh web instance per mount, reached
/// through the loopback port its readiness line reported.
///
/// The translation follows the reference implementation in the dsh
/// repository (`apps/web/tests/prefix-proxy.ts`), ported to Jetty:
///
/// - `/i/<id>` without a trailing slash is a 301 to `/i/<id>/` (token exchange
///   needs the directory form), query string preserved;
/// - `/i/<id>/<rest>` forwards to `http://127.0.0.1:<port>/<rest>` where the
///   port comes from the readiness line, never from the manifest — a patch
///   layer may have moved the server;
/// - hop-by-hop headers are stripped in both directions; **the Host header
///   passes through untouched**, because dsh's browser-trust fence compares
///   Origin to Host and refuses rewritten requests;
/// - `Set-Cookie: ...; Path=/` becomes `Path=/i/<id>/` so the cookies of two
///   instances (which share their names, decided by host alone) stop
///   overwriting each other;
/// - a redirect naming the instance's own address is rewritten onto the mount
///   — the token exchange answers with `303 Location: /`, and the browser that
///   follows it would leave the mount for the panel's own page;
/// - the page itself is rewritten on the way past: its `<base>` and its
///   `/plugins/` registrations are given the mount, and a small shim keeps the
///   requests it builds at runtime inside it (see [#rewritePage]);
/// - a page whose client routes by `location.pathname` gets one thing more: its
///   router bundle is patched to read the mount away ([#patchRouterBundle]), so
///   the address bar can keep the mount and still match the client's routes
///   (see [ROUTER_JS]);
/// - bodies stream in both directions (a `Flow.Publisher` over the servlet
///   input on the way up, `BodyHandlers.ofInputStream` on the way down), so
///   SSE — `/plugins/events` — works without special treatment;
/// - when the client goes away the upstream request is cancelled.
///
/// WebSocket upgrades under `/i/*` are not handled here; Jetty routes them to
/// the [org.jackhuang.hmcl.web.proxy.InstanceProxyWebSocket] endpoint before
/// the servlet ever sees them.
///
/// That reference is a fixture for the dsh repository's own test client, and that
/// client is prefix-aware: it takes the mount as its base, follows the token
/// exchange itself (`redirect: 'manual'`, with a `Location` of `./` meaning "in
/// place"), and resolves every path against that base. The real web client is
/// not — it is built to own the origin — so four things here have no
/// counterpart upstream and are this launcher's own: the `Location` rewrite, the
/// page rewrite, the shim that keeps the requests the page builds at runtime
/// inside the mount, and the router patch that teaches a path-routed client the
/// mount it is served under (see [#rewritePage]). A reader comparing the two should not
/// read their absence upstream as an oversight here.
@NotNullByDefault
public final class InstanceProxyServlet extends HttpServlet {

    static {
        // java.net.http refuses to set Connection/Content-Length/Expect/Host/
        // Upgrade via its builder API unless the property names them. Host
        // passthrough is the one contract dsh itself enforces (Origin must
        // equal Host), so it is enabled here, before any client is built.
        System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");
    }

    /// Headers that describe one hop and must not reach the other side.
    public static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-connection", "transfer-encoding", "upgrade", "te", "trailer");

    /// Matches the `Path=/` of a Set-Cookie value, at the start or after a
    /// separator, so it can be rewritten to the instance's mount.
    private static final Pattern COOKIE_PATH = Pattern.compile("(^|;\\s*)Path=/(?=;|$)", Pattern.CASE_INSENSITIVE);

    /// The per-mount script the generic page rewrite references: served by this
    /// servlet itself (see [service]) rather than inlined, because some brands
    /// (OpenCode) answer their pages with a Content-Security-Policy whose
    /// `script-src 'self'` refuses every inline script — a same-origin
    /// `<script src>` passes that policy where an inline shim is discarded
    /// before it ever runs. Measured 2026-10: the shim was present in the
    /// served HTML yet `window.fetch` stayed native, and every runtime API
    /// call 404'd at the panel root.
    static final String SHIM_RESOURCE = "__hdsl-shim.js";

    /// The per-mount script a client that routes by `location.pathname` needs
    /// ([Brand#needsRouterShim] — OpenCode): served under `/i/<id>/`, such a
    /// client is asked for a route it cannot match and renders an empty shell.
    /// The resource carries both halves of the answer — keep the mount in the
    /// address ([ROUTER_JS]) or hand it back to the panel root
    /// ([ROUTER_ROOT_JS], named by `?mode=root` when the client's bundle cannot
    /// be patched). Served as its own resource for the same CSP reason as the
    /// runtime shim.
    static final String ROUTER_RESOURCE = "__hdsl-router.js";

    private static final int BUFFER_SIZE = 8192;

    private final HttpClient client;
    private final TaskService tasks;

    public InstanceProxyServlet(TaskService tasks) {
        this.tasks = tasks;
        // HTTP/1.1 is forced: the default HTTP/2 makes java.net send an h2c
        // upgrade probe (Upgrade: h2c), and dsh's bare node:http server —
        // which treats every Connection: Upgrade as a WebSocket candidate —
        // answers that by destroying the socket, surfacing here as a 502.
        this.client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String uri = request.getRequestURI();
        String rest = uri.startsWith("/i/") ? uri.substring("/i/".length()) : "";
        int slash = rest.indexOf('/');
        String instanceId = slash < 0 ? rest : rest.substring(0, slash);
        if (instanceId.isEmpty()) {
            Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
            return;
        }

        // `/i/<id>` — the token exchange dsh performs needs the directory
        // form, so a bare mount is a redirect rather than a proxy target.
        if (slash < 0) {
            String query = request.getQueryString();
            response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
            response.setHeader("Location", "/i/" + instanceId + "/" + (query == null ? "" : "?" + query));
            return;
        }

        // The per-mount scripts are served by the panel itself, not by the
        // instance: they have to exist before any page loads (CSP-safe, see
        // [SHIM_RESOURCE]) and never depend on the instance's state. The router
        // shim answers the same way for path-routed clients.
        String restPath = rest.substring(slash);
        if (restPath.equals("/" + SHIM_RESOURCE) || restPath.equals("/" + ROUTER_RESOURCE)) {
            boolean router = restPath.equals("/" + ROUTER_RESOURCE);
            // The router shim has two contracts (see [ROUTER_JS]): keep the mount
            // in the address bar, or — for a client whose bundle carries no
            // patchable expression — hand the address back to the panel root. The
            // page names the one it needs, because only the page knows: it probes
            // the client's bundle before it names this script.
            boolean backToRoot = router && "root".equals(request.getParameter("mode"));
            String template = router ? (backToRoot ? ROUTER_ROOT_JS : ROUTER_JS) : SHIM_JS;
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("text/javascript; charset=utf-8");
            response.setHeader("Cache-Control", "no-store");
            response.getOutputStream().write(
                    template.replace("__MOUNT__", "/i/" + instanceId + "/")
                            .getBytes(StandardCharsets.UTF_8));
            return;
        }

        DshInstance instance = DshInstanceManager.find(instanceId);
        int port;
        if (instance != null) {
            URI webUrl = DshProcessManager.find(instanceId).flatMap(DshProcess::webUrl).orElse(null);
            if (webUrl == null) {
                Json.error(response, HttpServletResponse.SC_BAD_GATEWAY, "instance not running");
                return;
            }
            port = webUrl.getPort();
            if (port <= 0) {
                port = instance.portOrDefault();
            }
        } else {
            // The experimental ZCode category binds loopback too, so it reaches
            // the browser through this same mount.
            port = InstanceProxyTargets.resolve(instanceId);
            if (port == InstanceProxyTargets.UNKNOWN) {
                Json.error(response, HttpServletResponse.SC_NOT_FOUND, "instance not found");
                return;
            }
            if (port == InstanceProxyTargets.NOT_RUNNING) {
                Json.error(response, HttpServletResponse.SC_BAD_GATEWAY, "instance not running");
                return;
            }
        }

        String targetPath = rest.substring(slash);
        if (request.getQueryString() != null) {
            targetPath += "?" + request.getQueryString();
        }
        URI target = URI.create("http://127.0.0.1:" + port + targetPath);
        String mount = "/i/" + instanceId + "/";

        // The input stream must be claimed before the request goes async.
        boolean hasBody = request.getContentLengthLong() != 0;
        InputStreamBodyPublisher body = hasBody
                ? new InputStreamBodyPublisher(request.getInputStream(), tasks) : null;

        HttpRequest.Builder builder = HttpRequest.newBuilder(target);
        copyRequestHeaders(request, builder);
        if (body == null) {
            builder.method(request.getMethod(), HttpRequest.BodyPublishers.noBody());
        } else {
            builder.method(request.getMethod(), body);
        }
        HttpRequest upstreamRequest = builder.build();

        AsyncContext async = request.startAsync();
        // SSE streams stay open indefinitely; no timeout, the client's own
        // disconnect is what ends the pump.
        async.setTimeout(0);
        CompletableFuture<HttpResponse<InputStream>> upstream = client.sendAsync(upstreamRequest,
                HttpResponse.BodyHandlers.ofInputStream());
        async.addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                upstream.cancel(true);
            }

            @Override
            public void onError(AsyncEvent event) {
                upstream.cancel(true);
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
            }
        });

        boolean routerClient = needsRouterShim(instanceId);
        int upstreamPort = port;
        upstream.whenCompleteAsync(
                (result, error) -> pump(response, async, upstream, mount, routerClient, upstreamPort, result, error),
                tasks.executor());
    }

    /// Copies the response headers and streams the body back, running on the
    /// task pool so the client's I/O thread is never blocked on a write.
    ///
    /// Two bodies are not streamed but rebuilt: a page (the mount has to be in
    /// its references — see [rewritePage]) and, for a path-routed client, the
    /// script its router lives in ([patchRouterBundle]). Everything else streams,
    /// which is what keeps `/plugins/events` (SSE) working.
    private static void pump(HttpServletResponse response, AsyncContext async,
                             CompletableFuture<HttpResponse<InputStream>> upstream, String mount,
                             boolean routerClient, int port,
                             @Nullable HttpResponse<InputStream> result, @Nullable Throwable error) {
        try {
            if (error != null || result == null) {
                org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                        "Upstream fetch failed for mount " + mount + ": " + error);
                if (!response.isCommitted()) {
                    Json.error(response, HttpServletResponse.SC_BAD_GATEWAY, "instance not running");
                }
                async.complete();
                return;
            }
            response.setStatus(result.statusCode());
            long contentLength = result.headers().firstValueAsLong("content-length").orElse(-1);
            String contentType = result.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            boolean servesPage = contentType.contains("text/html");
            // A script is only worth rebuilding when the instance sized it in
            // advance (the patch changes that length) and it is small enough to
            // hold. The probe that chose the shim read the bundle under the same
            // rule, so what the page was told and what the browser gets agree.
            boolean servesScript = routerClient && !servesPage && contentType.contains("javascript")
                    && contentLength >= 0 && contentLength <= MAX_SCRIPT_BYTES;
            copyResponseHeaders(result, response, mount, servesPage || servesScript);
            try (InputStream in = result.body(); OutputStream out = response.getOutputStream()) {
                if (servesPage) {
                    // Buffered and rewritten, because the page dsh generates is rooted at the origin
                    // rather than at whatever path it is reached through — see rewritePage.
                    String html = readPage(in, result);
                    out.write(rewritePage(html, mount,
                                    routerClient ? routerShimFor(port, mount, html) : RouterShim.NONE)
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else if (servesScript) {
                    byte[] script = readScript(in, result);
                    byte[] patched = script == null ? null : patchRouterBundle(script);
                    if (patched == null) {
                        if (script != null) {
                            out.write(script);
                        } else {
                            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                                    "Script under " + mount + " outgrew its own content-length; served unpatched");
                        }
                    } else {
                        // These bytes are no longer the instance's own and must not be kept
                        // under its address: a cached copy without the patch routes the client
                        // by the mount itself, which matches no route at all.
                        response.setHeader("Cache-Control", "no-store");
                        out.write(patched);
                    }
                    out.flush();
                } else {
                    byte[] buffer = new byte[BUFFER_SIZE];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
            }
        } catch (IOException e) {
            // The client went away, or the instance died mid-response. The
            // upstream request is cancelled either way — a half-finished
            // response has no consumer.
            upstream.cancel(true);
        } finally {
            async.complete();
        }
    }

    /// Whether the page's client needs the router shim: only the brands that
    /// route by `location.pathname` do ([BrandRuntime#brandOf] is populated
    /// when the instance launches, which is always the case for a page being
    /// served).
    private static boolean needsRouterShim(String instanceId) {
        org.jackhuang.hmcl.web.brand.Brand brand =
                org.jackhuang.hmcl.web.brand.BrandRuntime.brandOf(instanceId);
        return brand != null && brand.needsRouterShim();
    }

    /// Copies the request headers, dropping hop-by-hop names and the
    /// content-length (the streamed body has none), and passing Host through
    /// as it arrived.
    private static void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder builder) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower) || lower.equals("content-length")) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                builder.header(name, values.nextElement());
            }
        }
    }

    /// Copies the response headers, dropping hop-by-hop names, pointing a
    /// redirect back at the mount, and rewriting the Set-Cookie paths onto it.
    ///
    /// @param rewritesBody whether the caller is replacing the body, in which
    ///                     case the headers that described the old one go too
    private static void copyResponseHeaders(HttpResponse<InputStream> result, HttpServletResponse response,
                                            String mount, boolean rewritesBody) {
        for (var entry : result.headers().map().entrySet()) {
            String name = entry.getKey();
            String lower = name.toLowerCase(Locale.ROOT);
            if (HOP_BY_HOP.contains(lower)) {
                continue;
            }
            if (rewritesBody && (lower.equals("content-length") || lower.equals("content-encoding"))) {
                continue;
            }
            for (String value : entry.getValue()) {
                if (lower.equals("set-cookie")) {
                    value = COOKIE_PATH.matcher(value).replaceAll("$1Path=" + mount);
                } else if (lower.equals("location")) {
                    value = mountLocation(value, mount);
                }
                response.addHeader(name, value);
            }
        }
    }

    /// Reads a page, decompressing it when the instance sent it compressed.
    ///
    /// The page arrives gzipped and cannot be rewritten while it is, so the answer goes back
    /// uncompressed — which is why the caller drops the `content-encoding` header with it.
    private static String readPage(InputStream in, HttpResponse<InputStream> result) throws IOException {
        String encoding = result.headers().firstValue("content-encoding").orElse("").toLowerCase(Locale.ROOT);
        if (encoding.contains("gzip")) {
            try (GZIPInputStream gzip = new GZIPInputStream(in)) {
                return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        if (encoding.contains("deflate")) {
            try (InflaterInputStream deflate = new InflaterInputStream(in)) {
                return new String(deflate.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    /// The shim that keeps the page's runtime requests inside the mount.
    ///
    /// What the page asks for at runtime, the panel never sees in the HTML: the client calls
    /// `/api/...`, opens its live channel at `/api/remote.mux` and subscribes to `/plugins/events`
    /// — all as origin-absolute URLs it builds itself. Through a mount they reach the panel, which
    /// answers 404 for names it does not have, and the interface then sits at "Reconnecting…" for
    /// ever. These four interfaces are the ones a page can be made to go through another path at;
    /// they are wrapped so that a same-host URL gets the mount, and anything else is left exactly
    /// as it was. Dynamic `import()` cannot be wrapped, and does not need to be: the page's module
    /// registrations are rewritten by [rewritePage] itself.
    ///
    /// The constant is the **bare script**, without `<script>` tags: dsh's page gets it inlined
    /// (tags added at the injection site), the generic brand pages get it as the external
    /// [SHIM_RESOURCE] — serving the tagged form as a standalone script would be a syntax error
    /// and the whole shim would silently never run.
    private static final String SHIM_JS = """
            (()=>{
            const mount="__MOUNT__";
            const fix=(value)=>{if(typeof value!=="string"||!value)return value;
            if(value.startsWith("/i/"))return value;
            try{const url=new URL(value,location.href);
            if(url.host!==location.host)return value;
            if(url.pathname.startsWith("/i/"))return value;
            return url.protocol+"//"+location.host+mount+url.pathname.slice(1)+url.search+url.hash
            }catch(e){return value}};
            const f=window.fetch;
            window.fetch=function(input,init){
            if(typeof input==="string")return f.call(this,fix(input),init);
            if(input instanceof URL)return f.call(this,fix(input.href),init);
            if(input instanceof Request){const u=fix(input.url);if(u!==input.url)return f.call(this,new Request(u,input),init)}
            return f.apply(this,arguments)};
            const open=XMLHttpRequest.prototype.open;
            XMLHttpRequest.prototype.open=function(){const a=[].slice.call(arguments);a[1]=fix(String(a[1]));return open.apply(this,a)};
            const wrap=(C)=>{try{return new Proxy(C,{construct(t,a,n){if(a.length>0)a[0]=fix(String(a[0]));return Reflect.construct(t,a,n)}})}catch(e){return C}};
            window.WebSocket=wrap(window.WebSocket);
            if(window.EventSource)window.EventSource=wrap(window.EventSource)})()
            """;

    /// What a path-routed client's page is given in place of a router that works
    /// at the mount — see [ROUTER_JS] and [ROUTER_ROOT_JS].
    enum RouterShim {
        /// The client needs neither: it routes by the path it is served at, or is
        /// the origin owner itself (Kimi Code, dsh).
        NONE,
        /// The client's router takes its route from `location.pathname`. Its
        /// bundle is patched to read the mount away ([patchRouterBundle]) and
        /// [ROUTER_JS] adds the mount back to everything the client writes: the
        /// address bar keeps `/i/<id>/…` and survives a refresh.
        MOUNT,
        /// The same client, but its bundle carries no expression this servlet
        /// knows how to patch: [ROUTER_ROOT_JS] hands the address back to the
        /// panel root. The app works; the address does not survive a refresh.
        ROOT
    }

    /// The largest script this servlet will hold in memory to patch. The bundles
    /// it exists for are single files a little under 3 MB (measured 2026-10 on
    /// both OpenCode builds); anything much larger is trusted to be something
    /// else and is streamed untouched.
    private static final long MAX_SCRIPT_BYTES = 16L * 1024 * 1024;

    /// The one expression a path-routed client turns the browser's address into
    /// its own route with — Solid Router's browser-history adapter, minified but
    /// stable: the path normalised to a single leading slash, then the search
    /// string. Measured 2026-10 in OpenCode 1.18.32 and 1.18.35
    /// (`assets/index-*.js`, the file's only `window.location.pathname`; the other
    /// `location.pathname` — bare, no `window.` — belongs to the client's
    /// boot-time `auth_token` cleanup and must stay untouched, or that cleanup
    /// would drop the mount from the address it rewrites).
    private static final byte[] ROUTER_PATH_READ =
            "window.location.pathname.replace(".getBytes(StandardCharsets.US_ASCII);

    /// The same expression with the mount taken off it, so that under `/i/<id>/`
    /// the client matches the routes it would match at the origin root. The
    /// global is looked up rather than called outright: the bundle is served
    /// alongside the shim that defines it, but a bundle that ends up without one
    /// must still run rather than die on a `ReferenceError`.
    private static final byte[] ROUTER_PATH_READ_PATCHED = ("(window.__hdslUnmount"
            + "?window.__hdslUnmount(window.location.pathname):window.location.pathname)"
            + ".replace(").getBytes(StandardCharsets.US_ASCII);

    /// The client's entry script, as the page names it — what the shim decision
    /// is probed on ([routerShimFor]). The first `<script src>` naming a `.js`
    /// file is the one a single-page app boots from (measured 2026-10: both
    /// brands' pages name exactly one, OpenCode's `/assets/index-<hash>.js`).
    private static final Pattern SCRIPT_SRC = Pattern.compile("<script[^>]*\\ssrc=\"([^\"]+)\"");

    /// Which shim each mount's bundle was found to take, as `<script src>|<mode>`
    /// — remembered because a page has to name its shim before the browser ever
    /// asks for the bundle, and the answer only changes when the client's own
    /// asset name does (a different build of it). One entry per mount.
    private static final Map<String, String> ROUTER_BUNDLES = new ConcurrentHashMap<>();

    /// The client the probe fetches bundles with — separate from the proxying
    /// client, which belongs to an instance and is built per servlet.
    private static final HttpClient PROBE_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /// @return the bundle with the router's path read made mount-blind, or `null`
    ///         when the expression is not there exactly once — a build this
    ///         servlet does not recognise, which is then served untouched and
    ///         paired with [RouterShim#ROOT].
    static byte @Nullable [] patchRouterBundle(byte[] bundle) {
        int at = indexOf(bundle, ROUTER_PATH_READ, 0);
        if (at < 0 || indexOf(bundle, ROUTER_PATH_READ, at + 1) >= 0) {
            return null;
        }
        byte[] patched = new byte[bundle.length - ROUTER_PATH_READ.length + ROUTER_PATH_READ_PATCHED.length];
        System.arraycopy(bundle, 0, patched, 0, at);
        System.arraycopy(ROUTER_PATH_READ_PATCHED, 0, patched, at, ROUTER_PATH_READ_PATCHED.length);
        System.arraycopy(bundle, at + ROUTER_PATH_READ.length, patched,
                at + ROUTER_PATH_READ_PATCHED.length,
                bundle.length - at - ROUTER_PATH_READ.length);
        return patched;
    }

    /// @return the first index of `needle` in `haystack` at or after `from`, or
    ///         `-1`. Both are ASCII here, so a byte compare is the whole story.
    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /// Which router shim the page about to be served must name.
    ///
    /// The client's own bundle decides: if its router's path read can be patched
    /// the address bar keeps the mount ([RouterShim#MOUNT]); if it cannot, the
    /// page falls back to the panel root ([RouterShim#ROOT]) rather than
    /// rendering a shell. The bundle is fetched once per mount and asset name to
    /// answer this, because the shim is named *before* the browser asks for the
    /// bundle and the two still have to agree on the first load. A probe that
    /// fails says no: losing the address bar is recoverable, a page whose shim
    /// does not match its bundle is not.
    private static RouterShim routerShimFor(int port, String mount, String html) {
        String src = entryScriptSrc(html);
        if (src == null) {
            return RouterShim.ROOT;
        }
        String remembered = ROUTER_BUNDLES.get(mount);
        if (remembered != null && remembered.startsWith(src + "|")) {
            return remembered.endsWith("|mount") ? RouterShim.MOUNT : RouterShim.ROOT;
        }
        boolean patchable = probeRouterBundle(port, src);
        ROUTER_BUNDLES.put(mount, src + "|" + (patchable ? "mount" : "root"));
        return patchable ? RouterShim.MOUNT : RouterShim.ROOT;
    }

    /// @return the path of the client's entry script as the instance serves it,
    ///         or `null` when the page names none (or names one on another host,
    ///         which is not the client and cannot be patched through here).
    static @Nullable String entryScriptSrc(String html) {
        Matcher matcher = SCRIPT_SRC.matcher(html);
        while (matcher.find()) {
            String src = matcher.group(1);
            if (!src.endsWith(".js")) {
                continue;
            }
            if (src.startsWith("//") || src.contains("://")) {
                return null;
            }
            return src.startsWith("/") ? src : "/" + src;
        }
        return null;
    }

    /// @return whether the client's entry script carries the path read this
    ///         servlet patches — see [patchRouterBundle]
    private static boolean probeRouterBundle(int port, String src) {
        try {
            HttpResponse<InputStream> response = PROBE_CLIENT.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + src))
                            .timeout(Duration.ofSeconds(20))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                long length = response.headers().firstValueAsLong("content-length").orElse(-1);
                if (response.statusCode() != 200 || length < 0 || length > MAX_SCRIPT_BYTES) {
                    return false;
                }
                byte[] bundle = readBytes(in, MAX_SCRIPT_BYTES);
                return bundle != null && patchRouterBundle(bundle) != null;
            }
        } catch (IOException | InterruptedException e) {
            org.jackhuang.hmcl.util.logging.Logger.LOG.warning(
                    "Could not probe the router bundle at " + src + ": " + e);
            return false;
        }
    }

    /// Reads a script response into memory, decompressing it when the instance
    /// sent it compressed: the patch is matched against the bytes the browser
    /// would run, and a compressed body is not those bytes. The caller drops
    /// `content-encoding` with it — see [copyResponseHeaders].
    ///
    /// @return the bytes, or `null` when the response holds more than
    ///         [MAX_SCRIPT_BYTES] after all
    private static byte @Nullable [] readScript(InputStream in, HttpResponse<InputStream> result) throws IOException {
        String encoding = result.headers().firstValue("content-encoding").orElse("").toLowerCase(Locale.ROOT);
        if (encoding.contains("gzip")) {
            try (GZIPInputStream gzip = new GZIPInputStream(in)) {
                return readBytes(gzip, MAX_SCRIPT_BYTES);
            }
        }
        if (encoding.contains("deflate")) {
            try (InflaterInputStream deflate = new InflaterInputStream(in)) {
                return readBytes(deflate, MAX_SCRIPT_BYTES);
            }
        }
        return readBytes(in, MAX_SCRIPT_BYTES);
    }

    /// @return the bytes of a stream, or `null` once it holds more than `cap`
    private static byte @Nullable [] readBytes(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(chunk)) >= 0) {
            total += read;
            if (total > cap) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    /// The shim a path-routed client's page gets: **the client reads the mount
    /// away, and everything it writes back gets the mount added**.
    /// ([org.jackhuang.hmcl.web.brand.Brand#needsRouterShim] — OpenCode.)
    ///
    /// Served through the mount, such a client matches no route and renders a
    /// bare shell (measured: the same page at any subpath of its own port does
    /// the same, so this is the client, not the proxy). Its router takes the route
    /// straight from the browser (`location.pathname`), and that read is what
    /// [patchRouterBundle] makes mount-blind from the other side — the two halves
    /// are one mechanism and have to agree:
    ///
    /// - `window.__hdslUnmount` is what the patched bundle calls; it lives here
    ///   rather than inside the bundle so that one bundle can serve every mount;
    /// - `history.pushState` / `replaceState` get the mount, and are not prefixed
    ///   twice when the client hands back an address it read itself.
    ///
    /// What that buys over [ROUTER_ROOT_JS]: the address bar keeps `/i/<id>/…`,
    /// a refresh re-enters the app where it was (the client's own server answers
    /// an unknown path with its index page), and the address stays shareable.
    private static final String ROUTER_JS = """
            (()=>{
            const mount="__MOUNT__";
            window.__hdslUnmount=(path)=>path.indexOf(mount)===0?path.slice(mount.length-1):path;
            const fix=(value)=>{if(typeof value!=="string"||!value)return value;
            if(value.indexOf(mount)===0)return value;
            if(value.charAt(0)==="#"||value.charAt(0)==="?")return value;
            try{const url=new URL(value,location.href);
            if(url.host!==location.host)return value;
            if(url.pathname.indexOf(mount)===0)return value;
            return mount+url.pathname.replace(/^\\/+/,"")+url.search+url.hash
            }catch(e){return value}};
            const push=history.pushState,repl=history.replaceState;
            const wrap=(fn)=>function(state,title,url){try{return fn.call(history,state,title,fix(url))}catch(e){}};
            history.pushState=wrap(push);
            history.replaceState=wrap(repl);
            })()
            """;

    /// The shim for a path-routed client whose bundle could not be patched — the
    /// fallback ([RouterShim#ROOT]). The script runs before the app bundle and
    /// makes the browser's address look like the origin root the client expects:
    ///
    /// - the initial `/i/<id>/…` address is replaced with `/` (hash kept), so the
    ///   first route match is the root view;
    /// - `history.pushState` / `replaceState` are swallowed down to `/` as well,
    ///   so later addresses the app writes never re-expose the mount in
    ///   `location.pathname` — the router keeps matching what it was given.
    ///
    /// Everything the app *requests* still goes through the mount, because the
    /// runtime shim ([SHIM_JS]) prefixes same-origin absolute URLs. The trade-off
    /// is the one [ROUTER_JS] exists to remove: the tab's address bar shows the
    /// panel root, and a manual refresh lands on the panel instead of the app.
    /// `__hdslUnmount` is defined here too — a bundle patched by one panel build
    /// and not by the next must not be the difference between an app and a
    /// `ReferenceError`.
    private static final String ROUTER_ROOT_JS = """
            (()=>{
            const mount="__MOUNT__";
            window.__hdslUnmount=(path)=>path.indexOf(mount)===0?path.slice(mount.length-1):path;
            const root=()=>"/"+location.hash;
            try{if(location.pathname.indexOf(mount)===0)history.replaceState(history.state,"",root())}catch(e){}
            const push=history.pushState,repl=history.replaceState;
            const swallow=(fn)=>function(state,title,url){try{return fn.call(history,state,title,root())}catch(e){}};
            history.pushState=swallow(push);
            history.replaceState=swallow(repl);
            })()
            """;

    /// Points a page the instance generated at the mount it is reached through.
    ///
    /// Two shapes of page arrive here:
    ///
    /// - **dsh's page** (the original case): it opens with `<base href="/">`
    ///   and registers its client plugins as absolute `/plugins/...` URLs
    ///   (fifty-two of them, inside the inline `__DSH_BOOT__`). Handled by the
    ///   exact replacements below, unchanged.
    /// - **Any other single-page app** (the third-party brand categories — Kimi
    ///   Code, OpenCode): no `<base>` tag, but root-absolute asset references
    ///   (`src="/boot.js"`, `href="/assets/…"`). Every `(src|href|action)="/…"`
    ///   reference gets the mount as a prefix, and the runtime shim is injected
    ///   right after `<head>` so the URLs the app builds at runtime
    ///   (`fetch("/api/…")`, WebSocket, EventSource) stay inside the mount too.
    ///   A page that routes by path gets the router shim named by `shim` after
    ///   it, and its own bundle patched on the way past
    ///   ([patchRouterBundle]) — one half without the other only gets the app
    ///   as far as a shell.
    ///
    /// A page carrying neither shape is passed through untouched.
    ///
    /// @param html  the page as the instance generated it
    /// @param mount the mount, with its trailing slash
    /// @return the page as the browser has to see it
    static String rewritePage(String html, String mount) {
        return rewritePage(html, mount, RouterShim.NONE);
    }

    /// The same, for a client that routes by `location.pathname`: `shim` says
    /// which half of [ROUTER_JS] its page must name.
    static String rewritePage(String html, String mount, RouterShim shim) {
        if (html.contains("<base href=\"/\">")) {
            return html.replace("<base href=\"/\">",
                            "<base href=\"" + mount + "\">"
                                    + "<script>" + SHIM_JS.replace("__MOUNT__", mount) + "</script>")
                    .replace("=\"/plugins/", "=\"" + mount + "plugins/")
                    .replace(":\"/plugins/", ":\"" + mount + "plugins/")
                    // A manifest is fetched without credentials, so through the mount it is a 401 and
                    // nothing else — and an app served under someone else's path could not be installed
                    // from it anyway.
                    .replace("<link rel=\"manifest\" href=\"./manifest.webmanifest\" />", "");
        }
        if (hasRootAbsoluteReferences(html)) {
            // Rewrite the references first, then reference the shim — the
            // injected tag's src is mount-absolute already and must not be
            // rewritten a second time.
            String prefixed = html
                    .replace("src=\"/", "src=\"" + mount)
                    .replace("href=\"/", "href=\"" + mount)
                    .replace("action=\"/", "action=\"" + mount);
            // Same rule as dsh's manifest: the browser fetches a manifest
            // without credentials, so through the mount it is a 401 from the
            // session gate — nothing else — and an app served under someone
            // else's path could not be installed from it anyway. (Measured
            // 2026-10 on OpenCode: two 401s per page load.)
            return injectShim(prefixed, mount, shim)
                    .replaceAll("<link rel=\"manifest\"[^>]*>", "");
        }
        return html;
    }

    /// Whether the page references anything from the origin root — the signal
    /// that it assumes it owns the origin and needs the generic rewrite.
    private static boolean hasRootAbsoluteReferences(String html) {
        return html.contains("src=\"/") || html.contains("href=\"/") || html.contains("action=\"/");
    }

    /// Injects a reference to the per-mount shim (see [SHIM_RESOURCE]) as the
    /// first thing inside `<head>` (falling back to prepending it), so it runs
    /// before any module script the page loads. The shim itself is a separate
    /// same-origin resource rather than an inline script: OpenCode's CSP
    /// (`script-src 'self'`) discards inline scripts, and a shim that never
    /// runs is worse than none — the failure is silent.
    ///
    /// The router shim follows it, naming the contract it is wanted under: the
    /// same resource serves both (see [ROUTER_JS] and [ROUTER_ROOT_JS]).
    private static String injectShim(String html, String mount, RouterShim shim) {
        String tag = "<script src=\"" + mount + SHIM_RESOURCE + "\"></script>";
        if (shim != RouterShim.NONE) {
            tag = tag + "<script src=\"" + mount + ROUTER_RESOURCE
                    + (shim == RouterShim.ROOT ? "?mode=root" : "") + "\"></script>";
        }
        int head = indexOfIgnoreCase(html, "<head>");
        if (head >= 0) {
            int after = head + "<head>".length();
            return html.substring(0, after) + tag + html.substring(after);
        }
        return tag + html;
    }

    /// A case-insensitive indexOf for the literal `<head>` opening tag.
    private static int indexOfIgnoreCase(String html, String needle) {
        return html.toLowerCase(Locale.ROOT).indexOf(needle);
    }

    /// Points a redirect back at the mount.
    ///
    /// dsh answers the token exchange with `303 Location: /`, and the browser that follows it
    /// leaves the mount for the panel's own home page — which is what "the button just refreshes"
    /// was. A location naming the instance's own loopback port is rewritten the same way, because
    /// that address is not one the browser can reach.
    ///
    /// @param value the upstream location
    /// @param mount the mount, with its trailing slash
    /// @return the location the browser has to be sent to
    private static String mountLocation(String value, String mount) {
        if (value.startsWith("/")) {
            return mount + value.substring(1);
        }
        try {
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (host != null && (host.equals("127.0.0.1") || host.equals("localhost") || host.equals("::1"))) {
                String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                return mount + (path.startsWith("/") ? path.substring(1) : path)
                        + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
            }
        } catch (IllegalArgumentException e) {
            // Not a location this understands: passed through as it arrived.
        }
        return value;
    }

    /// A request body publisher that pumps the servlet input stream into the
    /// upstream request, one chunk at a time, with backpressure from the
    /// client's demand.
    private static final class InputStreamBodyPublisher implements HttpRequest.BodyPublisher {
        private final SubmissionPublisher<ByteBuffer> publisher = new SubmissionPublisher<>();
        private final InputStream input;
        private final TaskService tasks;
        private final AtomicBoolean started = new AtomicBoolean();

        private InputStreamBodyPublisher(InputStream input, TaskService tasks) {
            this.input = input;
            this.tasks = tasks;
        }

        @Override
        public long contentLength() {
            return -1;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
            // The pump starts only once a subscriber exists: SubmissionPublisher
            // drops items submitted with nobody subscribed, which is what lost
            // the first chunks when the pump ran ahead of sendAsync.
            publisher.subscribe(subscriber);
            start();
        }

        /// Starts the pump thread once; the guard makes the subscribe-then-start
        /// order harmless either way around.
        private void start() {
            if (started.compareAndSet(false, true)) {
                tasks.execute(this::pump);
            }
        }

        private void pump() {
            try (InputStream in = input) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    ByteBuffer chunk = ByteBuffer.allocate(read).put(buffer, 0, read).flip();
                    publisher.submit(chunk);
                }
                publisher.close();
            } catch (IOException e) {
                publisher.closeExceptionally(e);
            }
        }
    }
}
