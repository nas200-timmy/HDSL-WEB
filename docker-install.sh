#!/usr/bin/env bash
#
# HDSL-web 本地构建/启动向导（非交付物：不进 Release，也不参与 CI）。
#
#   git clone … && cd HDSL-web && ./docker-install.sh
#
# 问四件事——数据放哪、工作区挂不挂、端口、要不要国内镜像源——然后在**当前仓库**
# 里 docker build，再 docker run 起容器，等健康检查通过后打印地址。
#
# 为什么用 docker run 而不是 compose：NAS 上常常只有 docker、没有 compose 插件，
# 而这条路径用不到 compose 的任何特性。docker-compose.yml 仍然有效，两条路互不干扰。
#
# 同名容器已存在时不会擅自覆盖：先问「重启 / 重建容器 / 重新构建镜像」还是退出。
#
# 非交互（宿主 agent、批量部署用）：
#   ./docker-install.sh --yes --data /srv/hdsl-data --workspace /srv/dsh-ws --port 3080
#   ./docker-install.sh --yes --restart     # 只重启现有容器，不碰镜像
#   ./docker-install.sh --yes --recreate    # 删容器重建（用现有镜像，不重新编译）
#   ./docker-install.sh --yes --rebuild     # 重新构建镜像后重建容器
#   ./docker-install.sh --dry-run           # 只打印会执行什么
#
# 连 docker 都没装（绝对冷启动）：脚本会说明装法；加 --install-docker 则由它代装
# （需要 root 或免密 sudo）。
#
set -euo pipefail

REPO_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
STATE_FILE="$REPO_DIR/.docker-install.state"
ENV_FILE="$REPO_DIR/.docker-install.env"

DEFAULT_NAME="hdsl-web"
DEFAULT_PORT="3080"
DEFAULT_BIND="0.0.0.0"
DEFAULT_JAVA_OPTS="-Xmx1g"
NPM_MIRROR="https://registry.npmmirror.com/"
NODE_MIRROR_CN="https://mirrors.cloud.tencent.com/nodejs-release"
NODE_MIRROR_OFFICIAL="https://nodejs.org/dist"

# ── 命令行参数 ────────────────────────────────────────────────────────────────

ASSUME_YES=0
DRY_RUN=0
INSTALL_DOCKER=0
ACTION=""
NO_BUILD=0
RELEASE=0
RELEASE_SET=0        # 命令行显式给了 --release / --no-release 时置 1
MIRROR=""            # yes / no，空 = 交互时问
NAME=""
PORT=""
BIND=""
DATA=""
WORKSPACE=""
WORKSPACE_SET=0      # 交互里明确选了「不挂」时置 1，避免被状态文件里的旧值顶掉
MC=""
MC_SET=0
PASSWORD=""
PASSWORD_SET=0
IMAGE=""
BASE=""              # debian / fedora / opensuse，空 = 沿用上次或 debian

usage() {
    cat <<'EOF'
用法：docker-install.sh [选项]

问什么（交互，回车取默认）：
  --data <路径|volume:名称>       面板数据（默认 ./hdsl-web-data；volume: 走命名卷）
  --workspace <路径|volume:名称>  工作区挂到 /home/hdsl/workspace（默认不挂）
  --no-workspace                  明确不挂工作区
  --mc <路径|volume:名称|none>    MC 服务器数据挂到 /home/hdsl/mc
                                  （默认 volume:hdsl-mc；none = 不挂，重建容器会丢存档）
  --no-mc                         同 --mc none
  --port <端口>                   面板端口（默认 3080，容器内外同号）
  --bind <地址>                   监听地址（默认 0.0.0.0；只本机用 127.0.0.1）
  --name <容器名>                 默认 hdsl-web
  --password <口令>               首启管理员口令（默认留空 = 网页初始化引导）
  --mirror | --no-mirror          国内镜像源（npm 用 npmmirror，Node 用腾讯）

动作（同名容器已存在时）：
  --restart     只重启现有容器
  --recreate    删容器重建，用现有镜像（不重新编译）
  --rebuild     重新构建镜像后重建容器
  --no-build    起容器时复用记录里的镜像，不重新构建

其它：
  --release     把最后的版本标签当 RELEASE_VERSION 打进 jar（否则是 0.1.0-dev）
  --no-release  关掉上次记住的 --release
  --image <tag> 指定镜像名（默认 <最后标签>-build-<底座>-<时间戳>）
  --base <底座> 运行时底座：debian（默认）/ fedora / opensuse。
                都是原地构建，镜像里跑的是同一颗 jar；Fedora/openSUSE 用各自的
                包管理器和官方仓库，ffmpeg 只有 patent-free 版本（构建期告警，不失败）。
  --install-docker  没有 docker 时代为安装（需要 root 或免密 sudo）
  --dry-run     只打印会执行什么，不构建、不起容器
  --yes         不问，全部用参数或默认值
  -h, --help    本帮助
EOF
    exit "${1:-0}"
}

need_value() {
    [ "$#" -ge 2 ] && [ -n "$2" ] || { echo "参数 $1 需要一个值" >&2; exit 2; }
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --data) need_value "$@"; DATA="$2"; shift 2 ;;
        --workspace) need_value "$@"; WORKSPACE="$2"; WORKSPACE_SET=1; shift 2 ;;
        --no-workspace) WORKSPACE=""; WORKSPACE_SET=1; shift ;;
        --mc) need_value "$@"; MC="$2"; MC_SET=1; shift 2 ;;
        --no-mc) MC=""; MC_SET=1; shift ;;
        --port) need_value "$@"; PORT="$2"; shift 2 ;;
        --bind) need_value "$@"; BIND="$2"; shift 2 ;;
        --name) need_value "$@"; NAME="$2"; shift 2 ;;
        --password) need_value "$@"; PASSWORD="$2"; PASSWORD_SET=1; shift 2 ;;
        --mirror) MIRROR=yes; shift ;;
        --no-mirror) MIRROR=no; shift ;;
        --restart) ACTION=restart; shift ;;
        --recreate) ACTION=recreate; shift ;;
        --rebuild) ACTION=rebuild; shift ;;
        --no-build) NO_BUILD=1; shift ;;
        --image) need_value "$@"; IMAGE="$2"; shift 2 ;;
        --base) need_value "$@"; BASE="$2"; shift 2 ;;
        --release) RELEASE=1; RELEASE_SET=1; shift ;;
        --no-release) RELEASE=0; RELEASE_SET=1; shift ;;
        --install-docker) INSTALL_DOCKER=1; shift ;;
        --dry-run) DRY_RUN=1; shift ;;
        --yes|-y) ASSUME_YES=1; shift ;;
        -h|--help) usage 0 ;;
        *) echo "未知参数：$1" >&2; usage 2 ;;
    esac
done

# ── 底座 ─────────────────────────────────────────────────────────────────────
# 同一颗 jar 在三个底座上行为一致（见 docs/deployment.md §4.1 与底座兼容性报告），
# 差别只在包管理器、tini 路径和 ffmpeg 的编码器集合——那些由 Dockerfile 的
# runtime 阶段按 /etc/os-release 分派。
BASE_IMAGE=""
BASE_CODE=""
BASE_ENCODERS="required"
resolve_base() {
    case "$1" in
        debian|"")  BASE_IMAGE="debian:trixie-slim";           BASE_CODE=debian; BASE_ENCODERS=required ;;
        fedora)     BASE_IMAGE="fedora:44";                     BASE_CODE=fedora; BASE_ENCODERS=optional ;;
        opensuse|suse) BASE_IMAGE="opensuse/tumbleweed:latest"; BASE_CODE=suse;   BASE_ENCODERS=optional ;;
        *) die "--base 只认 debian / fedora / opensuse（收到：$1）" ;;
    esac
}

# ── 输出 ──────────────────────────────────────────────────────────────────────

say()  { printf '%s\n' "$*"; }
step() { printf '\n==> %s\n' "$*"; }
warn() { printf '注意：%s\n' "$*" >&2; }
die()  { printf '错误：%s\n' "$*" >&2; exit 1; }

# 交互提问；--yes 时直接取默认值并把选择打印到 stderr（stdout 只留给答案），
# 这样 $(ask …) 拿到的始终是干净的值。
ask() {
    local prompt="$1" default="${2-}" answer=""
    if [ "$ASSUME_YES" = 1 ]; then
        printf '  %s → %s\n' "$prompt" "${default:-（空）}" >&2
        printf '%s' "$default"
        return 0
    fi
    if [ -n "$default" ]; then
        read -r -p "  $prompt [$default]: " answer || true
    else
        read -r -p "  $prompt: " answer || true
    fi
    [ -n "$answer" ] || answer="$default"
    printf '%s' "$answer"
}

ask_password() {
    local answer=""
    if [ "$ASSUME_YES" = 1 ]; then
        printf '%s' ""
        return 0
    fi
    read -r -s -p "  管理员口令（留空 = 打开网页走初始化引导）: " answer || true
    printf '\n' >&2
    printf '%s' "$answer"
}

confirm() {
    local prompt="$1" answer=""
    if [ "$ASSUME_YES" = 1 ]; then
        return 0
    fi
    read -r -p "  $prompt [y/N]: " answer || true
    case "$answer" in
        y|Y|yes|YES) return 0 ;;
        *) return 1 ;;
    esac
}

# ── docker 存在性 / 冷启动 ────────────────────────────────────────────────────

docker_present() { command -v docker >/dev/null 2>&1; }
docker_ready()   { docker info >/dev/null 2>&1; }

as_root() {
    if [ "$(id -u)" = 0 ]; then
        "$@"
    elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
        sudo "$@"
    else
        return 127
    fi
}

show_install_hint() {
    local pm="手动"
    for cand in apt-get dnf yum apk pacman zypper; do
        if command -v "$cand" >/dev/null 2>&1; then pm=$cand; break; fi
    done
    say ""
    say "这台机器上没有 docker。装法（任选其一，二选一开始）："
    case "$pm" in
        apt-get) say "  sudo apt-get update && sudo apt-get install -y docker.io" ;;
        dnf)     say "  sudo dnf install -y moby-engine    # 或 docker-ce，见 docs.docker.com" ;;
        yum)     say "  sudo yum install -y docker" ;;
        apk)     say "  sudo apk add docker && sudo rc-update add docker default && sudo service docker start" ;;
        pacman)  say "  sudo pacman -S docker && sudo systemctl enable --now docker" ;;
        zypper)  say "  sudo zypper install docker" ;;
        *)       say "  官方文档：https://docs.docker.com/engine/install/" ;;
    esac
    say ""
    say "装完把当前用户加进 docker 组（否则每条命令都要 sudo，且脚本读不到状态）："
    say "  sudo usermod -aG docker $(id -un)   # 之后重新登录一次"
    say ""
    say "或者让本脚本代装：./docker-install.sh --install-docker"
}

# 以 root 跑一条安装命令；--dry-run 时只打印。
do_root() {
    if [ "$DRY_RUN" = 1 ]; then
        say "  [dry-run] $*"
    else
        as_root "$@"
    fi
}

install_docker_now() {
    local pm=""
    for cand in apt-get dnf yum apk pacman zypper; do
        if command -v "$cand" >/dev/null 2>&1; then pm=$cand; break; fi
    done
    [ -n "$pm" ] || die "认不出包管理器，请照上面的提示手动安装"

    if [ "$DRY_RUN" = 0 ]; then
        as_root true || die "需要 root 或免密 sudo 才能代装 docker（否则请手动装）"
    fi

    step "安装 docker（$pm）"
    case "$pm" in
        apt-get)
            do_root apt-get update
            do_root sh -c 'DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io'
            do_root systemctl enable --now docker
            ;;
        dnf)
            do_root dnf install -y moby-engine || do_root dnf install -y docker
            do_root systemctl enable --now docker
            ;;
        yum)
            do_root yum install -y docker
            do_root systemctl enable --now docker
            ;;
        apk)
            do_root apk add --no-cache docker
            do_root rc-update add docker default
            do_root service docker start
            ;;
        pacman)
            do_root pacman -Sy --noconfirm docker
            do_root systemctl enable --now docker
            ;;
        zypper)
            do_root zypper --non-interactive install docker
            do_root systemctl enable --now docker
            ;;
    esac

    if [ "$DRY_RUN" = 1 ]; then
        say "  （dry-run：不会真的安装）"
        return 0
    fi

    command -v docker >/dev/null 2>&1 || die "安装命令跑完了，但 PATH 里仍然没有 docker"
    if ! docker_ready; then
        say ""
        say "docker 装好了但当前用户连不上守护进程。执行下面这行，然后重新登录，再跑本脚本："
        say "  sudo usermod -aG docker $(id -un)"
        exit 1
    fi
}

ensure_docker() {
    if docker_present; then
        if docker_ready; then
            return 0
        fi
        # 有 CLI 但连不上：多半是没启动，或没进 docker 组
        local err
        err=$(docker info 2>&1 || true)
        case "$err" in
            *"Permission denied"*|*"permission denied"*)
                say ""
                say "docker 在，但当前用户没有权限（不在 docker 组）。执行："
                say "  sudo usermod -aG docker $(id -un)   # 之后重新登录"
                exit 1
                ;;
        esac
        if command -v systemctl >/dev/null 2>&1; then
            warn "docker 守护进程没在跑，尝试启动"
            as_root systemctl start docker >/dev/null 2>&1 || true
            docker_ready && return 0
        fi
        if [ "$DRY_RUN" = 1 ]; then
            warn "docker 不可用，dry-run 继续打印计划"
            return 0
        fi
        die "docker 装好了但守护进程不可用（试过启动）。请检查：systemctl status docker"
    fi

    # 完全没有 docker：绝对冷启动
    show_install_hint
    if [ "$INSTALL_DOCKER" = 1 ]; then
        install_docker_now
        return 0
    fi
    if [ "$DRY_RUN" = 1 ]; then
        say "  （dry-run：假装 docker 可用，继续打印计划）"
        return 0
    fi
    exit 2
}

# ── 版本号、端口、目录 ────────────────────────────────────────────────────────

last_tag() {
    local tag=""
    tag=$(git -C "$REPO_DIR" describe --tags --abbrev=0 2>/dev/null || true)
    [ -n "$tag" ] || tag="v0.0.0"
    printf '%s' "$tag"
}


sanitize_tag() {
    printf '%s' "$1" | tr '[:upper:]' '[:lower:]' | sed 's/[^a-z0-9_.-]/-/g'
}

# 本地构建的镜像名：<最后一个版本标签>-build-<系统版本>-<日期时间>
# 例：hdsl-web:v0.6.34-build-debian13-20261030-202516
default_image() {
    local tail_part
    # 从前这里是 <宿主系统版本>；底座能选之后那个名字会读成 "fedora-debian13"，而宿主
    # 版本对镜像内容没有意义（jar 是同一颗），时间戳已经足够区分重建。
    tail_part=$(sanitize_tag "$(last_tag)-build-${BASE_CODE}-$(date +%Y%m%d-%H%M%S)")
    printf 'hdsl-web:%s' "$tail_part"
}

port_in_use() {
    local p="$1"
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$p" <<'PY'
import socket, sys
port = int(sys.argv[1])
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    s.bind(("0.0.0.0", port))
except OSError:
    sys.exit(0)          # 0 = 被占用
finally:
    s.close()
sys.exit(1)
PY
    elif command -v ss >/dev/null 2>&1; then
        ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE "[:.]${p}$"
    else
        return 1             # 没有探测手段就当它空闲
    fi
}

# ── 状态文件 ──────────────────────────────────────────────────────────────────

load_state() {
    [ -f "$STATE_FILE" ] || return 0
    # shellcheck disable=SC1090
    . "$STATE_FILE"
    PREV_NAME=${INSTALL_NAME:-}
    PREV_PORT=${INSTALL_PORT:-}
    PREV_BIND=${INSTALL_BIND:-}
    PREV_DATA=${INSTALL_DATA:-}
    PREV_WORKSPACE=${INSTALL_WORKSPACE:-}
    PREV_MC=${INSTALL_MC:-}
    PREV_MIRROR=${INSTALL_MIRROR:-}
    PREV_RELEASE=${INSTALL_RELEASE:-}
    PREV_IMAGE=${INSTALL_IMAGE:-}
    PREV_BASE=${INSTALL_BASE:-}
}

save_state() {
    {
        printf 'INSTALL_NAME=%q\n' "$NAME"
        printf 'INSTALL_PORT=%q\n' "$PORT"
        printf 'INSTALL_BIND=%q\n' "$BIND"
        printf 'INSTALL_DATA=%q\n' "$DATA"
        printf 'INSTALL_WORKSPACE=%q\n' "$WORKSPACE"
        printf 'INSTALL_MC=%q\n' "$MC"
        printf 'INSTALL_MIRROR=%q\n' "$MIRROR_MODE"
        printf 'INSTALL_RELEASE=%q\n' "$([ "$RELEASE" = 1 ] && echo yes || echo no)"
        printf 'INSTALL_IMAGE=%q\n' "$IMAGE"
        printf 'INSTALL_BASE=%q\n' "$BASE_CODE"
    } > "$STATE_FILE"
    chmod 600 "$STATE_FILE"
}

save_env() {
    (
        umask 077
        {
            printf 'HDSL_PORT=%s\n' "$PORT"
            printf 'NPM_CONFIG_REGISTRY=%s\n' "$NPM_REGISTRY"
            printf 'JAVA_OPTS=%s\n' "$JAVA_OPTS"
            if [ -n "$PASSWORD" ]; then
                printf 'HDSL_ADMIN_PASSWORD=%s\n' "$PASSWORD"
            fi
        } > "$ENV_FILE"
    )
    chmod 600 "$ENV_FILE"
}

# ── docker 动作 ───────────────────────────────────────────────────────────────

container_status() {
    # 容器不存在时，`docker inspect` 会先往 stdout 写一个空行再以非零退出；
    # `|| printf missing` 于是拼出 "\nmissing"，任何 `!= missing` 判断都会把
    # 「不存在」读成「存在」——全新安装直接卡在「已经存在，状态：missing」。
    # 所以取出结果后去掉空白，只有真拿不到状态才算 missing。
    local status
    status=$(docker inspect -f '{{.State.Status}}' "$1" 2>/dev/null || true)
    status=$(printf '%s' "$status" | tr -d '[:space:]')
    printf '%s' "${status:-missing}"
}

build_image() {
    step "构建镜像：$IMAGE"
    if [ "$DRY_RUN" = 1 ]; then
        say "  [dry-run] docker build -t $IMAGE \\"
        say "      --build-arg NPM_REGISTRY=$NPM_REGISTRY \\"
        say "      --build-arg NODE_DIST_MIRROR=$NODE_DIST_MIRROR \\"
        say "      --build-arg HDSL_BASE=$BASE_IMAGE --build-arg HDSL_ENCODERS=$BASE_ENCODERS \\"
        say "      --build-arg RELEASE_VERSION=$RELEASE_VERSION -f Dockerfile ."
        return 0
    fi
    # Dockerfile 里的 `RUN --mount=type=cache` 要 BuildKit；老引擎（NAS 上的 20.10）
    # 默认关着，不显式打开会在这一步报 "the --mount option requires BuildKit"。
    DOCKER_BUILDKIT=1 docker build \
        -t "$IMAGE" \
        --build-arg "NPM_REGISTRY=$NPM_REGISTRY" \
        --build-arg "NODE_DIST_MIRROR=$NODE_DIST_MIRROR" \
        --build-arg "RELEASE_VERSION=$RELEASE_VERSION" \
        -f "$REPO_DIR/Dockerfile" "$REPO_DIR"
}

ensure_owner() {   # 宿主目录：只建出来，属主交给容器入口去分
    # 以前这里是 `docker run -u 0 … chown -R 1000:1000`，把整卷交给 hdsl。现在不行了：
    # 面板在容器里以 root 跑，auth/、certs/、server.yaml、logs/ 必须是 root 0700，
    # 整卷 chown 等于每次启动把秘密又交还给 agent 的 uid。
    # 入口脚本（root）每次启动自己校正属主，所以这里只需要保证目录存在。
    local path="$1"
    if [ "$DRY_RUN" = 1 ]; then
        say "  [dry-run] mkdir -p $path   # 属主由容器入口校正（面板 root，秘密 0700）"
        return 0
    fi
    mkdir -p "$path"
}

run_container() {
    local mounts=()
    case "$DATA" in
        volume:*) mounts+=(-v "${DATA#volume:}:/data") ;;
        *)        ensure_owner "$DATA"; mounts+=(-v "$DATA:/data") ;;
    esac
    if [ -n "$WORKSPACE" ]; then
        case "$WORKSPACE" in
            volume:*) mounts+=(-v "${WORKSPACE#volume:}:/home/hdsl/workspace") ;;
            *)        ensure_owner "$WORKSPACE"; mounts+=(-v "$WORKSPACE:/home/hdsl/workspace") ;;
        esac
    fi
    if [ -n "$MC" ]; then
        case "$MC" in
            volume:*) mounts+=(-v "${MC#volume:}:/home/hdsl/mc") ;;
            *)        ensure_owner "$MC"; mounts+=(-v "$MC:/home/hdsl/mc") ;;
        esac
    fi

    step "启动容器：$NAME"
    local cmd=(docker run -d
        --name "$NAME"
        --restart unless-stopped
        --stop-timeout 40
        -p "${BIND}:${PORT}:${PORT}"
        --env-file "$ENV_FILE"
        "${mounts[@]}"
        "$IMAGE")

    if [ "$DRY_RUN" = 1 ]; then
        say "  [dry-run] ${cmd[*]}"
        return 0
    fi
    save_env
    "${cmd[@]}" >/dev/null
}

wait_healthy() {
    local name="$1" waited=0 limit=180 status
    step "等待健康检查（首次启动要生成自签证书、建 pnpm store，给足两分钟）"
    if [ "$DRY_RUN" = 1 ]; then
        say "  [dry-run] 不等待"
        return 0
    fi
    while [ "$waited" -lt "$limit" ]; do
        status=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$name" 2>/dev/null || true)
        status=$(printf '%s' "$status" | tr -d '[:space:]')
        [ -n "$status" ] || status=missing
        case "$status" in
            healthy)
                say "  健康（等了 ${waited}s）"
                return 0
                ;;
            unhealthy|missing|exited|dead)
                say ""
                say "容器没有起来（状态：$status）。最近 100 行日志："
                docker logs --tail 100 "$name" 2>&1 || true
                case "$status" in
                    unhealthy)
                        say ""
                        say "常见原因：$DATA 不可写（属主不是 1000）、端口冲突、首次下载超时。"
                        ;;
                esac
                return 1
                ;;
        esac
        sleep 3
        waited=$((waited + 3))
        printf '.'
    done
    printf '\n'
    say "180 秒还没到 healthy。日志："
    docker logs --tail 100 "$name" 2>&1 || true
    return 1
}

stop_container() {
    local status
    status=$(container_status "$NAME")
    [ "$status" = missing ] && return 0
    if [ "$status" = running ]; then
        step "停止容器（最多等 40 秒让它把 dsh 实例收干净）"
        if [ "$DRY_RUN" = 1 ]; then
            say "  [dry-run] docker stop -t 40 $NAME"
        else
            docker stop -t 40 "$NAME" >/dev/null
        fi
    fi
}

remove_container() {
    [ "$(container_status "$NAME")" = missing ] && return 0
    if [ "$DRY_RUN" = 1 ]; then
        say "  [dry-run] docker rm $NAME"
    else
        docker rm "$NAME" >/dev/null
    fi
}

image_exists() { docker image inspect "$1" >/dev/null 2>&1; }

# ── 主流程 ────────────────────────────────────────────────────────────────────

[ -f "$REPO_DIR/Dockerfile" ] || die "这个目录里没有 Dockerfile，请在仓库根目录运行"

load_state

step "检查 docker"
ensure_docker
say "  docker：$(docker --version 2>/dev/null || echo '不可用（dry-run）')"

# 已有状态作为默认值：重跑时只按回车就是上次的配置
[ -n "$NAME" ] || NAME="${PREV_NAME:-$DEFAULT_NAME}"
[ -n "$PORT" ] || PORT="${PREV_PORT:-$DEFAULT_PORT}"
[ -n "$BIND" ] || BIND="${PREV_BIND:-$DEFAULT_BIND}"
[ -n "$DATA" ] || DATA="${PREV_DATA:-$REPO_DIR/hdsl-web-data}"
if [ "$WORKSPACE_SET" = 0 ] && [ -z "$WORKSPACE" ]; then
    WORKSPACE="${PREV_WORKSPACE:-}"
fi
if [ "$MC_SET" = 0 ] && [ -z "$MC" ]; then
    MC="${PREV_MC:-volume:hdsl-mc}"
fi
if [ -z "$MIRROR" ]; then
    MIRROR="${PREV_MIRROR:-}"
fi

step "配置"
if [ "$ASSUME_YES" = 0 ]; then
    say "  回车表示括号里的默认值。"
fi

NAME=$(ask "容器名" "$NAME")
DATA=$(ask "面板数据（/data；宿主路径或 volume:名称）" "$DATA")
WORKSPACE=$(ask "工作区（挂到 /home/hdsl/workspace；留空或 none = 不挂，重建即丢）" "$WORKSPACE")
MC=$(ask "MC 服务器数据（挂到 /home/hdsl/mc；volume:名称 或宿主路径；none = 不挂）" "$MC")
PORT=$(ask "面板端口（容器内外同号）" "$PORT")
BIND=$(ask "监听地址（0.0.0.0=局域网，127.0.0.1=只本机）" "$BIND")
if [ "$PASSWORD_SET" = 0 ]; then
    PASSWORD=$(ask_password)
fi
if [ -z "$MIRROR" ]; then
    MIRROR=$(ask "国内镜像源（npm 走 npmmirror、Node 走腾讯）" "yes")
fi

case "$MIRROR" in
    yes|y|Y|Yes|YES) MIRROR_MODE=yes; NPM_REGISTRY="$NPM_MIRROR"; NODE_DIST_MIRROR="$NODE_MIRROR_CN" ;;
    *)               MIRROR_MODE=no;  NPM_REGISTRY="https://registry.npmjs.org/"; NODE_DIST_MIRROR="$NODE_MIRROR_OFFICIAL" ;;
esac

JAVA_OPTS="${JAVA_OPTS:-$DEFAULT_JAVA_OPTS}"
# 上次用过 --release，这次（比如 --rebuild）默认沿用：不沿用的话会静默掉回
# 0.1.0-dev，「同一套配置重建」就变成了「换了个版本」。--no-release 显式关掉。
# 底座：命令行 > 状态文件 > debian。空串也算"没指定"，这样 --rebuild 沿用上次的底座。
[ -n "$BASE" ] || BASE="${PREV_BASE:-debian}"
resolve_base "$BASE"

if [ "$RELEASE_SET" = 0 ] && [ "${PREV_RELEASE:-}" = "yes" ]; then
    RELEASE=1
fi
RELEASE_VERSION=""
if [ "$RELEASE" = 1 ]; then
    RELEASE_VERSION=$(last_tag)
    RELEASE_VERSION=${RELEASE_VERSION#v}
fi

# 路径归一：宿主路径去掉尾斜杠；none / - 表示不挂
case "$WORKSPACE" in
    none|no|-|NONE|None) WORKSPACE="" ;;
esac
case "$MC" in
    none|no|-|NONE|None) MC="" ;;
esac
case "$DATA" in
    volume:*) ;;
    *)
        while [ "$DATA" != "/" ] && [ "${DATA%/}" != "$DATA" ]; do DATA="${DATA%/}"; done
        ;;
esac
if [ -n "$WORKSPACE" ]; then
    case "$WORKSPACE" in
        volume:*) ;;
        *) while [ "$WORKSPACE" != "/" ] && [ "${WORKSPACE%/}" != "$WORKSPACE" ]; do WORKSPACE="${WORKSPACE%/}"; done ;;
    esac
fi
if [ -n "$MC" ]; then
    case "$MC" in
        volume:*) ;;
        *) while [ "$MC" != "/" ] && [ "${MC%/}" != "$MC" ]; do MC="${MC%/}"; done ;;
    esac
fi
[ -n "$DATA" ] || die "数据目录不能为空"
[ -n "$NAME" ] || die "容器名不能为空"

# docker 的容器名/卷名只允许这些字符；提前拦，别等 docker 报一串英文
case "$NAME" in
    *[!a-zA-Z0-9_.-]*) die "容器名只能用字母、数字、下划线、点、短横线：$NAME" ;;
esac
case "$DATA" in
    volume:*)
        vol="${DATA#volume:}"
        case "$vol" in
            ''|*[!a-zA-Z0-9_.-]*) die "卷名只能用字母、数字、下划线、点、短横线：$vol" ;;
        esac
        ;;
esac
case "$WORKSPACE" in
    volume:*)
        vol="${WORKSPACE#volume:}"
        case "$vol" in
            ''|*[!a-zA-Z0-9_.-]*) die "卷名只能用字母、数字、下划线、点、短横线：$vol" ;;
        esac
        ;;
esac
case "$MC" in
    volume:*)
        vol="${MC#volume:}"
        case "$vol" in
            ''|*[!a-zA-Z0-9_.-]*) die "卷名只能用字母、数字、下划线、点、短横线：$vol" ;;
        esac
        ;;
esac

case "$PORT" in
    ''|*[!0-9]*) die "端口必须是数字：$PORT" ;;
esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "端口超出范围：$PORT"

if [ -z "$IMAGE" ]; then
    # --no-build / --recreate 想要的是「上次那个镜像」；没有记录时才落回新时间戳的默认名
    if [ "$NO_BUILD" = 1 ] && [ -n "${PREV_IMAGE:-}" ]; then
        IMAGE="$PREV_IMAGE"
    else
        IMAGE=$(default_image)
    fi
fi

# 同名容器的状态要在**打印摘要之前**拿到：`--restart` 不动镜像，而上面刚按时间戳
# 算出了一个并不存在的标签——摘要、完成信息和状态文件都得用容器实际在跑的那个，
# 否则下一次 `--no-build` 会拿着这个空名字去重建。
STATUS=$(container_status "$NAME")
if [ "$STATUS" != "missing" ] && [ "$ACTION" = "restart" ]; then
    running_image=$(docker inspect -f '{{.Config.Image}}' "$NAME" 2>/dev/null || true)
    running_image=$(printf '%s' "$running_image" | tr -d '[:space:]')
    [ -n "$running_image" ] && IMAGE="$running_image"
fi

# 数据路径：宿主路径要建出来；volume: 交给 docker
case "$DATA" in
    volume:*) say "  数据：命名卷 ${DATA#volume:}" ;;
    *)
        [ "$DRY_RUN" = 1 ] || mkdir -p "$DATA"
        say "  数据：$DATA"
        ;;
esac
if [ -n "$WORKSPACE" ]; then
    case "$WORKSPACE" in
        volume:*) say "  工作区：命名卷 ${WORKSPACE#volume:}" ;;
        *)
            [ "$DRY_RUN" = 1 ] || mkdir -p "$WORKSPACE"
            say "  工作区：$WORKSPACE"
            ;;
    esac
else
    say "  工作区：不挂（容器内，重建容器会丢）"
fi
if [ -n "$MC" ]; then
    case "$MC" in
        volume:*) say "  MC 服务器数据：命名卷 ${MC#volume:} → /home/hdsl/mc" ;;
        *)
            [ "$DRY_RUN" = 1 ] || mkdir -p "$MC"
            say "  MC 服务器数据：$MC → /home/hdsl/mc"
            ;;
    esac
else
    say "  MC 服务器数据：不挂（容器内，重建容器会丢存档）"
fi
say "  端口：${BIND}:${PORT} → 容器 ${PORT}"
say "  底座：$BASE_IMAGE（$BASE_CODE）"
say "  镜像：$IMAGE"
say "  镜像源：$MIRROR_MODE"
say "  jar 版本：$([ "$RELEASE" = 1 ] && echo "发布 $RELEASE_VERSION" || echo '开发版 0.1.0-dev（--release 用最后的标签）')"
say "  口令：$([ -n "$PASSWORD" ] && echo '已设置' || echo '留空，打开网页走初始化引导')"

# 同名容器：没有指定动作时先问，别擅自覆盖
if [ "$STATUS" != "missing" ] && [ -z "$ACTION" ]; then
    say ""
    say "容器 $NAME 已经存在，状态：$STATUS"
    if [ "$ASSUME_YES" = 1 ]; then
        say "没有指定动作，什么都没做。要动它请加 --restart / --recreate / --rebuild。"
        exit 0
    fi
    say "  1) 只重启（不动镜像）"
    say "  2) 删掉重建容器（用现有镜像，不重新编译）"
    say "  3) 重新构建镜像后重建容器（会花几分钟）"
    say "  4) 退出"
    choice=$(ask "选一个" "4")
    case "$choice" in
        1) ACTION=restart ;;
        2) ACTION=recreate ;;
        3) ACTION=rebuild ;;
        *) say "退出，什么都没改。"; exit 0 ;;
    esac
fi

# 端口占用：只有真的要新建容器时才查；而且占着它的是自己的容器就放行
if [ "$ACTION" != "restart" ]; then
    port_ours=0
    if [ "$STATUS" = "running" ] && docker port "$NAME" 2>/dev/null | grep -qE "[:.]${PORT}\$"; then
        port_ours=1
    fi
    if [ "${port_ours:-0}" = 0 ] && port_in_use "$PORT"; then
        die "端口 $PORT 已被占用（换一个，或先停掉占用的进程）"
    fi
fi

if [ "$ASSUME_YES" = 0 ] && [ "$STATUS" = "missing" ]; then
    confirm "按上面的配置开始构建并启动？" || { say "已取消。"; exit 0; }
fi

case "$ACTION" in
    restart)
        if [ "$STATUS" = "missing" ]; then
            die "没有名为 $NAME 的容器，无法 --restart"
        fi
        if [ "$STATUS" != "running" ]; then
            step "启动现有容器"
            if [ "$DRY_RUN" = 1 ]; then say "  [dry-run] docker start $NAME"; else docker start "$NAME" >/dev/null; fi
        fi
        wait_healthy "$NAME"
        ;;
    recreate|rebuild)
        if [ "$ACTION" = "rebuild" ]; then
            build_image
        else
            # --recreate 的承诺是「用现有镜像，不重新编译」。容器还在就用它实际在跑的
            # 那个；容器被手动删了，就退回状态文件里记下的那个（仍在本地才算数）。
            reusable=""
            if [ "$STATUS" != "missing" ]; then
                reusable=$(docker inspect -f '{{.Config.Image}}' "$NAME" 2>/dev/null || true)
                reusable=$(printf '%s' "$reusable" | tr -d '[:space:]')
            fi
            if [ -z "$reusable" ] && [ -n "${PREV_IMAGE:-}" ] && image_exists "$PREV_IMAGE"; then
                reusable="$PREV_IMAGE"
            fi
            if [ -n "$reusable" ] && image_exists "$reusable"; then
                IMAGE="$reusable"
                say "  复用现有镜像：$IMAGE"
            else
                build_image
            fi
        fi
        stop_container
        remove_container
        run_container
        wait_healthy "$NAME"
        ;;
    *)
        if [ "$NO_BUILD" = 1 ] && image_exists "$IMAGE"; then
            say "  复用现有镜像：$IMAGE"
        else
            build_image
        fi
        if [ "$STATUS" = "missing" ]; then
            run_container
        fi
        wait_healthy "$NAME"
        ;;
esac

[ "$DRY_RUN" = 1 ] || save_state

scheme="http"
step "完成"
say "  容器：$NAME（$IMAGE）"
say "  地址：$scheme://127.0.0.1:$PORT/   局域网：$scheme://<本机IP>:$PORT/"
if [ -n "$MC" ]; then
    case "$MC" in
        volume:*) say "  MC 数据：卷 ${MC#volume:} → /home/hdsl/mc（存档与服务端程序都在这里）" ;;
        *) say "  MC 数据：$MC → /home/hdsl/mc（存档与服务端程序都在这里）" ;;
    esac
else
    say "  MC 数据：没挂（存档在容器内，重建容器会丢）"
fi
if [ -z "$PASSWORD" ]; then
    say "  首次打开会进入初始化引导，创建第一个管理员账号。"
fi
say ""
say "  常用操作："
say "    docker logs -f $NAME                      # 看日志"
say "    ./docker-install.sh --yes --restart       # 重启"
say "    ./docker-install.sh --yes --rebuild       # 拉了新代码后重建"
say "    docker stop $NAME && docker rm $NAME      # 拆掉（数据在 $DATA，不会删）"
say ""
if [ "$DRY_RUN" = 1 ]; then
    say "  （dry-run：没有构建、没有起容器，也没有写状态文件）"
else
    say "  配置记在 $STATE_FILE（口令在 $ENV_FILE，权限 600），重跑时就是默认值。"
fi