#!/usr/bin/env bash
# 轮询 GitHub Release 直至 APK 就绪，成功后 macOS 通知 + 复制下载链接到剪贴板。
#
# 用法:
#   ./scripts/wait-apk-release.sh [COMMIT_SHA]
#   COMMIT_SHA 省略时使用当前分支 HEAD。
#
# 环境变量（可选）:
#   DISPATCHER_DOWNLOAD_TOKEN  — 未设置时从 ~/.zshrc 读取
#   DISPATCHER_PROXY_PORT      — 代理端口，默认 7994
#   POLL_INTERVAL              — 轮询间隔秒，默认 45
#   MAX_ATTEMPTS               — 最大轮询次数，默认 20
#   SKIP_MAC_NOTIFY            — 设为 1 时跳过通知与剪贴板（仅打印链接）

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
COMMIT_SHA="${1:-$(git -C "$REPO_ROOT" rev-parse HEAD)}"
POLL_INTERVAL="${POLL_INTERVAL:-45}"
MAX_ATTEMPTS="${MAX_ATTEMPTS:-20}"
PROXY_PORT="${DISPATCHER_PROXY_PORT:-7994}"
RELEASE_JSON="${TMPDIR:-/tmp}/marks-dispatcher-release-${COMMIT_SHA}.json"

load_token() {
  if [[ -n "${DISPATCHER_DOWNLOAD_TOKEN:-}" ]]; then
    return 0
  fi
  if [[ ! -f "$HOME/.zshrc" ]]; then
    echo "错误: 未找到 DISPATCHER_DOWNLOAD_TOKEN，且 ~/.zshrc 不存在" >&2
    exit 1
  fi
  DISPATCHER_DOWNLOAD_TOKEN="$(
    grep '^export DISPATCHER_DOWNLOAD_TOKEN=' "$HOME/.zshrc" | tail -1 | python3 -c "
import sys
line = sys.stdin.read().strip()
val = line.split('=', 1)[1].strip()
if len(val) >= 2 and val[0] == val[-1] and val[0] in ('\"', \"'\"):
    val = val[1:-1]
print(val)
"
  )"
  if [[ -z "$DISPATCHER_DOWNLOAD_TOKEN" ]]; then
    echo "错误: 无法从 ~/.zshrc 解析 DISPATCHER_DOWNLOAD_TOKEN" >&2
    exit 1
  fi
  export DISPATCHER_DOWNLOAD_TOKEN
}

setup_proxy() {
  export all_proxy="http://127.0.0.1:${PROXY_PORT}"
  export https_proxy="http://127.0.0.1:${PROXY_PORT}"
  export http_proxy="http://127.0.0.1:${PROXY_PORT}"
}

read_version_name() {
  python3 -c "
import re, pathlib
text = pathlib.Path('${REPO_ROOT}/app/build.gradle.kts').read_text()
m = re.search(r'versionName\s*=\s*\"([^\"]+)\"', text)
print(m.group(1) if m else '')
"
}

fetch_release() {
  curl -sS --max-time 30 \
    -H "Authorization: Bearer $DISPATCHER_DOWNLOAD_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/FurtherBank/marks-dispatcher/releases/tags/debug-${COMMIT_SHA}" \
    -o "$RELEASE_JSON"
}

parse_apk_url() {
  python3 -c "
import json, sys
with open('${RELEASE_JSON}') as f:
    r = json.load(f)
if r.get('message'):
    print('PENDING', r['message'])
    raise SystemExit(1)
apk = next(a for a in r.get('assets', []) if a['name'].endswith('.apk'))
print(apk['browser_download_url'])
print(apk['size'])
print(r.get('tag_name', ''))
"
}

notify_mac() {
  local apk_url="$1"
  local version_name="$2"
  local subtitle

  if [[ -n "$version_name" ]]; then
    subtitle="v${version_name} · ${COMMIT_SHA:0:8}"
  else
    subtitle="${COMMIT_SHA:0:8}"
  fi

  printf '%s' "$apk_url" | pbcopy

  if [[ "$(uname -s)" == "Darwin" ]] && [[ "${SKIP_MAC_NOTIFY:-0}" != "1" ]]; then
    osascript <<EOF
display notification "下载链接已复制到剪贴板" with title "marks-dispatcher APK 就绪" subtitle "${subtitle}"
EOF
  fi
}

main() {
  load_token
  setup_proxy

  local version_name
  version_name="$(read_version_name)"

  echo "等待 Release: debug-${COMMIT_SHA}"
  [[ -n "$version_name" ]] && echo "versionName: ${version_name}"

  local attempt=1
  while [[ "$attempt" -le "$MAX_ATTEMPTS" ]]; do
    echo "[${attempt}/${MAX_ATTEMPTS}] 查询 GitHub Release…"
    fetch_release
    if output="$(parse_apk_url 2>/dev/null)"; then
      local apk_url apk_size tag_name
      apk_url="$(echo "$output" | sed -n '1p')"
      apk_size="$(echo "$output" | sed -n '2p')"
      tag_name="$(echo "$output" | sed -n '3p')"

      notify_mac "$apk_url" "$version_name"

      echo "tag: ${tag_name}"
      echo "apk_url: ${apk_url}"
      echo "size: ${apk_size}"
      echo "clipboard: 已复制"
      [[ "${SKIP_MAC_NOTIFY:-0}" != "1" ]] && echo "notification: 已发送 macOS 通知"
      exit 0
    fi

    if [[ -f "$RELEASE_JSON" ]]; then
      pending_msg="$(python3 -c "
import json
with open('${RELEASE_JSON}') as f:
    r = json.load(f)
print(r.get('message', 'unknown'))
" 2>/dev/null || echo "unknown")"
      echo "  尚未就绪: ${pending_msg}"
    fi

    if [[ "$attempt" -eq "$MAX_ATTEMPTS" ]]; then
      echo "超时: ${MAX_ATTEMPTS} 次轮询后仍未找到 APK Release" >&2
      echo "请检查 Actions: https://github.com/FurtherBank/marks-dispatcher/actions" >&2
      exit 1
    fi

    sleep "$POLL_INTERVAL"
    attempt=$((attempt + 1))
  done
}

main "$@"
