<div align="center">

# WatchNeko

**一个开源 Minecraft 反作弊插件**

**支持 1.8.x – 1.21.x**

[![最新版本](https://img.shields.io/github/v/release/jiuxian1337/WatchNeko?style=flat-square&label=最新版本&color=2ea44f)](https://github.com/jiuxian1337/WatchNeko/releases/latest)
[![Stars](https://img.shields.io/github/stars/jiuxian1337/WatchNeko?style=flat-square&label=Stars&color=yellow)](https://github.com/jiuxian1337/WatchNeko/stargazers)
[![QQ群](https://img.shields.io/badge/QQ群-点击加入-12B7F5?style=flat-square&logo=tencentqq&logoColor=white)](https://qm.qq.com/q/3f4Touszsc)
[![教程视频](https://img.shields.io/badge/视频教程-B站观看-FB7299?style=flat-square&logo=bilibili&logoColor=white)](https://www.bilibili.com/video/BV1pZa46cE81)

### [下载最新版](https://github.com/jiuxian1337/WatchNeko/releases/latest)

[官方网站](https://watchneko.zkmjnic.tech) · [反馈问题](https://github.com/jiuxian1337/WatchNeko/issues/new/choose) · [QQ 群](https://qm.qq.com/q/3f4Touszsc) · [视频教程](https://www.bilibili.com/video/BV1pZa46cE81)

</div>

---

> **本项目基于 [Grim](https://github.com/GrimAnticheat/Grim) 修改而来**，使用 GPL v3 协议。上游作者为 DefineOutside 及 Grim 贡献者

---

## 视频教程

**[[免费开源] WatchNeko 反作弊使用教程](https://www.bilibili.com/video/BV1pZa46cE81)**

> 不想看视频的话下面有文字版

## 安装

### 方式一：自动更新（推荐）

用 **[WatchNekoLoader](https://github.com/jiuxian1337/WatchNekoLoader)** —— 安装一次，以后每次开服都会自动下载最新的反作弊，就什么都不用管了。

1. 从 [WatchNekoLoader Releases](https://github.com/jiuxian1337/WatchNekoLoader/releases) 下载 `.jar`
2. 丢进服务器的 `plugins` 文件夹
3. 重启服务器

**不需要**先装 WatchNeko 本体，它会自己下载。装好后的文件会自动清理，不会越堆越多。

> **注意**：
>
> 1. 需要 **Java 21 或更高**
> 2. **下载失败时服务器会拒绝启动。** 会自动重试 3 次，仍然失败就直接关服，不会有凑合开着的情况。如果你的服务器**网络受限或连不上 GitHub**，请改用下面的手动方式

### 方式二：手动安装

1. 下载 `.jar` 文件（选文件名带 `bukkit` 的）
2. 丢进服务器的 `plugins` 文件夹
3. 重启服务器

> 已经装了 **Grim** 或 **其它Grim分支** 的话，需要先删掉再装，否则会启动不了

## 常用命令

根命令是 `/watchneko`，下面的命令**都需要管理员权限**。

| 你想干什么 | 输入这个 |
|---|---|
| **接收外挂检测提醒** | `/watchneko alerts` |
| 看这人被提醒过几次 | `/watchneko history 玩家名` |
| 悄悄旁观可疑玩家 | `/watchneko spectate 玩家名` |
| 看玩家的客户端信息 | `/watchneko profile 玩家名` |
| 详细模式（排查用，可能**会刷屏**） | `/watchneko verbose` |
| 改了配置让它生效 | `/watchneko reload` |

> 输入 `/watchneko help` 可以随时看帮助。目前**没有**短命令（比如 `/wn`、`/ac`）。

### [全部 17 个命令的详解、参数与权限表 →](docs/commands.md)

## 能检测的作弊类型

- **杀戮光环**
- **自动瞄准**
- **修改攻击交互距离**
- **修改实体碰撞箱大小**
- **自动连点器**
- **反击退**
- **自动搭路**
- **快速挖掘**
- **范围挖掘**
- **飞行**
- **加快移动速度**
- **使用物品无减速**
- **不符合原版的疾跑**
- **摔落无伤害**
- **时间加速**
- **鞘翅平飞和无烟花加速飞**
- **船飞**
- **打开背包或箱子时走路**
- **延迟伪造**
- **Baritone 自动挖掘与放置**
- **矿透行为**
- **崩服**
- **漏洞利用**
- 以及更多....

一共 **180 多项** 检查，可能没法 100% 的检测到任何作弊

> WatchNeko 默认**不会封号**，作弊次数攒够了会自动踢出
>
> 1. 想只**提醒**不踢人就把 `plugins/WatchNeko/punishments.yml` 里所有带 `kick` 的行删掉
> 2. 想只**封禁**不踢人就把 `plugins/WatchNeko/punishments.yml` 里所有的 `kick` 都换成 `ban`
> 3. 想实现更进阶的封禁惩罚效果需要搭配 [AdvancedBan](https://www.spigotmc.org/resources/advancedban.8695/) 或者 [LiteBans](https://www.spigotmc.org/resources/litebans.3715/) 插件

## 版本支持

支持的服务端：Paper / Spigot / Purpur / Folia

支持的 Minecraft 服务端版本：1.8.x - 1.21.x

基岩版玩家（Geyser / Floodgate）会被自动豁免所有检测

## 配置文件

配置文件都在 `plugins/WatchNeko/` 文件夹里，中文系统会自动显示中文。每个文件的详细说明：

| 文件 | 管什么 | 详解 |
|---|---|---|
| `config.yml` | 检测开关、灵敏度、性能、数据库 | **[config.yml 详解](docs/config.md)** |
| `punishments.yml` | 抓到几次提醒、几次踢出、几次封号 | **[punishments.yml 详解](docs/punishments.md)** |
| `messages.yml` | 提醒的文字、颜色、悬停内容 | **[messages.yml 详解](docs/messages.md)** |
| `discord.yml` | 把提醒发到 Discord 频道 | **[discord.yml 详解](docs/discord.md)** |

云端旋转检测 `AnalysisA` 需要单独搭一个服务，完整的接入流程见 **[云端检测接入指南](docs/cloud-detection.md)**

## 常见问题

**会不会误判合法玩家？** 有可能，但是默认只踢不封。怕的话把 `punishments.yml` 里带 `kick` 的行删掉

**能和别的反作弊一起装吗？** 和 Grim、EdGrim 等其它Grim分支不能。其它反作弊还未测试，可以自己动手试下

**里面有 AI 写的代码吗？** 有，建议先测试几天再用

**要钱吗？** 不要，永久开源免费

## 遇到问题

| 渠道 | 适合什么 | 链接 |
|---|---|---|
| **视频教程** | 第一次装不知道从哪下手 | [B 站观看](https://www.bilibili.com/video/BV1pZa46cE81) |
| **QQ 群** | 装不上报错、想问配置怎么写 | **[加入【WatchNeko】交流群](https://qm.qq.com/q/3f4Touszsc)** |
| **GitHub Issue** | 确认是 bug、误判反馈（会被作者看到并修复） | [提交 Issue](https://github.com/jiuxian1337/WatchNeko/issues/new/choose) |
| **官方网站** | 下载、更新公告 | [watchneko.zkmjnic.tech](https://watchneko.zkmjnic.tech) |

> **反馈误判时请附上 `/watchneko dump` 的输出**，否则作者没法定位。

---

<div align="center">

### 觉得好用就点个 Star

[![Star History Chart](https://api.star-history.com/svg?repos=jiuxian1337/WatchNeko&type=Date)](https://star-history.com/#jiuxian1337/WatchNeko&Date)

<sub>作者：P01_4rU5er</sub>

</div>

---

<details>
<summary><b>English</b></summary>

**WatchNeko** — a free Minecraft anticheat for Paper / Spigot / Folia, supporting **1.8 – 1.21**. Bedrock players (Geyser / Floodgate) are exempt.

Catches **180+ cheats**: Killaura, AimBot, Reach, Scaffold, Fly, Speed, NoFall, Timer, ElytraFly and more. It never bans — it alerts you and kicks players past a threshold, all configurable in `punishments.yml`.

**Install:** download the `.jar` from [Releases](https://github.com/jiuxian1337/WatchNeko/releases/latest), drop it in `plugins`, restart. Do not run alongside Grim or EdGrim.

Licensed under **GPLv3**. Based on [Grim](https://github.com/GrimAnticheat/Grim) by DefineOutside and contributors.

</details>
