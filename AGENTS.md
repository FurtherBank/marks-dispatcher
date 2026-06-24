# marks-dispatcher Agent 迭代规则

本仓库由 **Agent 自主迭代**。Agent 负责从代码修改到远端构建验收的**完整闭环**，并在每次交付时向用户返回**可直接安装的 Release APK 链接**。

## 仓库定位

- Android 客户端：剪贴板链接识别 → 派发到 PC 端 [cpu-collector](https://github.com/FurtherBank/cpu-collector)
- 远端仓库：`https://github.com/FurtherBank/marks-dispatcher`
- 默认分支：`main`
- 本地路径：`ai-workspace/marks-dispatcher`（git / Gradle 操作须在此目录执行）

## Agent 完整迭代流程

每次功能修复或版本迭代，Agent **必须**按顺序完成以下步骤，不得只改代码不推送、或推送后不验收。

### 1. 本地开发与验证

```bash
cd /path/to/ai-workspace/marks-dispatcher
chmod +x gradlew
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

- 有逻辑变更时，优先补/跑对应单元测试
- 本地 APK 路径：`app/build/outputs/apk/debug/app-debug.apk`（仅开发自检，**不作为交付物**）

### 2. 版本号（有用户可见变更时）

修改 `app/build.gradle.kts`：

- `versionCode`：单调递增整数
- `versionName`：语义化字符串（如 `1.1.2`）

纯 CI/文档改动可不改版本号；功能或 Bug 修复**应** bump。

### 3. Git 提交与推送

```bash
git status
git diff
git add <相关文件>
git commit -m "简明说明 why"
git push origin main
```

**推送要求：**

- 仅在有明确变更时提交；commit message 说明「为什么」
- 本环境访问 GitHub 可能需要代理：

```bash
export all_proxy=http://127.0.0.1:7993
export https_proxy=http://127.0.0.1:7993
export http_proxy=http://127.0.0.1:7993
```

- **禁止** force push `main`、跳过 hooks、提交密钥或 `.env`

### 4. 等待 CI 并完成构建验收

`main` push 后，GitHub Actions 工作流 `.github/workflows/android-ci.yml` 自动执行：

| Job | 作用 |
|---|---|
| `build` | 单测 + 构建 debug APK + 上传 Artifact（zip，仅 CI 留存） |
| `publish-apk` | 将 APK 发布为 **GitHub Release 附件**（用户交付物） |

**构建成功的判定标准（Agent 必须执行）：**

1. 本次 push 的 **完整 commit SHA** 对应 Release 已存在
2. Release 下存在 `.apk` 资产
3. 该 `browser_download_url` 可访问（HTTP 200，文件大小 > 0）

> **注意**：Release 标记为 `prerelease: true`，`/releases/latest` API **不会**返回这些版本。必须按 **commit SHA** 或 **releases 列表** 查询。

### 5. 获取 Release APK 链接（构建验收脚本）

环境变量 `DISPATCHER_DOWNLOAD_TOKEN` 已配置于用户 `~/.zshrc`（Fine-grained PAT，需 **Contents: Read**）。

Agent 在新 shell 中应先加载：

```bash
source ~/.zshrc
# 或仅提取 token（避免 source 整份 zshrc 卡住时）：
DISPATCHER_DOWNLOAD_TOKEN=$(grep '^export DISPATCHER_DOWNLOAD_TOKEN=' ~/.zshrc | tail -1 | python3 -c "
import sys
line=sys.stdin.read().strip(); val=line.split('=',1)[1].strip()
if len(val)>=2 and val[0]==val[-1] and val[0] in ('\"', \"'\"):
    val=val[1:-1]
print(val)
")
```

**按本次 commit 验收（推荐）：**

```bash
COMMIT_SHA="<push 后的完整 40 位 sha>"
export all_proxy=http://127.0.0.1:7993 https_proxy=http://127.0.0.1:7993 http_proxy=http://127.0.0.1:7993

curl -sS --max-time 30 \
  -H "Authorization: Bearer $DISPATCHER_DOWNLOAD_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/FurtherBank/marks-dispatcher/releases/tags/debug-${COMMIT_SHA}" \
  | python3 -c "
import sys, json
r = json.load(sys.stdin)
if r.get('message'):
    print('PENDING_OR_FAILED:', r['message']); raise SystemExit(1)
apk = next(a for a in r.get('assets', []) if a['name'].endswith('.apk'))
print('tag:', r['tag_name'])
print('apk_name:', apk['name'])
print('apk_url:', apk['browser_download_url'])
"
```

**轮询（CI 通常 3–8 分钟）：** 若返回 `PENDING_OR_FAILED: Not Found`，每 30–60 秒重试，最多约 15 分钟；超时则查看 Actions 日志排障。

**取当前最新构建（不绑定特定 commit 时）：**

```bash
curl -sS --max-time 30 \
  -H "Authorization: Bearer $DISPATCHER_DOWNLOAD_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/FurtherBank/marks-dispatcher/releases?per_page=1" \
  | python3 -c "
import sys, json
r = json.load(sys.stdin)[0]
apk = next(a for a in r['assets'] if a['name'].endswith('.apk'))
print('apk_url:', apk['browser_download_url'])
"
```

可选：对 `apk_url` 做 HEAD/下载抽样，确认非空 APK（`file` 显示 Zip archive）。

### 6. 向用户交付（回复模板）

Agent 完成迭代后，回复中**必须包含**：

1. **变更摘要**（做了什么、修了什么）
2. **commit SHA** 与 **versionName**（若已 bump）
3. **Release APK 直链**（`browser_download_url`，非 Artifact zip）
4. **CI 验收结论**：成功 / 失败及原因

示例：

```text
## 交付
- commit: 0b27579595a24afdbb09931d04876c59ad609867
- version: 1.1.1
- APK: https://github.com/FurtherBank/marks-dispatcher/releases/download/debug-<sha>/app-debug-<sha>.apk
- CI: publish-apk 已通过，APK 直链可下载（6.6MB）
```

若 CI 失败：**不得**声称构建成功；贴 Actions 失败步骤，修复后重新走完整流程。

## 产物说明（勿混淆）

| 来源 | 格式 | 用途 |
|---|---|---|
| GitHub **Release** 附件 | 独立 `.apk` | **用户安装、Agent 交付、构建验收** |
| GitHub **Actions Artifact** | 始终为 `.zip` | CI 调试/留存，**不要**给用户 |

Release 命名规则：

- Tag：`debug-<完整 commit sha>`
- 文件：`app-debug-<完整 commit sha>.apk`

## 与 cpu-collector 联调

- 标准派发地址：`http://<电脑局域网 IP>:10889/dispatch`
- 配对后 App 使用**扫描到的局域网 IP**，勿依赖 Tailscale `100.x`（除非手机也走 Tailscale）
- 联调前确认 PC 端 `cpu-collector` 已启动；详见 `cpu-collector/AGENTS.md`

## Agent 禁止事项

- 只改本地代码不 push，或 push 后不查 Release
- 把 Actions Artifact 下载链接当作最终 APK 交给用户
- 在未验收 APK 链接前声明「已发布可用」
- 在回复中泄露 `DISPATCHER_DOWNLOAD_TOKEN` 或其它密钥

## 相关文件

- CI：`.github/workflows/android-ci.yml`
- 版本：`app/build.gradle.kts`
- 配对/派发：`app/src/main/java/com/marksdispatcher/app/api/CollectorDiscoveryClient.kt`
