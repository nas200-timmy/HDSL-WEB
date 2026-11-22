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

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Kimi Code 2.7 的 web 端按 `location.pathname` 路由：读是
/// `return{pathname:kAe(window.location.pathname)}`，写是
/// `history.pushState(null,"","/sessions/<id>")`。不给 router shim 时，点一下会话
/// 地址栏就变成面板根路径的 `/sessions/<id>`，`/i/<id>/` 也随之不显示。
/// 这组用例钉住"读处剥挂载"的补丁形状和它的拒绝条件。
class KimiRouterPatchTest {

    private static final String KIMI_BUNDLE =
            "function f3(){return{pathname:kAe(window.location.pathname)}}"
                    + "function qat(e,t){return f3().pathname}";

    @Test
    void kimiNeedsTheRouterShimNow() {
        assertTrue(org.jackhuang.hmcl.web.brand.Brand.KIMI.needsRouterShim(),
                "Kimi 2.7 按路径路由，必须注入写处补挂载的 router shim");
    }

    @Test
    void theSingleNormalizerCallIsRecognisedAndUnmounted() {
        assertTrue(InstanceProxyServlet.hasRouterPathRead(KIMI_BUNDLE));
        String patched = new String(
                InstanceProxyServlet.patchBrandScript(KIMI_BUNDLE.getBytes(StandardCharsets.UTF_8), "/i/abc/"),
                StandardCharsets.UTF_8);
        assertTrue(patched.contains(
                        "kAe(window.__hdslUnmount?window.__hdslUnmount(window.location.pathname)"
                                + ":window.location.pathname)"),
                "路由读要先剥掉挂载再交给归一化函数: " + patched);
    }

    @Test
    void twoPathReadsAreNotGuessedAt() {
        String bundle = KIMI_BUNDLE + "var other = String(window.location.pathname);";
        assertFalse(InstanceProxyServlet.hasRouterPathRead(bundle),
                "两处以上就读不准哪处是路由，必须退回 ROOT 而不是乱改");
    }

    @Test
    void aBundleWithoutAnyPathReadIsLeftAlone() {
        assertFalse(InstanceProxyServlet.hasRouterPathRead("var x = 1;"));
    }

    @Test
    void theSolidRouterPatchStillWinsWhenPresent() {
        String solid = "const p = window.location.pathname.replace(/\\\\/g, '/');";
        assertTrue(InstanceProxyServlet.hasRouterPathRead(solid));
        String patched = new String(
                InstanceProxyServlet.patchBrandScript(solid.getBytes(StandardCharsets.UTF_8), "/i/abc/"),
                StandardCharsets.UTF_8);
        assertTrue(patched.contains("window.__hdslUnmount(window.location.pathname)"),
                "OpenCode 的 Solid 读法保持原有补丁形状: " + patched);
        assertFalse(patched.contains("kAe("), "不引入别的形状的补丁");
    }
}
