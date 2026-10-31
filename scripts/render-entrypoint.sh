#!/bin/bash
# 把 Dockerfile 里那段 `printf '%s\n' '…'` 的入口脚本渲染出来。
#
# 为什么要它：入口脚本是**构建期**写进镜像的 shell，只有构建到那一层才会发现少一个引号。
# 改完 Dockerfile 先渲染一遍再 `sh -n`，比等 10 分钟的镜像构建便宜得多。
#
#   scripts/render-entrypoint.sh > /tmp/entrypoint.sh && sh -n /tmp/entrypoint.sh
#   scripts/render-entrypoint.sh | grep -n fix_owner        # 看某一段
set -eu

repo=$(cd "$(dirname "$0")/.." && pwd)

python3 - "$repo/Dockerfile" <<'PY'
import re, sys

dockerfile = sys.argv[1]
src = open(dockerfile, encoding="utf-8").read()

marker = "&& chmod 0755 /usr/local/bin/hdsl-entrypoint"
if marker not in src:
    sys.exit("no entrypoint block in " + dockerfile)
end = src.index(marker)
start = src.rindex("RUN printf '%s\\n' \\", 0, end)

# 每个参数是 Dockerfile 里单引号包住的一行；\\ 是 printf 里的转义，原样输出。
lines = re.findall(r"^\s*'((?:[^'\\]|\\.)*)'\s*\\?$", src[start:end], re.M)
if not lines:
    sys.exit("no quoted lines found in the entrypoint block")
print("\n".join(lines))
PY
