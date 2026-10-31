#!/bin/sh
# 容器里的身份划分：以 agent 的身份跑，确认它碰不到面板的秘密。
#
# 用法（在宿主上）：
#   docker exec -u 1000 hdsl-web sh /scripts/verify-privilege-split.sh
# 或者脚本已挂进容器时：
#   docker exec -u 1000 hdsl-web /scripts/verify-privilege-split.sh
#
# 以 root 跑是**错误用法**：root 当然什么都能改，那样验的是"root 能改"，
# 不是这次要证明的"agent 不能改"。脚本会拒绝。
set -u

fail=0
ok()   { printf 'PASS  %s\n' "$*"; }
bad()  { printf 'FAIL  %s\n' "$*"; fail=1; }

if [ "$(id -u)" = "0" ]; then
    echo "以 root 运行没有意义：请用 docker exec -u 1000 … 跑这个脚本" >&2
    exit 2
fi

echo "uid=$(id -u) gid=$(id -g) $(id -un 2>/dev/null || true)"
echo

# 1. 秘密：写不进去
for path in /data/auth /data/logs; do
    if [ -d "$path" ]; then
        if touch "$path/.hdsl-probe" 2>/dev/null; then
            rm -f "$path/.hdsl-probe" 2>/dev/null
            bad "$path 可写（agent 能改面板的秘密）"
        else
            ok "$path 不可写"
        fi
    else
        echo "跳过  $path（还不存在）"
    fi
done

# 2. 秘密：读不出来
for path in /data/auth/developer.json /data/server.yaml /data/certs; do
    if [ -e "$path" ]; then
        if cat "$path" >/dev/null 2>&1 || ls "$path" >/dev/null 2>&1; then
            bad "$path 可读（面板的口令/证书暴露给 agent）"
        else
            ok "$path 不可读"
        fi
    else
        echo "跳过  $path（还不存在）"
    fi
done

# 3. 实例子目录：agent 必须还能写，否则 dsh/pnpm 全废
instance_root=/data/hdsl/instances
if [ -d "$instance_root" ]; then
    first=$(find "$instance_root" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | head -1)
    if [ -n "$first" ]; then
        if touch "$first/.hdsl-probe" 2>/dev/null; then
            rm -f "$first/.hdsl-probe" 2>/dev/null
            ok "实例目录可写（$(basename "$first")）"
        else
            bad "实例目录不可写：agent 装不了包、起不了服务（$first）"
        fi
    else
        echo "跳过  实例目录是空的（先建一个实例再跑）"
    fi
else
    echo "跳过  $instance_root 不存在"
fi

# 4. pnpm store 必须可写且和实例同 fs，否则硬链接退化成复制
if [ -d /data/pnpm-store ]; then
    if touch /data/pnpm-store/.hdsl-probe 2>/dev/null; then
        rm -f /data/pnpm-store/.hdsl-probe 2>/dev/null
        ok "pnpm store 可写"
    else
        bad "pnpm store 不可写：实例装包会失败"
    fi
else
    echo "跳过  /data/pnpm-store 还不存在"
fi

# 5. 品牌实例的 home/workspace 必须归 agent 自己
#    Hermes 的 gateway 发现会拒绝 root 拥有的 control path（unsafe_control_path），
#    症状是聊天面板一直 "retrying in 30s" 而消息留在队列里。别的品牌不检查属主，
#    但同样不该是 root——它们只是恰好落在 setgid 的 /data 上才还能写。
brand_homes=$(find /data/brands -mindepth 4 -maxdepth 4 -type d \( -name home -o -name workspace \) 2>/dev/null | head -20)
if [ -n "$brand_homes" ]; then
    for dir in $brand_homes; do
        owner=$(stat -c '%u' "$dir" 2>/dev/null || echo '?')
        if [ "$owner" = "$(id -u)" ]; then
            ok "品牌实例目录属主正确（$dir）"
        else
            bad "品牌实例目录属主是 $owner，应为 $(id -u)：Hermes 会拒绝这种 control path（$dir）"
        fi
    done
else
    echo "跳过  还没有品牌实例（先建一个再跑）"
fi

echo
if [ "$fail" = 1 ]; then
    echo "结论：身份划分没有生效（或方向错了），见上面 FAIL 行"
    exit 1
fi
echo "结论：agent 改不动面板的秘密，但仍然能维护自己的实例"
exit 0
