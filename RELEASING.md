# 发布流程

本文件说明如何发一个新版本，让**应用内置的更新检查**能发现它。

## 版本号规则

`build.gradle.kts` 里两个值必须同时改，且满足映射关系：

```kotlin
versionCode = 10700      // = major*10000 + minor*100 + patch
versionName = "1.7.0"
```

| versionName | versionCode |
|-------------|-------------|
| 1.6.0 | 10600 |
| 1.7.0 | 10700 |
| 1.7.1 | 10701 |
| 2.0.0 | 20000 |

应用内的更新检查用 `versionCode` 比较大小，`versionName` 只用于显示。
GitHub Release 的 tag 会按同样规则反解成 versionCode（见 `UpdateChecker.versionCodeFromTag`），
所以 **tag 必须写成 `v1.7.0` 这种形式**，否则解析失败 → 不提示更新。

## 发版步骤

### 1. 改版本号

```bash
# app/build.gradle.kts
versionCode = 10700
versionName = "1.7.0"
```

### 2. 更新 CHANGELOG

在 `CHANGELOG.md` 顶部加一节。**这一节的内容会成为应用内更新弹窗显示的说明**，
所以写得让用户看得懂：说清「修了什么 / 加了什么 / 有什么影响」。

### 3. 构建 release APK

```bash
# 先生成签名密钥（只需一次）
keytool -genkeypair -v -keystore a11yguard2.jks \
        -keyalg RSA -keysize 2048 -validity 10000 -alias a11yguard2

# 在 local.properties 或环境变量里提供密钥信息（不要提交密钥）
cat >> local.properties <<'EOF'
RELEASE_STORE_FILE=../a11yguard2.jks
RELEASE_STORE_PASSWORD=你的密码
RELEASE_KEY_ALIAS=a11yguard2
RELEASE_KEY_PASSWORD=你的密码
EOF

./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

### 4. 建 Release

```bash
git tag v1.7.0
git push origin v1.7.0
```

在 GitHub 上创建 Release：

- **Tag**：`v1.7.0`（必须是这个格式）
- **Title**：`v1.7.0` 或一句话标题
- **Description**：把 CHANGELOG 里对应那一节整段粘进来 —— 这就是弹窗里显示的更新说明
- **Attach**：上传 `app-release.apk`，**文件名必须以 `.apk` 结尾**（更新检查靠这个后缀找下载地址）

### 5. 用户端如何收到

用户首次需要在设置页填一次更新源：

```
设置 → 更新 → 更新源 → 填 owner/repo
```

之后打开「检查更新」开关，每次启动（或手动点「立即检查更新」）就会比对版本。

## 自定义更新源（不用 GitHub Release）

如果不想用 GitHub，可以自己托管一个 JSON：

```json
{
  "versionCode": 10700,
  "versionName": "1.7.0",
  "notes": "修复摇一摇误判\n新增 root 执行方式\n移除挥手与抬腕（实测不可靠）",
  "url": "https://example.com/a11yguard2-1.7.0.apk"
}
```

把完整 URL 填到更新源即可。

## 注意事项

1. **签名必须一致**：换签名后用户无法覆盖安装，必须先卸载（会丢配置）。建议第一次发版就固定签名并妥善保存密钥。
2. **APK 文件名带 `.apk` 后缀**：GitHub 模式下更新检查按这个后缀在 assets 里找下载地址。
3. **tag 格式必须是 `vX.Y.Z`**：其它格式会让 versionCode 解析成 0，用户收不到更新提示。
4. **不要提交密钥**：`*.jks` / `*.keystore` / `keystore.properties` 已在 `.gitignore` 里。
5. **更新说明里不要放敏感信息**：它会原样显示在用户手机上。

## 检查发布是否可被识别

发完后可以用 curl 自己验一下：

```bash
curl -s https://api.github.com/repos/OWNER/REPO/releases/latest \
  | grep -E '"tag_name"|"browser_download_url"'
```

应能看到 `"tag_name": "v1.7.0"` 和一个 `.apk` 结尾的下载地址。

---

## 内置更新源（安装后即可自动更新）

应用内内置了一个默认更新源，用户装完不需要手填地址就能收到更新。

### 为什么这样设计

- **用户视角**：装了应用，作者发新版我应该知道 —— 这是分发的正常预期
- **二次开发视角**：fork 之后只改一处，更新就指向自己的仓库

### 设置内置源（三种方式任选）

**方式 1：命令行注入（推荐，不改任何文件）**

```bash
./gradlew assembleRelease -PupdateSource=你的名字/a11yguard2
```

**方式 2：环境变量**

```bash
UPDATE_SOURCE=你的名字/a11yguard2 ./gradlew assembleRelease
```

Windows:
```powershell
$env:UPDATE_SOURCE="你的名字/a11yguard2"; .\gradlew.bat assembleRelease
```

**方式 3：改 build.gradle.kts 里的默认值**

```kotlin
val srcDefault = "你的名字/a11yguard2"
```

### 用户仍可覆盖

设置页 →「更新」→「更新源」可以改成任意地址（GitHub 仓库或自定义 JSON）。
保存为空即恢复内置默认。

### 默认开关状态

- **内置了源** → 「检查更新」默认 **开启**（否则装了却收不到更新很反直觉）
- **没内置** → 默认 **关闭**（用户没给地址，开着也没用）

### 自动检查的触发条件

启动应用时（延迟 1.5 秒）检查一次，需同时满足：

1. 「检查更新」开关已开启
2. 有可用更新源（自定义或内置）
3. 距上次检查 ≥ **12 小时**
4. 该版本没有被用户「跳过」

**只有真的发现新版本才弹窗**；已是最新或网络失败都静默处理，不打扰。

### 二次开发者注意

- 换包名（`applicationId`）后，老用户无法覆盖安装，需先卸载（会丢配置）
- 如果改了包名又沿用同一个更新源，用户可能装到「别人家的新版」
- 建议 fork 后同时改：`applicationId` + `updateSource`