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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// The generic half of the page rewrite: single-page apps that assume they own
/// the origin (root-absolute asset references, no `<base>` tag) — the Kimi Code
/// and OpenCode web UIs — must come out of the rewrite pointing at the mount,
/// with the runtime shim installed before any module script runs. The fixtures
/// are the real index pages those tools served, captured during the brand
/// integration spike.
class GenericPageRewriteTest {

    private static final String MOUNT = "/i/demo123/";

    @Test
    void kimiIndexGetsTheMountAndTheShim() {
        String rewritten = rewrite(resource("brand-pages/kimi-index.html"));

        assertTrue(rewritten.contains("src=\"" + MOUNT + "boot.js\""), rewritten);
        assertTrue(rewritten.contains("src=\"" + MOUNT + "assets/index-CiJ6FDOC.js\""), rewritten);
        assertTrue(rewritten.contains("href=\"" + MOUNT + "assets/index-zbwnJKhO.css\""), rewritten);
        assertTrue(rewritten.contains("href=\"" + MOUNT + "favicon.ico\""), rewritten);
        assertShim(rewritten);
        assertNoDoublePrefix(rewritten);
    }

    @Test
    void opencodeIndexGetsTheMountAndBothShims() {
        // Path-routed clients get the router shim; the proxy is told so by the
        // brand flag at serve time (see InstanceProxyServlet#needsRouterShim).
        String rewritten = InstanceProxyServlet.rewritePage(
                resource("brand-pages/opencode-index.html"), MOUNT, true);

        assertTrue(rewritten.contains("src=\"" + MOUNT + "assets/index-CjbuCoME.js\""), rewritten);
        assertTrue(rewritten.contains("href=\"" + MOUNT + "assets/index-DLiUNAg_.css\""), rewritten);
        assertTrue(rewritten.contains("href=\"" + MOUNT + "favicon-v3.svg\""), rewritten);
        // The manifest is stripped like dsh's: the browser fetches it without
        // credentials, so through the mount it is a 401 from the session gate
        // and nothing else (measured: two 401s per OpenCode page load).
        assertFalse(rewritten.contains("site.webmanifest"), rewritten);
        assertShim(rewritten);
        assertRouterShim(rewritten);
        assertNoDoublePrefix(rewritten);
    }

    @Test
    void shimIsInjectedInsideHeadBeforeAnyAsset() {
        String rewritten = rewrite(resource("brand-pages/kimi-index.html"));
        String tag = "<script src=\"" + MOUNT + InstanceProxyServlet.SHIM_RESOURCE + "\"></script>";
        int head = rewritten.indexOf("<head>");
        int shim = rewritten.indexOf(tag);
        int firstAsset = rewritten.indexOf("src=\"" + MOUNT);
        assertTrue(head >= 0 && shim > head, "shim inside <head>: " + rewritten);
        assertTrue(shim < firstAsset, "shim before the first rewritten asset");
    }

    @Test
    void dshShapedPageKeepsItsExactTreatment() {
        String dsh = "<html><head><base href=\"/\">"
                + "<script src=\"/plugins/a/client.js\"></script></head><body></body></html>";
        String rewritten = InstanceProxyServlet.rewritePage(dsh, MOUNT);

        assertTrue(rewritten.contains("<base href=\"" + MOUNT + "\">"), rewritten);
        assertTrue(rewritten.contains("src=\"" + MOUNT + "plugins/a/client.js\""), rewritten);
        // The dsh branch predates the CSP finding and inlines the shim, which
        // dsh's responses (no CSP) accept; the generic branch externalises it.
        assertTrue(rewritten.contains("const mount=\"" + MOUNT + "\""), rewritten);
    }

    @Test
    void aRelativeOnlyPageIsUntouched() {
        String plain = "<html><head><script src=\"./app.js\"></script></head>"
                + "<body><a href=\"page\">x</a></body></html>";
        assertEquals(plain, InstanceProxyServlet.rewritePage(plain, MOUNT));
    }

    @Test
    void theRouterShimIsReferencedOnlyWhenAsked(@TempDir Path dataDir) {
        String page = "<html><head><script src=\"/assets/a.js\"></script></head></html>";

        assertFalse(InstanceProxyServlet.rewritePage(page, MOUNT, true)
                .contains(InstanceProxyServlet.ROUTER_RESOURCE + "\"></script>false"));
        assertTrue(InstanceProxyServlet.rewritePage(page, MOUNT, true)
                .contains(InstanceProxyServlet.ROUTER_RESOURCE));
        assertFalse(InstanceProxyServlet.rewritePage(page, MOUNT, false)
                .contains(InstanceProxyServlet.ROUTER_RESOURCE));
    }

    @Test
    void theShimResourceIsServedByThePanelItself(@TempDir Path dataDir) throws Exception {
        // No instance named "whatever" exists — the endpoint must still answer,
        // because the page referencing it is only ever served alongside a
        // running instance, and CSP pages need the script before anything else.
        try (org.jackhuang.hmcl.web.TestSupport.RunningServer running =
                     org.jackhuang.hmcl.web.TestSupport.start(dataDir, java.util.Map.of(), config -> {
                         config.bindHost = "127.0.0.1";
                         config.auth.disabled = true;
                     })) {
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    running.baseUrl() + "/i/whatever/" + InstanceProxyServlet.SHIM_RESOURCE))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode(), response.body());
            assertTrue(response.headers().firstValue("Content-Type").orElse("")
                    .contains("javascript"), response.headers().toString());
            assertTrue(response.body().contains("const mount=\"/i/whatever/\""), response.body());

            java.net.http.HttpResponse<String> router = java.net.http.HttpClient.newHttpClient().send(
                    java.net.http.HttpRequest.newBuilder(java.net.URI.create(
                                    running.baseUrl() + "/i/whatever/" + InstanceProxyServlet.ROUTER_RESOURCE))
                            .GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            assertEquals(200, router.statusCode(), router.body());
            assertTrue(router.body().contains("history.pushState=swallow(push)"), router.body());
        }
    }

    private static String rewrite(String html) {
        return InstanceProxyServlet.rewritePage(html, MOUNT);
    }

    /// The router shim is referenced for path-routed clients only — see
    /// [InstanceProxyServlet#rewritePage] and the OpenCode brand flag.
    private static void assertRouterShim(String rewritten) {
        assertTrue(rewritten.contains(
                "<script src=\"" + MOUNT + InstanceProxyServlet.ROUTER_RESOURCE + "\"></script>"),
                "router shim referenced: " + rewritten);
    }

    private static void assertShim(String rewritten) {
        // The generic rewrite references the per-mount shim as an external
        // same-origin script (CSP-safe), it does not inline it.
        assertTrue(rewritten.contains(
                "<script src=\"" + MOUNT + InstanceProxyServlet.SHIM_RESOURCE + "\"></script>"),
                "shim script referenced: " + rewritten);
    }

    /// The mount prefix must appear exactly once per rewritten reference —
    /// a double prefix would mean the rewrite ran twice or matched its own
    /// output.
    private static void assertNoDoublePrefix(String rewritten) {
        assertFalse(rewritten.contains(MOUNT + MOUNT.substring(1)),
                "no double mount prefix: " + rewritten);
    }

    private static String resource(String name) {
        try (var stream = GenericPageRewriteTest.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) {
                throw new IllegalStateException("missing test resource " + name);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
