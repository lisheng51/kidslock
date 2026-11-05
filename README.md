# 儿童锁屏 · 电视答题锁屏（家长/孩子模式）

一款专为小米电视（Android TV / PatchWall）设计的儿童锁屏应用。改造自开源 KidsLock：把原"计时→到期锁屏"升级为"**答题通关 → 自由窗口 → 到期再锁**"循环，题目按孩子的**年级 / 教材 / 科目 / 学习进度**自动生成。

## 功能

- **家长模式**：不弹出任何题目，正常使用电视。
- **孩子模式**：
  - 开机自动弹出题目（默认 3 道），**全部答对才放行**看电视；
  - 错题即重做本轮（重新抽题），直到本轮全对；
  - 每间隔 N 分钟（默认 10）再弹出题目，依次循环；
  - 答完即时反馈，错题给出**参考答案 + 解析**。
- **题目自适应**：内置人教版 5 年级语数英；支持 1–9 年级，弱项优先 + 间隔重复选题（掌握度越低 / 越久未练，越优先出现）。
- **在线题库（可选）**：填题库 JSON 链接（如 GitHub / NAS 上的 `questions.json`，http/https 均可）或自建后台地址；断网时先用上次下载的缓存，再回退内置题库。自建方法见 [server/README.md](server/README.md)。
- **家长 PIN**：家长 PIN 可临时放行（默认 1234，建议修改）。
- **防绕过**：全屏悬浮锁屏 + 拦截 HOME / 返回键 + 重启持久化（关机再开仍锁）。

## 安装方法

### 方法一：GitHub Actions 自动出包（推荐，无需本地 Android Studio）
1. 把本仓库推送到**你自己的** GitHub 仓库（不要推到上游 wplleo/kidslock）。
2. 仓库 **Actions** 自动构建，完成后在 **Artifacts** 下载 `tv-quiz-lock-apk`，内含 `app-universal-debug.apk`。
3. 打 Release 时 APK 也会自动附在 Release 资源里。

### 方法二：adb 安装
```bash
adb connect <电视IP>:5555
adb install app-universal-debug.apk
```

### 方法三：U盘安装
将 `app-universal-debug.apk` 拷贝到 U 盘，电视「文件管理」中找到并安装（需先开启「允许安装未知来源应用」）。

## Google TV / 原生 Android TV

Google TV 没有"选择默认桌面"的入口，HOME 键会直接回到 Google TV 主屏，因此需要开启**锁屏守护（无障碍服务）**：

- 锁屏期间拦截 HOME、最近任务、设置、语音助手、应用快捷键等，只放行方向键 / 确认 / 返回 / 数字 / 音量 / 电源；
- 锁屏期间若有其他应用切到前台，立即拉回答题界面；
- 开机时系统自动绑定该服务，处于锁屏状态会立即弹题；
- 未锁屏（家长模式 / 自由观看）时完全不拦截。

**开启方法**：设置页「防绕过权限」→「开启锁屏守护」，在系统无障碍列表里打开「儿童锁屏 · 锁屏守护」。设置里找不到入口时用 adb：

```bash
adb connect <电视IP>:5555
adb shell settings put secure enabled_accessibility_services com.kidslock.app/.GuardAccessibilityService
adb shell settings put secure accessibility_enabled 1
# 可选：悬浮窗权限（Google TV 设置里通常没有此开关）
adb shell appops set com.kidslock.app SYSTEM_ALERT_WINDOW allow
```

> 注意：自由观看期间孩子仍可进入系统设置关闭无障碍服务。建议在 Google TV 设置中为「设置」等入口加家长控制，或定期检查设置页的守护状态。

## 使用流程

1. **首次打开**：电视桌面「全部应用」找到「儿童锁屏」图标打开。
2. **设置参数**：
   - 模式：家长模式 / 孩子模式
   - 年级：1–9（默认 5）
   - 教材：如「人教版」（默认）
   - 间隔分钟：看电视多久后再次弹题（默认 10）
   - 题数：每轮题目数（默认 3）
   - 家长 PIN：默认 1234，建议修改
   - 在线题库（可选）：点"配置服务器/孩子ID"，填题库链接（如 `https://…/questions.json`，孩子ID可不填）或后台地址（如 `http://192.168.1.10:8000`）+ 孩子ID，保存后自动测试连接
3. **开启孩子模式（立即锁屏）** → 开机即弹题；或保持家长模式使用。
4. **答题解锁**：屏幕显示科目 + 题干 + 4 个选项，遥控器方向键选择、确认提交；本轮全对方可进入电视。
5. **家长解锁**：锁屏界面输入 PIN 临时放行。

## 锁屏防绕过机制

- 全屏悬浮窗覆盖层遮挡桌面
- 遥控器 HOME 键被拦截，无法回桌面
- 返回键无效
- 关机重启 → 锁屏界面自动弹出（状态存 SharedPreferences）

## 项目结构

```
kidslock/
├─ .github/workflows/build.yml    # GitHub Actions 自动构建 APK
├─ app/src/main/
│  ├─ AndroidManifest.xml         # 清单（含 INTERNET 权限）
│  ├─ java/com/kidslock/app/
│  │  ├─ MainActivity.java        # 设置界面（模式/年级/教材/间隔/题数）
│  │  ├─ LockScreenActivity.java  # 通用答题卡（科目+题干+选项+参考答案）
│  │  ├─ LockService.java         # 自由窗口循环（通关→计时→再锁）
│  │  ├─ BootReceiver.java        # 开机自启（孩子模式即锁）
│  │  ├─ QuestionBank.java        # 通用题库（替代原 CharacterBank）
│  │  └─ PrefManager.java         # 模式/年级/教材/间隔/掌握度持久化
│  ├─ assets/questions.json       # 内置题库（人教版5年级语数英，24题）
│  └─ res/layout/                 # 布局（锁屏页加科目标签/题干区；设置页重写）
└─ （构建产物）app-universal-debug.apk
```

## 技术参数

- 最低支持：Android 5.0 (API 21)
- 兼容 Android 6.0.1 (API 23)：未使用 API 26+ 接口；在线拉题走后台线程，明文 HTTP 已允许（`usesCleartextTraffic`）
- 目标版本：Android 13 (API 33)
- 编译版本：API 34
- 架构：armeabi-v7a / arm64-v8a（universal 包覆盖所有小米电视）

## 编译方法

- **自动**：推送到 GitHub 即由 Actions 用 AGP 8.2 + Gradle 8.4 + JDK 17 构建（debug 密钥签名，可 sideload）。产物为 `app-universal-debug.apk`（开启了 ABI splits + universalApk）。
- **本地**：需 JDK 17 与 Android SDK（platforms;android-34、build-tools;34.0.0）。
  ```bash
  export JAVA_HOME=<JDK17路径>
  export ANDROID_HOME=<SDK路径>
  ./gradle-dist/bin/gradle assembleDebug   # 或自备 wrapper: ./gradlew assembleDebug
  ```
- **正式签名发布**：配自有 keystore 走 `assembleRelease`，产物 `app-universal-release.apk`。

## 注意事项

- 安装后需给予「开机自启」「后台运行」权限；小米「安全中心」可能清理后台，请将本应用加入白名单。
- 内置题库默认 5 年级人教版语数英；扩充题库见上层 `tv-quiz-lock/` 文档（后台 `/ui` 或批量 JSON 导入）。
- 题库答案经人工核对；若接入 LLM 生成变式，须保证参考答案正确。
