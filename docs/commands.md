# 命令详解

根命令是 `/watchneko`。输入 `/watchneko help` 可以随时看帮助。

> 目前**没有**短命令（比如 `/wn`、`/ac`），必须打全。唯一的例外是 `/gl <编号>`，它是 `/watchneko log <编号>` 的简写。

## 目录

- [日常最常用的](#日常最常用的)
  - [`/watchneko alerts`](#watchneko-alerts)
  - [`/watchneko history <玩家> [页码]`](#watchneko-history-玩家-页码)
  - [`/watchneko spectate <玩家>`](#watchneko-spectate-玩家)
  - [`/watchneko profile <玩家>`](#watchneko-profile-玩家)
- [排查问题时用](#排查问题时用)
  - [`/watchneko verbose`](#watchneko-verbose)
  - [`/watchneko log <编号>`](#watchneko-log-编号)
  - [`/watchneko dump`](#watchneko-dump)
  - [`/watchneko debug [玩家]`](#watchneko-debug-玩家)
  - [`/watchneko consoledebug <玩家>`](#watchneko-consoledebug-玩家)
  - [`/watchneko perf`](#watchneko-perf)
- [服务器管理用](#服务器管理用)
  - [`/watchneko brands`](#watchneko-brands)
  - [`/watchneko sendalert <消息>`](#watchneko-sendalert-消息)
  - [`/watchneko list players`](#watchneko-list-players)
  - [`/watchneko reload`](#watchneko-reload)
  - [`/watchneko version`](#watchneko-version)
- [权限总结](#权限总结)

---

根命令是 `/watchneko`。输入 `/watchneko help` 可以随时看帮助。

> 目前**没有**短命令（比如 `/wn`、`/ac`），必须打全。唯一的例外是 `/gl <编号>`，它是 `/watchneko log <编号>` 的简写。

## 日常最常用的

### `/watchneko alerts`

**开关你自己的作弊提醒。** 开一次打开，再开一次关闭。打开后，服务器里有人触发检测会立刻在聊天栏提醒你。

```text
/watchneko alerts
```

- 权限：`watchneko.alerts`
- 控制台也能用（控制台执行等于开关控制台自己的提醒）
- 想让它**一进服就自动打开**，给权限组加 `watchneko.alerts.enable-on-join`

### `/watchneko history <玩家> [页码]`

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

### `/watchneko spectate <玩家>`

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

### `/watchneko profile <玩家>`

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

## 排查问题时用

### `/watchneko verbose`

**详细模式：把每一次拉回都打出来，不做任何缓冲。**

平时检测会攒够一定次数才提醒（防止刷屏），打开 verbose 后**每一次**都显示。排查「这人到底有没有问题」的时候用。

```text
/watchneko verbose
```

- 权限：`watchneko.verbose`
- 一进服自动打开：权限 `watchneko.verbose.enable-on-join`
- **会刷屏**，排查完记得关掉

### `/watchneko log <编号>`

**把某一次检测的完整调试日志上传到网页，方便发给作者看。**

```text
/watchneko log 42
```

执行后会输出一个网址，打开就能看到这次检测的原始数据。

- 权限：`watchneko.log`
- **编号范围是 1–256**，循环使用（新记录会覆盖旧的）
- 超出范围的编号会提示「找不到该日志」
- 有个简写命令：`/gl 42`

### `/watchneko dump`

**生成一份诊断报告并上传**，内容包括：插件版本、服务器版本、Java 版本、装了哪些插件、系统信息。

**反馈问题的时候一定要附上这个。** 同一个服务器的 dump 只会生成一次，之后执行会直接给出之前的链接。

- 权限：`watchneko.dump`

### `/watchneko debug [玩家]`

**开发者的预测调试输出**，会打印大量移动计算细节。

```text
/watchneko debug
/watchneko debug Steve
```

- 权限：`watchneko.debug`
- 不填玩家就是调试自己
- 绝大部分情况你不需要这个，用 `verbose` 就够了

### `/watchneko consoledebug <玩家>`

和上面一样，但把指定玩家的调试输出**打到服务器控制台**。

- 权限：`watchneko.consoledebug`

### `/watchneko perf`

**查看反作弊的性能开销**，输出两个数字（单位毫秒）：

```text
Milliseconds per prediction (avg. 500): 0.021
Milliseconds per prediction (avg. 20k): 0.019
```

- 权限：`watchneko.performance`
- 第一个数是最近 500 次的平均，第二个是最近 20000 次的平均

> 插件没有给出「多少算正常」的标准，作者也没在代码里写。建议你自己在**玩家少的时候和人多的时候各测一次**，对比出你服务器的基线，之后再看变化。

## 服务器管理用

### `/watchneko brands`

**开关「某某用某客户端加入了游戏」的提示。**

```text
/watchneko brands
```

输出：`[WatchNeko] Steve 用 lunarclient 加入了游戏`

- 权限：`watchneko.brand`
- 一进服自动打开：权限 `watchneko.brand.enable-on-join`
- 哪些客户端不提示，由 `config.yml` 的 `client-brand.ignored-clients` 决定

### `/watchneko sendalert <消息>`

**手动发一条提醒给所有开启提醒的人。**

```text
/watchneko sendalert 大家注意，最近有外挂出没
```

- 权限：`watchneko.sendalert`
- **只会发到游戏内，不会发到 Discord**。想让某条内容进 Discord，得让检测自己触发

### `/watchneko list players`

**列出所有在线玩家，以及谁被豁免了检测。**

```text
/watchneko list players
```

被豁免的玩家显示为灰色，鼠标悬停能看到 UUID 和状态。

- 权限：`watchneko.list`

### `/watchneko reload`

**重新加载配置文件，不用重启服务器。**

```text
/watchneko reload
```

- 权限：`watchneko.reload`
- **注意**：数据库类型（SQLITE / MYSQL）改了之后必须重启才生效，`reload` 没用

### `/watchneko version`

查看当前插件版本。

- 权限：`watchneko.version`
- 服务器启动时会在控制台打印一次版本号（由 `config.yml` 的 `check-for-updates` 控制）

## 权限总结

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


---

[⬅️ 返回 README](../README.md) · [config.yml 详解 ➡️](config.md)
