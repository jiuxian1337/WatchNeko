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

</div>

---

## 📺 视频教程（新手推荐先看这个）

**[[免费开源] WatchNeko 反作弊使用教程](https://www.bilibili.com/video/BV1pZa46cE81)** — 从下载到装好，全程演示。

> 不想看视频的话，下面有文字版。

## 🔧 装

1. 下载 `.jar` 文件（选文件名带 `bukkit` 的）
2. 丢进服务器的 `plugins` 文件夹
3. 重启服务器

> 已经装了 **Grim** 或 **EdGrim** 的话，先删掉再装，否则服务器起不来。

## 🎮 命令详解

根命令是 `/watchneko`。输入 `/watchneko help` 可以随时看帮助。

> 目前**没有**短命令（比如 `/wn`、`/ac`），必须打全。唯一的例外是 `/gl <编号>`，它是 `/watchneko log <编号>` 的简写。

### 日常最常用的

#### `/watchneko alerts`

**开关你自己的作弊提醒。** 开一次打开，再开一次关闭。打开后，服务器里有人触发检测会立刻在聊天栏提醒你。

```text
/watchneko alerts
```

- 权限：`watchneko.alerts`
- 控制台也能用（控制台执行等于开关控制台自己的提醒）
- 想让它**一进服就自动打开**，给权限组加 `watchneko.alerts.enable-on-join`

#### `/watchneko history <玩家> [页码]`

**查这个人以前被检测到过几次。**

```text
/watchneko history Steve
/watchneko history Steve 2
```

输出长这样（`[Prison]` 是你设置的服务器名，`距离` 那段是检测附带的额外信息）：

```text
[WatchNeko] 显示 Steve 的日志 (1/3)
[WatchNeko] [Prison] 失败 Reach (x12) 3.14000 blocks (5m 前)
```

> `3.14000 blocks` 是 Reach 检测输出的原始数据——**额外信息部分是英文的，且由检测自己决定内容**，不是所有检测都有。

| 参数 | 说明 |
|---|---|
| `<玩家>` | 玩家名，**可以是离线玩家** |
| `[页码]` | 第几页，不填就是第 1 页 |

- 权限：`watchneko.history`
- 每页显示多少条由 `config.yml` 的 `history.entries-per-page` 决定（默认 15 条）
- 记录存在数据库里，**永久保存，不会自动清理**（数据存在 `plugins/WatchNeko/violations.sqlite`）

#### `/watchneko spectate <玩家>`

**悄悄传送到可疑玩家身边，用旁观模式盯着他。**

```text
/watchneko spectate Steve
```

执行后你会进入旁观模式并传送到他附近，**你不会出现在他的玩家列表里**（默认隐藏）。看完之后：

```text
/watchneko stopspectating
```

会把你**传送回原来的位置**并退出旁观模式。

- 权限：`watchneko.spectate`
- 只能玩家用，控制台不行
- 不能对自己用
- 加 `here` 参数（`/watchneko stopspectating here`）可以**留在原地**不传送回去，需要额外权限 `watchneko.spectate.stophere`

#### `/watchneko profile <玩家>`

**看这个玩家的客户端信息。**

```text
/watchneko profile Steve
```

```text
======================
Steve 的信息
延迟: 46
版本: 1.8.9
客户端型号: lunarclient
水平灵敏度: 100%
垂直灵敏度: 100%
FastMath: false
======================
```

灵敏度这两行显示的是**玩家客户端设置里的原始数值**（0–200）。插件不对它做任何判断，只是展示出来给你参考。

- 权限：`watchneko.profile`
- 显示的内容可以在 `messages.yml` 的 `profile` 里改

### 排查问题时用

#### `/watchneko verbose`

**详细模式：把每一次拉回都打出来，不做任何缓冲。**

平时检测会攒够一定次数才提醒（防止刷屏），打开 verbose 后**每一次**都显示。排查「这人到底有没有问题」的时候用。

```text
/watchneko verbose
```

- 权限：`watchneko.verbose`
- 一进服自动打开：权限 `watchneko.verbose.enable-on-join`
- **会刷屏**，排查完记得关掉

#### `/watchneko log <编号>`

**把某一次检测的完整调试日志上传到网页，方便发给作者看。**

```text
/watchneko log 42
```

执行后会输出一个网址，打开就能看到这次检测的原始数据。

- 权限：`watchneko.log`
- **编号范围是 1–256**，循环使用（新记录会覆盖旧的）
- 超出范围的编号会提示「找不到该日志」
- 有个简写命令：`/gl 42`

#### `/watchneko dump`

**生成一份诊断报告并上传**，内容包括：插件版本、服务器版本、Java 版本、装了哪些插件、系统信息。

**反馈问题的时候一定要附上这个。** 同一个服务器的 dump 只会生成一次，之后执行会直接给出之前的链接。

- 权限：`watchneko.dump`

#### `/watchneko debug [玩家]`

**开发者的预测调试输出**，会打印大量移动计算细节。

```text
/watchneko debug
/watchneko debug Steve
```

- 权限：`watchneko.debug`
- 不填玩家就是调试自己
- 绝大部分情况你不需要这个，用 `verbose` 就够了

#### `/watchneko consoledebug <玩家>`

和上面一样，但把指定玩家的调试输出**打到服务器控制台**。

- 权限：`watchneko.consoledebug`

#### `/watchneko perf`

**查看反作弊的性能开销**，输出两个数字（单位毫秒）：

```text
Milliseconds per prediction (avg. 500): 0.021
Milliseconds per prediction (avg. 20k): 0.019
```

- 权限：`watchneko.performance`
- 第一个数是最近 500 次的平均，第二个是最近 20000 次的平均

> 插件没有给出「多少算正常」的标准，作者也没在代码里写。建议你自己在**玩家少的时候和人多的时候各测一次**，对比出你服务器的基线，之后再看变化。

### 服务器管理用

#### `/watchneko brands`

**开关「某某用某客户端加入了游戏」的提示。**

```text
/watchneko brands
```

输出：`[WatchNeko] Steve 用 lunarclient 加入了游戏`

- 权限：`watchneko.brand`
- 一进服自动打开：权限 `watchneko.brand.enable-on-join`
- 哪些客户端不提示，由 `config.yml` 的 `client-brand.ignored-clients` 决定

#### `/watchneko sendalert <消息>`

**手动发一条提醒给所有开启提醒的人。**

```text
/watchneko sendalert 大家注意，最近有外挂出没
```

- 权限：`watchneko.sendalert`
- **只会发到游戏内，不会发到 Discord**。想让某条内容进 Discord，得让检测自己触发

#### `/watchneko list players`

**列出所有在线玩家，以及谁被豁免了检测。**

```text
/watchneko list players
```

被豁免的玩家显示为灰色，鼠标悬停能看到 UUID 和状态。

- 权限：`watchneko.list`

#### `/watchneko reload`

**重新加载配置文件，不用重启服务器。**

```text
/watchneko reload
```

- 权限：`watchneko.reload`
- **注意**：数据库类型（SQLITE / MYSQL）改了之后必须重启才生效，`reload` 没用

#### `/watchneko version`

查看当前插件版本。

- 权限：`watchneko.version`
- 服务器启动时会在控制台打印一次版本号（由 `config.yml` 的 `check-for-updates` 控制）

### 权限总结

| 权限 | 作用 |
|---|---|
| `watchneko.alerts` | 接收作弊提醒 |
| `watchneko.alerts.enable-on-join` | 进服自动开启提醒 |
| `watchneko.history` | 查看玩家违规历史 |
| `watchneko.log` | 上传调试日志 |
| `watchneko.spectate` | 旁观玩家 |
| `watchneko.spectate.stophere` | 旁观后留在原地 |
| `watchneko.profile` | 查看玩家信息 |
| `watchneko.verbose` | 详细模式 |
| `watchneko.debug` | 调试输出 |
| `watchneko.consoledebug` | 控制台调试输出 |
| `watchneko.performance` | 查看性能 |
| `watchneko.brand` | 客户端品牌提示 |
| `watchneko.sendalert` | 手动发提醒 |
| `watchneko.list` | 列出玩家 |
| `watchneko.dump` | 生成诊断报告 |
| `watchneko.reload` | 重载配置 |
| `watchneko.exempt` | **豁免全部检测** |
| `watchneko.nosetback` | 不做拉回 |
| `watchneko.nomodifypacket` | 不修改数据包 |

给管理员组一次性加权限（LuckPerms）：

```text
/lp group admin permission set watchneko.alerts true
/lp group admin permission set watchneko.history true
/lp group admin permission set watchneko.spectate true
/lp group admin permission set watchneko.profile true
```

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
