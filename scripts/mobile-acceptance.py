#!/usr/bin/env python3
"""移动端信息架构验收（headless Chromium 390x844）+ 桌面版式回归。

钉死的规则（回归时直接跑）：
  ① 实例列表页首屏可见 dsh 实例行 >= 3
  ② 第一个实例行的启动按钮零滚动可达（在视口内）
  ③ 实例列表页**不再含品牌区**：无折叠摘要卡、无品牌安装行、
     工具栏里没有品牌页签；品牌入口是子侧栏的 chip
  ④ 品牌实例页（/brands/<品牌>）：版本卡折叠 <= 90px、实例卡吃掉剩余高度（>= 400px）、
     首行实例的启动按钮零滚动可达——手机上一屏能完成「看实例 + 启动」
  ⑤ /instances?brand=x 旧链接重定向到 /brands/x；/brands/<非法> 落到有实例的品牌
另有：行内版本选择器（已装/未装标记）、长说明一行降级、插件弹窗（真调后端）、
主页品牌卡跟随启动目标与其「管理」跳转、桌面端与桌面缩窄（细指针）两种路径。

跑法（需要本机 python playwright 与一个可登录的实例）：
  pip install playwright && playwright install chromium
  # 起一个有数据的 jar（建议 >=4 个 dsh 实例 + 1 个 kimi 实例）：
  HDSL_DATA=/tmp/data HDSL_PORT=3920 java -jar build/libs/hdsl-web-<ver>.jar &
  HDSL_BASE=http://127.0.0.1:3920 HDSL_USER=admin HDSL_PASS=*** HDSL_KIMI_ID=<kimi 实例 id> \
      python3 scripts/mobile-acceptance.py

不设 `HDSL_KIMI_ID` 就跳过所有与品牌实例相关的检查（6/6a/7/7b/7c）；真拉起实例的 7/7a
在**跑脚本的机器自己就是 kimi 进程**时需要留意（面板实例与 agent 同名）。

脚本用 web/dist 起静态服务并把 /api 反代到 HDSL_BASE——所以它验的是**当前工作区
的 dist**：先 `cd web && ./node_modules/.bin/vite build`（或整包 gradle build）。
静态服务必须像真服务器那样注入 `<base href="/">`（见 SpaHandler）：前端用相对路径发
API 请求，缺了它 `/brands/kimi` 这类两级深链会把 `api/…` 解析到 `/brands/api/…`。
"""
import json
import os
import threading
import http.server
import socketserver

from playwright.sync_api import sync_playwright

DIST = os.path.join(os.path.dirname(__file__), "..", "web", "dist")
API = os.environ.get("HDSL_BASE", "http://127.0.0.1:3920")
USER = os.environ.get("HDSL_USER", "admin")
PASS = os.environ.get("HDSL_PASS", "testpass123")
KIMI_ID = os.environ.get("HDSL_KIMI_ID", "")  # 预建 kimi 实例 id；为空则跳过实例相关检查
BASE = "http://127.0.0.1:3910"


class SpaHandler(http.server.SimpleHTTPRequestHandler):
    """web/dist 的静态服务，只为验收脚本自足。

    必须像真服务器（`StaticServlet.indexHtml`）那样往 index.html 里注入
    `<base href="/">` + `window.__HDSL_BASE__`：前端用**相对**路径发 API 请求
    （见 `web/src/base.ts`），没有 base 标签时 `/brands/kimi` 这类两级深链会把
    `api/…` 解析成 `/brands/api/…`，页面看起来「一个实例都没有」。"""

    def __init__(self, *a, **kw):
        super().__init__(*a, directory=DIST, **kw)

    def send_head(self):
        import os.path
        p = os.path.join(DIST, self.path.lstrip("/").split("?")[0])
        if self.path != "/" and not os.path.isfile(p):
            self.path = "/index.html"
        if self.path.split("?")[0] in ("/", "/index.html"):
            with open(os.path.join(DIST, "index.html"), encoding="utf-8") as f:
                html = f.read()
            html = html.replace(
                "<head>",
                '<head>\n  <base href="/">\n  <script>window.__HDSL_BASE__="";</script>',
                1,
            )
            body = html.encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return None
        return super().send_head()

    def log_message(self, *a):
        pass


class Q(socketserver.TCPServer):
    allow_reuse_address = True


srv = Q(("127.0.0.1", 3910), SpaHandler)
threading.Thread(target=srv.serve_forever, daemon=True).start()

results = []


def check(name, cond, detail=""):
    results.append((name, bool(cond)))
    print(("PASS " if cond else "FAIL ") + name + (f"  [{detail}]" if detail else ""))


def route_api(route, request):
    if request.url.startswith(BASE + "/api/"):
        route.continue_(url=request.url.replace(BASE, API, 1))
    else:
        route.continue_()


def box(pg, selector):
    return pg.evaluate(
        """(s) => { const e = document.querySelector(s); if (!e) return null;
             const r = e.getBoundingClientRect();
             return {h: Math.round(r.height), top: Math.round(r.top), bottom: Math.round(r.bottom)}; }""",
        selector,
    )


with sync_playwright() as p:
    # ------------------------------------------------------ 移动端：实例列表页 --
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 390, "height": 844}, has_touch=True)
    pg = ctx.new_page()
    pg.route("**/*", route_api)
    ctx.request.post(API + "/api/auth/login", data={"username": USER, "password": PASS})

    pg.goto(BASE + "/instances")
    pg.wait_for_timeout(1800)

    vis = pg.evaluate("""() => {
      const vh = window.innerHeight;
      const rows = [...document.querySelectorAll('.list-body')][0]?.querySelectorAll('.tlli') ?? [];
      return [...rows].filter(el => { const r = el.getBoundingClientRect(); return r.bottom > 0 && r.top < vh; }).length;
    }""")
    check("1 首屏可见 dsh 实例行 >= 3", vis >= 3, f"visible={vis}")

    btn = pg.evaluate("""() => {
      const row = [...document.querySelectorAll('.list-body')][0]?.querySelector('.tlli');
      if (!row) return null;
      const b = row.querySelector('.row-actions button').getBoundingClientRect();
      return {top: b.top, bottom: b.bottom, vh: window.innerHeight};
    }""")
    check("2 首行启动按钮零滚动可达", btn and btn["top"] >= 0 and btn["bottom"] <= btn["vh"], json.dumps(btn))

    leftovers = pg.evaluate("""() => ({
      summary: !!document.querySelector('.brand-summary'),
      installRow: !!document.querySelector('.filter-bar'),
      brandTabs: [...document.querySelectorAll('.toolbar-row button')]
        .map(b => b.textContent.trim()).filter(t => /ZCode|Kimi Code|OpenCode|Claude Web/.test(t)),
      brandEntry: [...document.querySelectorAll('.sidebar-sub .side-item')]
        .some(e => e.textContent.includes('品牌实例')),
    })""")
    check("3 实例列表页无品牌残留 + 子侧栏有品牌入口",
          not leftovers["summary"] and not leftovers["installRow"] and not leftovers["brandTabs"]
          and leftovers["brandEntry"], json.dumps(leftovers, ensure_ascii=False))
    pg.screenshot(path="/tmp/acc-mobile-instances.png")

    # ------------------------------------------------------ 移动端：品牌实例页 --
    pg.get_by_role("button", name="品牌实例").first.click()
    pg.wait_for_timeout(1800)
    check("4 点侧栏入口进入品牌实例页", "/brands/" in pg.url, pg.url)

    chips = pg.evaluate("""() => {
      const nav = document.querySelector('.sidebar-sub');
      return {dir: getComputedStyle(nav).flexDirection,
              labels: [...nav.querySelectorAll('.side-item')].map(e => e.textContent.replace(/\\s+/g, ' ').trim())};
    }""")
    check("4a 品牌列表在手机上变横向 chip 条（4 个品牌）",
          chips["dir"] == "row" and len(chips["labels"]) == 4, json.dumps(chips, ensure_ascii=False))

    heights = pg.evaluate("""() => {
      const pc = document.querySelector('.page-content');
      return [...pc.children].map(c => ({cls: c.className, h: Math.round(c.getBoundingClientRect().height)}));
    }""")
    # 子元素顺序：[0] 警示条 [1] 版本卡（折叠）[2] 实例卡（吃剩余高度）
    version_h = heights[1]["h"] if len(heights) > 2 else -1
    instances_h = heights[2]["h"] if len(heights) > 2 else -1
    check("4b 版本卡折叠 <= 90px", 0 < version_h <= 90, json.dumps(heights, ensure_ascii=False))
    check("4c 实例卡吃剩余高度 >= 400px", instances_h >= 400, f"instances={instances_h}")

    inst_btn = pg.evaluate("""() => {
      const row = document.querySelector('.list-body .tlli');
      if (!row) return null;
      const b = [...row.querySelectorAll('button')][0].getBoundingClientRect();
      return {top: Math.round(b.top), bottom: Math.round(b.bottom), vh: window.innerHeight};
    }""")
    check("4d 品牌实例行首个动作按钮零滚动可达",
          inst_btn is None or (inst_btn["top"] >= 0 and inst_btn["bottom"] <= inst_btn["vh"]),
          json.dumps(inst_btn))

    ver_opts = pg.evaluate("""() => {
      const row = document.querySelector('.list-body .tlli');
      return row ? { hasSelect: !!row.querySelector('select') } : null;
    }""")
    # zcode 是无版本概念的品类（发行包检测 + 构建），实例行不该有版本选择器；
    # 带版本目录的品牌（kimi/opencode/claude-web）那条断言在 6a，同样只读真后端。
    check("4e zcode 实例行没有版本选择器（无版本概念）",
          ver_opts is None or ver_opts["hasSelect"] is False, json.dumps(ver_opts))
    no_page_scroll = pg.evaluate("() => document.documentElement.scrollHeight <= window.innerHeight + 1")
    check("4f 品牌页无页面级滚动（内部滚动）", no_page_scroll)
    pg.screenshot(path="/tmp/acc-mobile-brands.png")

    # zcode 分支：长说明降级 +
    pg.goto(BASE + "/brands/zcode")
    pg.wait_for_timeout(1800)
    note = pg.evaluate("""() => {
      const nc = document.querySelector('.note-collapse');
      return nc ? {h: Math.round(nc.getBoundingClientRect().height)} : null;
    }""")
    check("5 长说明折叠为一行 + 详情按钮（zcode 分支）", note and note["h"] <= 48, json.dumps(note))
    zheights = pg.evaluate("""() => [...document.querySelector('.page-content').children]
        .map(c => Math.round(c.getBoundingClientRect().height))""")
    check("5a zcode 品牌页同为「版本卡折叠 + 实例卡吃高度」",
          len(zheights) >= 3 and zheights[1] <= 90 and zheights[2] >= 400, json.dumps(zheights))

    # 深链与旧链接
    pg.goto(BASE + "/brands/nope")
    pg.wait_for_timeout(1500)
    check("5b /brands/<非法> 落到有实例的品牌", "/brands/nope" not in pg.url, pg.url)
    pg.goto(BASE + "/instances?brand=kimi")
    pg.wait_for_timeout(1500)
    check("5c /instances?brand=kimi 重定向到 /brands/kimi", pg.url.endswith("/brands/kimi"), pg.url)

    if KIMI_ID:
        # 插件弹窗：真调后端（通道预留，supported=false）——只读，不碰进程
        pg.goto(BASE + "/brands/kimi")
        pg.wait_for_timeout(1800)
        pg.evaluate("""() => { const r = document.querySelector('.list-body .tlli');
            const btns = [...r.querySelectorAll('button')]; btns[btns.length - 1].click(); }""")
        pg.wait_for_timeout(600)
        pg.get_by_text("插件", exact=True).last.click()
        pg.wait_for_timeout(1200)
        dlg = pg.evaluate("""() => { const d = [...document.querySelectorAll('[role=dialog]')].pop();
            return d ? d.textContent.replace(/\\s+/g, ' ').trim() : null; }""")
        check("6 插件弹窗按后端响应渲染（暂不支持 + 通道预留）",
              dlg and ("暂不支持插件" in dlg) and ("预留" in dlg), str(dlg)[:60])
        pg.keyboard.press("Escape")
        pg.wait_for_timeout(400)

        # 行内版本选择器（全量目录 + 已装/未装标记）：只读
        ver_opts = pg.evaluate("""() => {
          const sel = document.querySelector('.list-body .tlli select');
          return sel ? [...sel.options].slice(0, 4).map(o => o.textContent.trim()) : null;
        }""")
        check("6a 行内版本选择器列出全量目录并标记已装/未装",
              ver_opts is not None
              and any("已装" in o for o in ver_opts) and any("未装" in o for o in ver_opts),
              json.dumps(ver_opts, ensure_ascii=False))

        # 主页品牌卡跟随启动目标 + 「管理」跳转（只读导航）
        pg.evaluate(f"() => localStorage.setItem('hdsl.instance.selected', '{KIMI_ID}')")
        pg.goto(BASE + "/")
        pg.wait_for_timeout(2000)
        card = pg.evaluate("""() => document.querySelector('.mobile-home')?.textContent.replace(/\\s+/g, ' ') ?? null""")
        check("7b 主页品牌卡跟随启动目标", card and "Kimi Code" in card, str(card)[:80])
        pg.get_by_role("button", name="管理").first.click()
        pg.wait_for_timeout(1500)
        check("7c 主页品牌卡「管理」跳到 /brands/kimi", pg.url.endswith("/brands/kimi"), pg.url)
    # 行内启动/停止：真拉起实例。
        # 注意：如果跑本脚本的机器上 **agent 自己就是 `kimi` 进程**（真机验证时的常见情形），
        # 别用它跑这一条——面板拉起的实例与 agent 同名，排查成本远大于收益。
        pg.goto(BASE + "/brands/kimi")
        pg.wait_for_timeout(1500)
        pg.evaluate("""() => { document.querySelector('.list-body .tlli button').click(); }""")
        try:
            pg.wait_for_function(
                """() => /运行中/.test(document.querySelector('.list-body .tlli')?.textContent ?? '')""",
                timeout=90000)
            launched = True
        except Exception:
            launched = False
        check("7 品牌实例行内启动真实拉起实例", launched)
        pg.evaluate("""() => { document.querySelector('.list-body .tlli button').click(); }""")
        try:
            pg.wait_for_function(
                """() => !/运行中|启动中/.test(document.querySelector('.list-body .tlli')?.textContent ?? '')""",
                timeout=90000)
            stopped = True
        except Exception:
            stopped = False
        check("7a 品牌实例行内停止真实停掉实例", stopped)
    else:
        print("SKIP 6-7（HDSL_KIMI_ID 未设置）")

    b.close()

    # ------------------------------------------------- 桌面：实例列表 / 品牌页 --
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1440, "height": 900})
    pg = ctx.new_page()
    pg.route("**/*", route_api)
    ctx.request.post(API + "/api/auth/login", data={"username": USER, "password": PASS})

    pg.goto(BASE + "/instances")
    pg.wait_for_timeout(1800)
    desk = pg.evaluate("""() => ({ list: Math.round(document.querySelector('.list-body').getBoundingClientRect().height),
      summary: !!document.querySelector('.brand-summary'), filterRow: !!document.querySelector('.filter-bar'),
      divider: (() => { const c = document.querySelector('.sidebar-sub .side-category');
        return c ? getComputedStyle(c).borderBottomWidth : null; })() })""")
    check("8 桌面实例列表吃整屏（>= 600px）且无品牌残留",
          desk["list"] >= 600 and not desk["summary"] and not desk["filterRow"], json.dumps(desk))
    check("8a 子侧栏品牌分区带 1px 分割线", desk["divider"] == "1px", str(desk["divider"]))

    pg.goto(BASE + "/brands/kimi")
    pg.wait_for_timeout(1800)
    bk = pg.evaluate("""() => { const pc = document.querySelector('.page-content');
      return { cards: [...pc.children].map(c => Math.round(c.getBoundingClientRect().height)),
               list: Math.round((document.querySelector('.list-body')?.getBoundingClientRect().height) ?? -1),
               noteCollapse: !!document.querySelector('.note-collapse') }; }""")
    check("9 桌面品牌页：版本卡 <= 90px、实例列表 >= 300px、长说明原样",
          len(bk["cards"]) >= 3 and bk["cards"][1] <= 90 and bk["list"] >= 300 and not bk["noteCollapse"],
          json.dumps(bk))
    pg.screenshot(path="/tmp/acc-desktop-brands.png")
    b.close()

    # --------------------- 回归：桌面缩窄触发移动版式（细指针，非 coarse） ----
    # v0.3.3 的教训：移动样式曾误放进 (hover:none)+(pointer:coarse) 查询里，真机正常、
    # 桌面缩窄时退化。两种指针路径都要钉：现在钉的是品牌页的行内选择器与卡片高度。
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 500, "height": 900}, has_touch=False)
    pg = ctx.new_page()
    pg.route("**/*", route_api)
    ctx.request.post(API + "/api/auth/login", data={"username": USER, "password": PASS})
    pg.goto(BASE + "/brands/kimi")
    pg.wait_for_timeout(1800)
    fine = pg.evaluate("""() => {
      const nav = document.querySelector('.sidebar-sub');
      const pc = document.querySelector('.page-content');
      const cards = [...pc.children].map(c => Math.round(c.getBoundingClientRect().height));
      const summary = pc.children[1]?.querySelector('.toolbar-row');
      const sel = document.querySelector('.list-body .tlli select');
      return { dir: getComputedStyle(nav).flexDirection, cards,
               summaryH: summary ? Math.round(summary.getBoundingClientRect().height) : null,
               hasSelect: !!sel };
    }""")
    check("10 桌面缩窄（细指针）：子侧栏仍横排、版本卡折叠单行、行内选择器在",
          fine["dir"] == "row" and len(fine["cards"]) >= 3 and fine["cards"][1] <= 90
          and fine["summaryH"] is not None and fine["summaryH"] <= 52 and fine["hasSelect"],
          json.dumps(fine))
    b.close()

srv.shutdown()
fails = [n for n, ok in results if not ok]
print("\n==>", "ALL PASS" if not fails else f"FAILED: {fails}")
raise SystemExit(1 if fails else 0)