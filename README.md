# HMCL Region Fix

Fix false-positive "Chinese Mainland" region detection in **HMCL 3.17+** for users in **Taiwan, Hong Kong, Macau, and the Asia-Pacific region**.

> **This is an unofficial community project, not affiliated with HMCL.**
>
> It is intended **only** for users whose device or network is actually outside mainland China but is incorrectly detected as mainland China. **If you are in mainland China, please do not use this project.** You are responsible for complying with your local laws and regulations.

---

## 中文说明

### 这是什么

HMCL 3.17+ 使用 `RegionUtils` 判断是否隐藏/禁用下载入口：

- **机器地区**：时区为 `Asia/Shanghai`，或 UTC+8 且国家为 `CN`，或 Windows `GetUserGeoID()` 返回 45（中国）
- **网络地区**：请求 `https://ipwho.is`；如果请求失败，会**默认按中国大陆处理**

台湾、香港、澳门以及亚太地区的玩家，可能因为以下原因被误判：

- 系统时区被设为 `Asia/Shanghai`
- 系统区域/国家被设为 `CN`
- Windows 地理位置设为中国
- 使用代理/VPN，导致 `ipwho.is` 返回 `CN`
- `ipwho.is` 被拦截、超时或不可用（HMCL 会 fail-closed，直接当作中国大陆）

一旦误判，HMCL 的下载页会显示“中国大陆地区暂不支持下载”并禁用整个页面。

本项目用 **Java Agent** 在内存中把 `RegionUtils.isChinaMainlandNetwork` 设为 `false`，让这次启动不进入禁用分支：

- **不修改 HMCL 的 jar**，一个字节都不动
- HMCL 的自校验、自动更新、Microsoft 登录不受影响
- 只影响当前启动的进程，关掉 HMCL 就恢复原样
- 保留 `IS_CHINA_MAINLAND_MACHINE` 的值，不影响国内镜像选择

### 适用 / 不适用

**适用：**

- 台湾、香港、澳门、日本、韩国、新加坡等亚太地区用户
- HMCL 下载页被错误禁用，但设备/网络实际不在中国大陆
- HMCL 3.17 及以上版本

**不适用：**

- 中国大陆用户
- 把本工具当作绕过当地法规或游戏发行限制的手段
- HMCL 3.16 及更早版本（旧版使用 `LocaleUtils`，本项目不包含旧版兼容代码）

### 支持

- **HMCL 3.17+**
- Windows / macOS / Linux
- HMCL 官方 `.jar` / `.sh` / `.exe`
- `.deb` 不支持；Linux 请用 `.sh` / `.jar`，或对安装后的 `HMCL.jar` 运行本工具

### 使用

1. 从 [Releases](https://github.com/ColorCard/hmcl-region-fix/releases/latest) 下载 `HMCL-Region-Fix-v1.0.0.zip` 并解压。
2. 把 `HMCL-Region-Fix.jar` 和启动脚本放在 HMCL 包同一目录（也可以放任意位置，工具会自动搜索“下载 / 桌面 / 文档”等常见目录）。
3. 双击对应系统的启动脚本，或直接双击 `HMCL-Region-Fix.jar`：
   - Windows：`start-windows.bat`
   - macOS：`start-macos.command`
   - Linux：`chmod +x start-linux.sh && ./start-linux.sh`
4. 工具会自动找到 HMCL；没找到就把 HMCL 文件拖进窗口，或点“选择其他 HMCL 文件…”。
5. 点“启动 HMCL”。
6. 首次运行会要求确认使用范围：**确认你的设备/网络不在中国大陆**。

手动启动：

```bash
java -javaagent:/path/to/HMCL-Region-Fix.jar -jar /path/to/HMCL-3.17.SNAPSHOT.jar
```

### 构建

需要 JDK 17+（本项目 class 目标为 Java 8，方便在较旧的 JRE 上运行 GUI）。

```bash
./build.sh
# Windows:
build.bat
```

产物：`build/HMCL-Region-Fix.jar`

### 常见问题

**Q: HMCL 还是显示限制？**
- 确认 HMCL 是 3.17 或更高版本。
- 确认你是通过本工具的“启动 HMCL”按钮启动的，而不是直接双击原来的 HMCL。
- 首次启动时确认对话框必须选择“是”。

**Q: 会不会破坏 HMCL 的自动更新/登录？**
不会。本工具不修改 jar，只在内存中设置一个静态字段。HMCL 的自校验仍然通过。

**Q: HMCL 更新后还能用吗？**
可以。继续用本工具启动即可；如果 HMCL 未来的版本改了 `RegionUtils` 的字段名，Agent 会自动回退到字节码修改；如果连类结构也变了，需要更新本工具。

**Q: 为什么还要确认“不在中国大陆”？**
本项目的目标是修复**误判**，不是提供绕过地区限制的通用手段。请只在你确实位于台湾、香港、澳门或亚太地区时使用。

**Q: 支持 HMCL 3.16 吗？**
不支持。3.16 使用旧的 `LocaleUtils` 判断；本项目只保留 3.17+ 的 `RegionUtils` 方案。

**Q: Windows/macOS 提示未知开发者/文件不安全？**
本项目没有代码签名，这是正常的。可以右键 -> 打开，或者自行用 `build.sh` / `build.bat` 从源码构建。

### 免责声明

- 本项目不是 HMCL 官方项目，与 HMCL 开发团队无关。
- 本项目不提供游戏本体、账号、下载源或任何游戏内容。
- 本项目仅用于修复地区误判；请勿用于违反当地法律法规的用途。
- 软件按“现状”提供，不提供任何明示或暗示的担保。

### License

GPL-3.0. See [LICENSE](LICENSE).

---

## English

### What it is

HMCL 3.17+ uses `RegionUtils` to decide whether to disable downloads for mainland China:

- **Machine region:** timezone `Asia/Shanghai`, or UTC+8 with country `CN`, or Windows GeoID 45
- **Network region:** `https://ipwho.is`; if the request fails, HMCL treats it as mainland China (fail-closed)

Users in Taiwan, Hong Kong, Macau, and the Asia-Pacific region can be misdetected because of a mainland-like system timezone/country, a Windows location set to China, a VPN/proxy, or a blocked/failed `ipwho.is` request.

This project uses a **Java agent** to set `RegionUtils.isChinaMainlandNetwork` to `false` in memory:

- Does **not** modify the HMCL jar
- Keeps HMCL's self-integrity check, auto-update, and Microsoft login intact
- Preserves the machine-region flag used for mirror selection

### Scope

Intended for users in Taiwan, Hong Kong, Macau, and the Asia-Pacific region whose device/network is outside mainland China but is incorrectly detected as mainland China.

**Do not use this project if you are in mainland China.** You are responsible for complying with your local laws.

### Requirements

- HMCL 3.17+
- Java 17+ to run HMCL (the GUI itself targets Java 8)
- Windows / macOS / Linux

### Usage

Download the latest release, put `HMCL-Region-Fix.jar` next to the HMCL package, run the launcher for your OS, select HMCL, and click **Start HMCL**.

Manual:

```bash
java -javaagent:/path/to/HMCL-Region-Fix.jar -jar /path/to/HMCL.jar
```

### Build

```bash
./build.sh        # macOS / Linux
build.bat         # Windows
```

Output: `build/HMCL-Region-Fix.jar`

### Disclaimer

This is an unofficial community project, not affiliated with HMCL. It provides no game content and is intended only to fix region false positives. Use at your own risk and comply with your local laws.
