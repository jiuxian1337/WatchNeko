<div align="center">

# 🐾 WatchNeko

**Minecraft 反作弊插件 · 装上就能抓外挂**

**支持 1.8 – 1.21 · 中文 · 免费**

[![最新版本](https://img.shields.io/github/v/release/jiuxian1337/WatchNeko?style=flat-square&label=最新版本&color=2ea44f)](https://github.com/jiuxian1337/WatchNeko/releases/latest)
[![Stars](https://img.shields.io/github/stars/jiuxian1337/WatchNeko?style=flat-square&label=Stars&color=yellow)](https://github.com/jiuxian1337/WatchNeko/stargazers)
[![QQ群](https://img.shields.io/badge/QQ群-点击加入-12B7F5?style=flat-square&logo=tencentqq&logoColor=white)](https://qm.qq.com/q/3f4Touszsc)
[![教程视频](https://img.shields.io/badge/视频教程-B站观看-FB7299?style=flat-square&logo=bilibili&logoColor=white)](https://www.bilibili.com/video/BV1pZa46cE81)

### [⬇️ 下载最新版](https://github.com/jiuxian1337/WatchNeko/releases/latest)

[官方网站](https://watchneko.zkmjnic.tech) · [反馈问题](https://github.com/jiuxian1337/WatchNeko/issues/new/choose) · [QQ 群](https://qm.qq.com/q/3f4Touszsc) · [视频教程](https://www.bilibili.com/video/BV1pZa46cE81)

> 💡 **懒得每次手动更新？** 装 **[WatchNekoLoader](https://github.com/jiuxian1337/WatchNekoLoader)**，开服时自动用上最新版，装一次就不用管了。

</div>

---

## 📺 视频教程（新手推荐先看这个）

**[[免费开源] WatchNeko 反作弊使用教程](https://www.bilibili.com/video/BV1pZa46cE81)** — 从下载到装好，全程演示。

> 不想看视频的话，下面有文字版。

## 🔧 装

两种装法，**推荐第一种**。

### 方式一：自动更新（推荐）

用 **[WatchNekoLoader](https://github.com/jiuxian1337/WatchNekoLoader)** —— 装一次，以后每次开服它都会自动下载最新版反作弊，你什么都不用管。

1. 从 [WatchNekoLoader Releases](https://github.com/jiuxian1337/WatchNekoLoader/releases) 下载 `.jar`
2. 丢进服务器的 `plugins` 文件夹
3. 重启服务器

**不需要**先装 WatchNeko 本体，它会自己下载。装好后的文件会自动清理，不会越堆越多。

> 🎯 **为什么推荐它**：反作弊只有保持最新才有用。落后几个版本意味着**已经有人知道怎么绕过了，而你收不到任何提示**。手动更新一次不难，但每周都手动更新，大多数人做几次就不做了——于是服务器的反作弊永远停在装上那天。
>
> 这个插件就是把这件麻烦事彻底交出去。

> ⚠️ **用之前要知道的两件事**：
>
> 1. 需要 **Java 21 或更高**
> 2. **下载失败时服务器会拒绝启动。** 它重试 3 次（每次间隔 2 秒），仍然失败就直接关服，不会「凑合开着」。如果你的服务器**网络受限、连不上 GitHub**，请改用下面的手动方式
>
> 这是故意的设计——宁可不开服，也不开一个没有防护的服务器。

### 方式二：手动安装

1. 下载 `.jar` 文件（选文件名带 `bukkit` 的）
2. 丢进服务器的 `plugins` 文件夹
3. 重启服务器

> 已经装了 **Grim** 或 **EdGrim** 的话，先删掉再装，否则服务器起不来。

## 🎮 常用命令

根命令是 `/watchneko`，下面的命令**都需要管理员权限（OP）**。

| 你想干什么 | 输入这个 |
|---|---|
| **接收外挂提醒**（最常用） | `/watchneko alerts` |
| 看这人被提醒过几次 | `/watchneko history 玩家名` |
| 悄悄旁观可疑玩家 | `/watchneko spectate 玩家名` |
| 看玩家的客户端信息 | `/watchneko profile 玩家名` |
| 详细模式（排查用，**会刷屏**） | `/watchneko verbose` |
| 改了配置让它生效 | `/watchneko reload` |

> 输入 `/watchneko help` 可以随时看帮助。目前**没有**短命令（比如 `/wn`、`/ac`）。

### [📖 全部 16 个命令的详解、参数与权限表 →](docs/commands.md)

## 🎯 能抓什么

**Killaura** · **AimBot** · **Reach** · **Hitboxes** · **AutoClicker** · **Velocity** · **Scaffold** · **FastBreak** · **Nuker** · **Fly** · **Speed** · **NoSlow** · **Sprint** · **NoFall** · **Timer** · **ElytraFly** · **VehicleFly** · **InventoryMove** · **PingSpoof** · **Baritone** · **Crash** · **Exploit** ……

一共 **170 多项**。

默认**不封号**，作弊次数攒够了会自动踢出。想只提醒不踢人，把 `plugins/WatchNeko/punishments.yml` 里带 `kick` 的行删掉。

## 🖥️ 支持

Paper / Spigot / Purpur / Folia · Minecraft 1.8 – 1.21 · 手机版（基岩版）服务器

## ⚙️ 配置文件详解

配置文件都在 `plugins/WatchNeko/` 文件夹里，中文系统会自动显示中文。每个文件的详细说明：

| 文件 | 管什么 | 详解 |
|---|---|---|
| `config.yml` | 检测开关、灵敏度、性能、数据库 | **[📖 config.yml 详解](docs/config.md)** |
| `punishments.yml` | 抓到几次提醒、几次踢出、几次封号 | **[📖 punishments.yml 详解](docs/punishments.md)** |
| `messages.yml` | 提醒的文字、颜色、悬停内容 | **[📖 messages.yml 详解](docs/messages.md)** |
| `discord.yml` | 把提醒发到 Discord 频道 | **[📖 discord.yml 详解](docs/discord.md)** |

## ❓ 常见问题

**会不会误判？** 有可能。默认只踢不封，重进就行。怕的话把 `punishments.yml` 里带 `kick` 的行删掉。

**能和别的反作弊一起装吗？** 和 Grim、EdGrim 不能。

**里面有 AI 写的代码吗？** 有，建议先测试几天再用。

**要钱吗？** 不要。

## 💬 遇到问题找谁

| 渠道 | 适合什么 | 链接 |
|---|---|---|
| 📺 **视频教程** | 第一次装，不知道从哪下手 | [B 站观看](https://www.bilibili.com/video/BV1pZa46cE81) |
| 💬 **QQ 群** | 装不上、报错、想问配置怎么写 | **[点击加入【WatchNeko】](https://qm.qq.com/q/3f4Touszsc)** |
| 🐛 **GitHub Issue** | 确认是 bug、误判反馈（会被作者看到并修复） | [提交 Issue](https://github.com/jiuxian1337/WatchNeko/issues/new/choose) |
| 🌐 **官方网站** | 下载、更新公告 | [watchneko.zkmjnic.tech](https://watchneko.zkmjnic.tech) |

> **反馈误判时请附上 `/watchneko dump` 的输出**，否则作者没法定位。

---

<div align="center">

### ⭐ 觉得好用就点个 Star

[![Star History Chart](https://api.star-history.com/svg?repos=jiuxian1337/WatchNeko&type=Date)](https://star-history.com/#jiuxian1337/WatchNeko&Date)

<sub>作者：P01_4rU5er</sub>

</div>

---

<details>
<summary><b>English</b></summary>

**WatchNeko** — a free Minecraft anticheat for Paper / Spigot / Folia, supporting **1.8 – 1.21**.

Catches **170+ cheats**: Killaura, AimBot, Reach, Scaffold, Fly, Speed, NoFall, Timer, ElytraFly and more. It never bans — it alerts you and kicks players past a threshold, all configurable in `punishments.yml`.

**Install:** download the `.jar` from [Releases](https://github.com/jiuxian1337/WatchNeko/releases/latest), drop it in `plugins`, restart. Do not run alongside Grim or EdGrim.

Licensed under **GPLv3**.

</details>
