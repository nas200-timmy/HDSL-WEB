#!/usr/bin/env python3
"""移动端信息架构验收（headless Chromium 390x844）。

钉死 v0.3.3 的三条移动端规则，回归时直接跑：
  ① 实例列表页首屏可见 dsh 实例行 >= 3
  ② 第一个实例行的启动按钮零滚动可达（在视口内）
  ③ 品牌区折叠态（页签行 + 摘要条，同一张卡）总高 < 110px——44px 触控目标
     ×2 + 分隔线/内边距是物理下限；v0.3.2 基线整块 678px，首屏被挤出 dsh 列表
另有：品牌摘要卡展开/收起/刷新保持、长说明一行降级、主页品牌卡跟随启动目标、
?brand= 深链、桌面端零变化。

跑法（需要本机 python playwright 与一个可登录的实例）：
  pip install playwright && playwright install chromium
  # 起一个有数据的 jar（建议 >=4 个 dsh 实例 + 1 个 kimi 实例）：
  HDSL_DATA=/tmp/data HDSL_PORT=3920 java -jar build/libs/hdsl-web-<ver>.jar &
  HDSL_BASE=http://127.0.0.1:3920 HDSL_USER=admin HDSL_PASS=*** \
      python3 scripts/mobile-acceptance.py

脚本用 web/dist 起静态服务并把 /api 反代到 HDSL_BASE——所以它验的是**当前工作区
的 dist**：先 `cd web && ./node_modules/.bin/vite build`（或整包 gradle build）。
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
KIMI_ID = os.environ.get("HDSL_KIMI_ID", "")  # 预建 kimi 实例 id；为空则跳过 ⑦
BASE = "http://127.0.0.1:3910"


class SpaHandler(http.server.SimpleHTTPRequestHandler):
    def __init__(self, *a, **kw):
        super().__init__(*a, directory=DIST, **kw)

    def send_head(self):
        import os.path
        p = os.path.join(DIST, self.path.lstrip("/").split("?")[0])
        if self.path != "/" and not os.path.isfile(p):
            self.path = "/index.html"
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


with sync_playwright() as p:
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

    h = pg.evaluate("""() => {
      const tabs = [...document.querySelectorAll('.toolbar-row')]
        .find(d => [...d.querySelectorAll('button')].some(b => b.textContent.trim() === 'ZCode'));
      const summary = document.querySelector('.brand-summary');
      if (!tabs || !summary) return null;
      return {total: +(summary.getBoundingClientRect().bottom - tabs.getBoundingClientRect().top).toFixed(0)};
    }""")
    check("3 品牌折叠态总高 < 110px（44px 触控目标×2+分隔线；基线 678px）", h and h["total"] < 110, json.dumps(h))

    pg.get_by_role("button", name="Kimi Code").first.click()
    pg.wait_for_timeout(1200)
    ksum = pg.evaluate("() => document.querySelector('.brand-summary')?.textContent.trim() ?? null")
    check("4 品牌摘要卡带已装版本与实例数", ksum and "Kimi Code" in ksum and "实例" in ksum, str(ksum))
    act = pg.evaluate("""() => {
      const b = document.querySelector('.brand-summary-action');
      const e = document.querySelector('.brand-summary-expand');
      return {label: b?.textContent.trim(), disabled: b?.disabled, expand: e?.textContent.replace(/\\s+/g, '')};
    }""")
    check("4a 摘要卡有默认操作 + 全部展开药丸", act and act["label"] == "启动" and act["disabled"] is False and act["expand"] == "全部展开", json.dumps(act, ensure_ascii=False))
    pg.locator(".brand-summary-main").first.click()
    pg.wait_for_timeout(800)
    check("4b 点摘要卡展开面板", pg.evaluate("() => !document.querySelector('.brand-summary') && [...document.querySelectorAll('.filter-bar')].length > 0"))
    pg.get_by_role("button", name="Kimi Code").first.click()
    pg.wait_for_timeout(500)
    check("4c 再点页签收起", pg.evaluate("() => !!document.querySelector('.brand-summary')"))
    pg.locator(".brand-summary-main").first.click()
    pg.wait_for_timeout(500)
    pg.reload()
    pg.wait_for_timeout(1500)
    check("5 展开状态刷新后保持", pg.evaluate("() => !document.querySelector('.brand-summary') && [...document.querySelectorAll('.filter-bar')].length > 0"))

    pg.get_by_role("button", name="Kimi Code").first.click()
    pg.wait_for_timeout(300)
    pg.get_by_role("button", name="ZCode").first.click()
    pg.wait_for_timeout(600)
    pg.locator(".brand-summary-main").first.click()
    pg.wait_for_timeout(800)
    note = pg.evaluate("""() => {
      const nc = document.querySelector('.note-collapse');
      return nc ? {h: +nc.getBoundingClientRect().height.toFixed(0)} : null;
    }""")
    check("6 长说明折叠为一行 + 详情", note and note["h"] <= 48, json.dumps(note))

    if KIMI_ID:
        # 真实启动：点摘要卡上的「启动」，等状态翻到运行中（launch 同步阻塞至就绪）
        pg.get_by_role("button", name="Kimi Code").first.click()
        pg.wait_for_timeout(800)
        pg.locator(".brand-summary-action").first.click()
        try:
            pg.wait_for_function("() => document.querySelector('.brand-summary-action')?.textContent.trim() === '停止'", timeout=90000)
            launch_ok = True
        except Exception:
            launch_ok = False
        check("7m 摘要卡启动按钮真实拉起实例", launch_ok)
        st = pg.evaluate("() => document.querySelector('.brand-summary-text')?.textContent.trim()")
        check("7n 启动后摘要状态=运行中", st and "运行中" in st, str(st))
        pg.locator(".brand-summary-action").first.click()
        try:
            pg.wait_for_function("() => document.querySelector('.brand-summary-action')?.textContent.trim() === '启动'", timeout=90000)
            stop_ok = True
        except Exception:
            stop_ok = False
        check("7o 摘要卡停止按钮真实停掉实例", stop_ok)
        pg.evaluate(f"() => localStorage.setItem('hdsl.instance.selected', '{KIMI_ID}')")
        pg.goto(BASE + "/")
        pg.wait_for_timeout(2000)
        card = pg.evaluate("""() => {
          const home = document.querySelector('.mobile-home');
          return home ? home.textContent.replace(/\\s+/g, ' ') : null;
        }""")
        check("7 主页品牌卡跟随启动目标", card and "Kimi Code" in card, str(card)[:80])
        pg.get_by_role("button", name="管理").first.click()
        pg.wait_for_timeout(1200)
        check("7b 管理跳转 ?brand=kimi", "/instances?brand=kimi" in pg.url, pg.url)
    else:
        print("SKIP 7 (HDSL_KIMI_ID 未设置)")

    b.close()

    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 1146, "height": 900})
    pg = ctx.new_page()
    pg.route("**/*", route_api)
    ctx.request.post(API + "/api/auth/login", data={"username": USER, "password": PASS})
    pg.goto(BASE + "/instances")
    pg.wait_for_timeout(1500)
    desk = pg.evaluate("""() => ({
      summary: !!document.querySelector('.brand-summary'),
      caption: !!document.querySelector('.section-caption'),
      noteCollapse: !!document.querySelector('.note-collapse'),
    })""")
    check("8 桌面端无摘要卡/长说明原样", desk["summary"] is False and desk["caption"] and desk["noteCollapse"] is False, json.dumps(desk))
    b.close()

    # ---------- 回归：桌面缩窄触发移动版式（细指针，非 coarse） ----------
    # v0.3.3 的教训：摘要条样式曾误放在 (hover:none)+(pointer:coarse) 查询里，
    # 真机正常、桌面缩窄时摘要条退化成块级堆叠（按钮掉到第二行）。两种指针路径都要钉。
    b = p.chromium.launch()
    ctx = b.new_context(viewport={"width": 500, "height": 900}, has_touch=False)
    pg = ctx.new_page()
    pg.route("**/*", route_api)
    ctx.request.post(API + "/api/auth/login", data={"username": USER, "password": PASS})
    pg.goto(BASE + "/instances")
    pg.wait_for_timeout(1500)
    pg.get_by_role("button", name="Kimi Code").first.click()
    pg.wait_for_timeout(1200)
    fine = pg.evaluate("""() => {
      const bar = document.querySelector('.brand-summary');
      if (!bar) return null;
      const g = e => { const r = e.getBoundingClientRect(); return {y: +r.y.toFixed(0), right: +(r.x + r.width).toFixed(0)}; };
      const barR = g(bar);
      const pill = g(bar.querySelector('.brand-summary-expand'));
      const btn = g(bar.querySelector('.brand-summary-action'));
      return {barH: +bar.getBoundingClientRect().height.toFixed(0), barRight: barR.right,
              pillY: pill.y, pillRight: pill.right, btnY: btn.y, btnRight: btn.right,
              pillH: +bar.querySelector('.brand-summary-expand').getBoundingClientRect().height.toFixed(0)};
    }""")
    same_line = fine and abs(fine["pillY"] - fine["btnY"]) < 6
    right_anchored = fine and fine["btnRight"] >= fine["barRight"] - 8 and fine["pillRight"] < fine["btnRight"]
    styled = fine and fine["pillH"] == 32
    check("9 桌面缩窄（细指针）摘要条单行且按钮组右对齐", same_line and right_anchored and styled, json.dumps(fine))
    b.close()

srv.shutdown()
fails = [n for n, ok in results if not ok]
print("\n==>", "ALL PASS" if not fails else f"FAILED: {fails}")
raise SystemExit(1 if fails else 0)
