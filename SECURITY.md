# 权限与安全说明

本应用需要一些**高权限**才能工作。这里逐项说明用途、获取方式、以及为什么不给也可以。

## 一、无障碍服务（核心）

```xml
android.permission.BIND_ACCESSIBILITY_SERVICE
```

**这是应用的核心能力，不是可选项。**

无障碍服务能读取屏幕上的一切内容。这也是为什么用户必须信任本应用。本项目的承诺是：

- **不朗读、不记录、不上传任何屏幕内容**
- 无障碍服务只做两件事：
  1. `GuardA11yService`：检测目标服务是否在线，掉线时重新绑定
  2. `TapAgentService`：注册传感器监听（摇一摇 / 距离 / 抬腕），以及接收音量键事件

代码可自查：`AccessibilityServices.kt` 里 `onAccessibilityEvent()` 是空实现：

```kotlin
override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
```

**本应用从不处理 AccessibilityEvent。**

## 二、WRITE_SECURE_SETTINGS（可选，需 adb 授权）

```xml
android.permission.WRITE_SECURE_SETTINGS
```

**普通安装拿不到这个权限**，必须用一次 adb 手动授予：

```bash
adb shell pm grant com.merin.a11yguard2 android.permission.WRITE_SECURE_SETTINGS
```

授权后随包持久化，重启仍有效。

**用途**：在「切换方式」选 `secure settings` 时，通过写入 `enabled_accessibility_services` 来开关无障碍服务。

**为什么需要**：无障碍服务之间的自动切换，Android 没有公开 API。可行路径只有三条：

| 方式 | 需要什么 | 本应用是否支持 |
|------|---------|--------------|
| Shizuku | 装 Shizuku 并授权 | ✅ 推荐 |
| WRITE_SECURE_SETTINGS | 一次 adb 授权 | ✅ 支持 |
| 手动点系统设置 | 每次都要看得见屏幕 | ❌ 正是要避免的 |

**不授权也能用**：默认切换方式是 Shizuku；两者都没有时，保活功能仍然工作，只是手势切换需要手动配合。

## 三、逐项权限说明

| 权限 | 用途 | 不给的影响 |
|------|------|-----------|
| `FOREGROUND_SERVICE` | 保活服务常驻 | 保活失效 |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Android 14+ 前台服务类型声明 | 同上 |
| `POST_NOTIFICATIONS` | 显示保活状态通知 | 看不到运行状态 |
| `RECEIVE_BOOT_COMPLETED` | 开机后自动恢复守护 | 重启后需手动打开一次 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 请求加入省电白名单 | 厂商省电策略可能杀掉服务 |
| `VIBRATE` | 手势触发时的触觉反馈 | 无反馈（功能仍正常） |
| `WAKE_LOCK` | **仅在用户开启「熄屏继续检测背面敲击」时**持有 PARTIAL_WAKE_LOCK | 熄屏时背面敲击失效 |

## 四、为什么不用 QUERY_ALL_PACKAGES

枚举无障碍服务需要 `PackageManager.queryIntentServices(AccessibilityService)`。部分应用（如输入法、系统组件）的无障碍服务未导出，intent 查询看不到。

**替代方案**：在 `<queries>` 里按包名逐个声明可见性（见 AndroidManifest.xml），而不是申请 `QUERY_ALL_PACKAGES` 这种全量权限。

代价是：不在列表里的应用，其无障碍服务可能需要手动添加包名才能被识别。这是隐私与完整性的取舍，本项目选择隐私优先。

## 五、网络

**本应用只有一个功能会联网：检查更新。它默认关闭。**

`AndroidManifest.xml` 声明了 `INTERNET` 与 `ACCESS_NETWORK_STATE`。这两个权限在
1.6.0 及之前的版本里**没有**，是为了内置更新才加入的。对此外界可以自行核验：

```bash
# 1) 除了 UpdateChecker / UpdateUi，没有其它文件持有网络相关代码
grep -rn "HttpURLConnection\|openConnection\|java.net" app/src/main/java/

# 2) 更新开关默认 false
grep -n "update_on" app/src/main/java/com/merin/a11yguard2/AppSettings.kt
```

### 更新检查的实际行为

| 项目 | 行为 |
|------|------|
| 默认状态 | **关闭**，必须用户填更新源 + 打开开关 |
| 请求目标 | 只有用户自己填的那一个地址（`owner/repo` 或自定义 JSON URL） |
| 发送内容 | 仅 HTTP GET + `User-Agent: a11yguard2-updater` |
| **不上传** | 设备标识、IMEI、使用数据、崩溃日志、账号信息 —— 全部不传 |
| Cookie | 不使用 |
| 第三方 SDK | 无 |
| 下载 | 用户点「下载并安装」才下载；校验 ZIP 头后交给**系统安装器**，本应用不静默安装 |

代码全部在 [UpdateChecker.kt](app/src/main/java/com/merin/a11yguard2/UpdateChecker.kt)
与 [UpdateUi.kt](app/src/main/java/com/merin/a11yguard2/UpdateUi.kt)，可逐行审计。

### 如果你不接受任何联网

两种方式彻底关闭：

1. 设置页把「检查更新」开关关掉（默认就是关的）
2. 或者自己从源码构建时删掉 Manifest 里那两行 `uses-permission` —— 代码不会因此崩溃，
   因为它只在用户主动检查时才触碰网络

### 诊断日志

日志只写本机 `files/diag.log`，需要用户手动导出。**日志不会被上传。**

## 六、root 权限（可选的执行方式）

本应用**不请求 root**，也不会主动提权。它只做一件事：如果用户把执行方式选成「root」，
就用 `su -c` 执行两条固定形态的命令：

```sh
settings put secure enabled_accessibility_services "<服务列表>"
settings put secure accessibility_enabled 0|1
```

防护措施（见 [RootProvider.kt](app/src/main/java/com/merin/a11yguard2/RootProvider.kt)）：

1. **只在用户显式选择 root 方式或是点「检测 root」时才调用 su**，auto 模式不探测
   （未 root 的设备上探测会反复弹 root 管理器授权窗）
2. **不是通用 root 执行器**：命令形态固定，不接受任意输入
3. **写入值白名单校验**：只允许 `字母数字 . _ / : -`，拒绝 `; | & $ \` " ' 换行`
   —— 这是防 shell 注入的唯一屏障，测试见 `UpdateLogicTest.safeList_rejectsShellMetacharacters`
4. 只读/只写 `Settings.Secure` 的两个键，不碰其它系统配置

## 七、第三方依赖

| 依赖 | 用途 | 不装的影响 |
|------|------|-----------|
| `dev.rikka.shizuku:api` / `:provider` | 通过 Shizuku 切换无障碍服务 | 改用 WRITE_SECURE_SETTINGS 或手动 |

除此之外**零第三方依赖**（除 JUnit 测试库）。

## 八、开源后的安全责任

如果你要 fork 并发行：

1. **不要**添加 `INTERNET` 权限后仍声称「不联网」
2. **不要**在无障碍服务里处理 `AccessibilityEvent` 却声称「不读屏幕」
3. **必须**保留本文件，或至少保留它描述的行为约定
4. 包名 `com.merin.a11yguard2` 是作者发行版标识，fork 请改包名以免用户混淆

## 报告问题

发现本应用有与本说明不符的行为，请开 issue 并附上：

- 复现步骤
- 应用内导出的 `diag.log`
- 如果涉及网络或数据，附抓包证据

这类问题会被当作最高优先级处理。
