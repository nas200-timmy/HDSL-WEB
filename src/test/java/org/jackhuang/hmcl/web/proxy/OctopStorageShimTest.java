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

import static org.junit.jupiter.api.Assertions.assertTrue;

/// Octop 上游按单租户设计，几十个存储键不分实例。逐键打补丁必然漏，所以面板
/// 在页面里注入一层 `Storage.prototype` 的挂载前缀，一次覆盖所有键。
class OctopStorageShimTest {

    private static final String PAGE = "<html><head><title>octop</title></head>"
            + "<body><script src=\"/assets/index.js\"></script></body></html>";

    @Test
    void thePageCarriesTheMountAndTheStorageNamespace() {
        String rewritten = InstanceProxyServlet.rewriteOctopPage(PAGE, "/i/abc/");
        assertTrue(rewritten.contains("window.__OCTOP_BASE__=\"/i/abc/\""), "先告诉页面挂载点");
        assertTrue(rewritten.contains("Storage.prototype.setItem"), "存储写路径被分区");
        assertTrue(rewritten.contains("Storage.prototype.getItem"), "存储读路径被分区");
        assertTrue(rewritten.contains("Storage.prototype.removeItem"), "存储删路径被分区");
        assertTrue(rewritten.contains("const prefix=\"octop:\"+mount+\":\""), "分区前缀来自挂载");
    }

    @Test
    void theShimRunsBeforeTheAppBundle() {
        String rewritten = InstanceProxyServlet.rewriteOctopPage(PAGE, "/i/abc/");
        int base = rewritten.indexOf("__OCTOP_BASE__");
        int shim = rewritten.indexOf("Storage.prototype.setItem");
        int bundle = rewritten.indexOf("/assets/index.js");
        assertTrue(base >= 0 && shim > base && bundle > shim,
                "挂载点 → 存储分区 → 应用 bundle，顺序不能反，否则先写的键没人分区");
    }

    @Test
    void anAlreadyScopedKeyIsNotPrefixedTwice() {
        String rewritten = InstanceProxyServlet.rewriteOctopPage(PAGE, "/i/abc/");
        assertTrue(rewritten.contains("if(key.indexOf(prefix)===0)return key"),
                "0001 补丁里已分区的键必须原样放行");
    }
}
