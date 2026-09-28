# `discord.yml` 详解

这个文件让作弊提醒**推送到你的 Discord 频道**，手机上也能收到。

位置：`plugins/WatchNeko/discord.yml`

改完之后输入 `/watchneko reload` 生效。

> ⚠️ **光开这个文件不够。** 还需要在 [`punishments.yml`](punishments.md) 里给对应的检测加上 `[webhook]` 命令，否则不会有任何东西发出去。默认配置里已经加好了。

---

## 目录

- [5 分钟配好](#5-分钟配好)
- [所有设置逐项说明](#所有设置逐项说明)
- [可用变量](#可用变量)
- [消息长什么样](#消息长什么样)
- [调试与排错](#调试与排错)
- [完整示例](#完整示例)

---

## 5 分钟配好

### 第一步：在 Discord 创建 Webhook

1. 打开你的 Discord 服务器，**右键点击**想接收提醒的频道
2. 选 **编辑频道** → 左侧 **整合** → **Webhook**
3. 点 **新 Webhook**，给它起个名字（比如 `反作弊`）
4. 点 **复制 Webhook URL**

得到的地址长这样：

```text
https://discord.com/api/webhooks/1234567890123456789/abcdefghijklmnopqrstuvwxyz-ABCDEF
```

> 🔒 **这个地址就是密码**，谁拿到都能往你频道发消息。不要发到公开的地方（比如 GitHub issue、群里）。如果不小心泄露了，回到这个页面删掉重建一个。

### 第二步：填进配置

```yaml
enabled: true
webhook: "https://discord.com/api/webhooks/你复制的完整地址"
```

> 📌 **地址必须用引号包起来**，而且**前后不能有空格**。

### 第三步：重载

```text
/watchneko reload
```

### 第四步：测试

**必须真的触发一个检测**才能验证 Discord 是否工作。最直接的办法是开个小号用外挂飞一下，或者故意触发一个容易触发的检测。

> ⚠️ **`/watchneko sendalert` 测不出 Discord 是否配好了**——它只发游戏内提醒，**完全不经过 Discord**。别看游戏里收到消息了就当配置成功了。

---

## 所有设置逐项说明

### `enabled`

```yaml
enabled: false
```

| 值 | 效果 |
|---|---|
| `false`（默认） | **关闭**。什么都不发 |
| `true` | 开启 |

**装好插件后默认是关的**，必须手动改成 `true`。

### `webhook`

```yaml
webhook: ""
```

Discord 的 Webhook 地址。

**格式要求很严**：必须完全符合 `https://discord.com/api/webhooks/数字/字符串` 这个格式。格式不对的话，控制台会打印一条错误：

```text
Discord webhook url does not follow expected format (https://discord.com/api/webhooks/<id>/<token>): 你填的地址
```

**常见错误**：

| 错误写法 | 问题 |
|---|---|
| `https://discordapp.com/api/webhooks/...` | 老域名。必须用 `discord.com` |
| `https://canary.discord.com/api/webhooks/...` | 不能带 canary |
| 地址后面多了空格 | 复制的时候容易带上，检查一下 |
| 没加引号 | YAML 里 `:` 和 `/` 可能出问题，**一律加引号** |

### `embed-title`

```yaml
embed-title: "**WatchNeko Alert**"
```

Discord 消息卡片顶部的**粗体标题**。

支持 Discord 的 Markdown（`**粗体**`、`*斜体*`、`__下划线__`）。

改成你的服务器名：

```yaml
embed-title: "**我的服务器 · 反作弊提醒**"
```

### `embed-color`

```yaml
embed-color: "#00FFFF"
```

卡片左侧那条**竖线的颜色**，十六进制色值。

| 颜色 | 色值 |
|---|---|
| 青色（默认） | `#00FFFF` |
| 红色 | `#FF0000` |
| 橙色 | `#FF8C00` |
| 绿色 | `#00FF00` |
| 紫色 | `#9B59B6` |
| 粉红 | `#FF69B4` |
| 深灰 | `#2C2F33` |

写错了（比如漏了 `#`）控制台会提示 `Discord embed color is invalid`，然后用默认颜色。

**想让不同严重程度显示不同颜色**？这个文件只能设一个颜色。想要多种颜色需要写多个 webhook 或改代码。

### `include-timestamp`

```yaml
include-timestamp: true
```

| 值 | 效果 |
|---|---|
| `true`（默认） | 卡片底部显示**发送时间**（Discord 会自动换算成观看者的本地时区） |
| `false` | 不显示 |

### `violation-content`

```yaml
violation-content:
    - "**玩家名**: %player%"
    - "**检查**: %check%"
    - "**违规率**: %violations%"
    - "**游戏版本**: %version%"
    - "**客户端品牌**: %brand%"
    - "**延迟**: %ping%"
    - "**服务器卡顿程度**: %tps%"
```

消息正文的**每一行**。这是一个列表，**加一行就多显示一行，删一行就少一行**。

每行用 Discord 的 Markdown 写，`**文字**` 是粗体。

**想精简成一行**：

```yaml
violation-content:
    - "**%player%** 触发了 **%check%**（第 %violations% 次）"
```

**想加一些信息**：

```yaml
violation-content:
    - "**玩家名**: %player%"
    - "**检查**: %check%"
    - "**违规率**: %violations%"
    - "**延迟**: %ping%"
    - "**TPS**: %tps%"
    - "**服务器**: 生存服"
```

> 最后那行 `**服务器**: 生存服` 是**写死的文字**——如果你有多个子服共用一个 webhook，这样能区分是哪个服发的。

---

## 可用变量

写在 `violation-content` 里的这些词会被替换成实际内容：

| 变量 | 变成 | 例子 |
|---|---|---|
| `%player%` | 玩家名字 | `Steve` |
| `%check%` | 触发的检测名 | `Reach` |
| `%violations%` | 当前违规次数 | `12` |
| `%version%` | 玩家的游戏版本 | `1.8.9` |
| `%brand%` | 客户端型号 | `lunarclient` / `vanilla` / `forge` |
| `%client%` | 和 `%brand%` 一样 | 同上 |
| `%ping%` | 玩家延迟（毫秒） | `46` |
| `%tps%` | 服务器 TPS（每秒 tick 数） | `19.98` |
| `%uuid%` | 玩家 UUID | `069a79f4-44e9-...` |
| `%h_sensitivity%` | 玩家水平灵敏度（0–200） | `100` |
| `%v_sensitivity%` | 玩家垂直灵敏度（0–200） | `0` |
| `%fast_math%` | 是否用了 FastMath | `true` / `false` |
| `%grim_version%` | 插件版本 | `26.09.27.2` |

> **注意两点**：
> 1. `%check%` 和 `%violations%` 是 **Discord 专用的**，游戏内提醒里用不了
> 2. `%description%`（检测说明）和 `%verbose%`（额外数据）**写在 `violation-content` 里无效**——前者游戏内才有，后者是插件自动加的独立字段

---

## 消息长什么样

一条通知在 Discord 里长这样：

```text
┌─────────────────────────────────────────┐  ← 左侧是 embed-color 的竖线
│ **WatchNeko Alert**              👤     │  ← embed-title ＋ 右上角玩家头像
│                                          │
│ **玩家名**: Steve                        │  ← violation-content 的每一行
│ **检查**: Reach                          │
│ **违规率**: 12                           │
│ **游戏版本**: 1.8.9                      │
│ **客户端品牌**: lunarclient              │
│ **延迟**: 46                             │
│ **服务器卡顿程度**: 19.98                │
│                                          │
│ ┌─ Verbose ──────────────────────────┐  │  ← 自动加的，有额外信息时才出现
│ │ 3.14000 blocks                      │  │
│ └────────────────────────────────────┘  │
│ WatchNeko Alert              今天 14:32  │  ← include-timestamp
└─────────────────────────────────────────┘
```

几个**你没法配置、但会自动出现**的东西：

| 元素 | 内容 |
|---|---|
| **右上角头像** | 自动从 `crafthead.net/helm/<玩家UUID>` 抓这个玩家的 Minecraft 皮肤头盔图 |
| **Verbose 字段** | 检测有额外数据（比如距离、角度）时自动多一个字段。**内容是英文的**，由各检测自己决定 |
| **发送时间** | `include-timestamp` 为 `true` 时显示 |
| **底部小图标** | 固定用 `grim.ac/images/grim.png`（上游 Grim 的图标），改不了 |
| **一张透明占位图** | 插件会塞一张 1 像素的空白图来撑宽卡片，**不是你的配置写错了** |

> 上面这些在代码里是**写死**的，配置文件改不了。想改只能改源码。

### 关于频率限制

Discord 对 Webhook 有发送频率限制。插件已经自动处理了：

- 消息会**排队**发送（先进先出），不会因为同时触发很多检测而丢失
- 收到 Discord 返回的 **429（请求过多）**时，会读取响应头里的重置时间，**等到限制解除再继续发**

**如果你的服务器外挂很多，提醒会明显延迟**——因为队列是一条一条发的。

> 具体的限额由 Discord 决定，插件里没有写死，也**不会**在延迟时告诉你。想减少延迟：

- 把 [`punishments.yml`](punishments.md) 里 `[webhook]` 的**间隔调大**（比如 `50:10` 改成 `50:50`）
- 或者只给严重的检测（Crash、Aim）配 `[webhook]`

---

## 调试与排错

### 完全没收到消息

按顺序检查：

1. **`enabled` 是不是 `true`**？默认是 `false`
2. **`punishments.yml` 里有没有 `[webhook]`**？没有的话永远不发
3. **`/watchneko reload` 了没有**？
4. **看控制台有没有报错**。webhook 地址格式不对会打印错误
5. **验证地址是否有效**：把地址贴到浏览器打开。**地址有效时**会返回一段 JSON 报错（大意是「不能用 GET 访问」），**地址无效时**才是 404。看到 JSON 报错说明地址是对的

### 控制台报错对照

| 报错 | 原因 | 怎么办 |
|---|---|---|
| `Discord webhook url does not follow expected format` | 地址格式不对 | 重新复制一遍，确认是 `discord.com` 且加引号 |
| `Discord embed color is invalid` | 颜色值写错了 | 检查是不是漏了 `#` |
| `Failed to load Discord webhook configuration` | YAML 格式错误 | 检查缩进（用空格不用 Tab） |
| `Encountered status code 401` | Webhook 被删了或 token 错了 | 重新创建一个 webhook |
| `Encountered status code 404` | Webhook 不存在 | 同上 |
| `Exception caught while sending a Discord webhook alert` | 网络问题 | 检查服务器能不能访问 discord.com |

### 国家级网络问题

**如果你的服务器在中国大陆**，可能连不上 `discord.com`。这不是插件的问题。

解决办法：

- 给服务器配代理
- 或者改用其他通知方式（比如通过 `punishments.yml` 里的普通命令调用其他插件的通知功能）

### 消息内容有奇怪的反斜杠

检测名里如果有下划线（比如 `Bad_Packets`），插件会自动转义成 `Bad\_Packets`——这是 Discord Markdown 的要求，**正常现象**。

---

## 完整示例

### 极简版（只想要个通知）

```yaml
enabled: true
webhook: "https://discord.com/api/webhooks/你的地址/你的token"
embed-title: "**反作弊**"
embed-color: "#FF0000"
include-timestamp: true
violation-content:
    - "**%player%** 触发了 **%check%**（第 %violations% 次）"
```

### 完整版（信息齐全）

```yaml
enabled: true
webhook: "https://discord.com/api/webhooks/你的地址/你的token"
embed-title: "**我的服务器 · 作弊警报**"
embed-color: "#FF4500"
include-timestamp: true
violation-content:
    - "**玩家名**: %player%"
    - "**检测项**: %check%"
    - "**违规次数**: %violations%"
    - "**游戏版本**: %version%"
    - "**客户端**: %brand%"
    - "**延迟**: %ping% ms"
    - "**服务器 TPS**: %tps%"
```

### 只通知严重作弊

如果你想**只把最严重的推送到 Discord**，去 [`punishments.yml`](punishments.md) 里，把不重要的检测的 `[webhook]` 行删掉，只保留这几组：

```yaml
    Crash:
        commands:
            - "1:1 [webhook]"      # 保留
    Aim:
        commands:
            - "3:3 [webhook]"      # 保留
    Hitboxes:
        commands:
            - "2:2 [webhook]"      # 保留
```

其他段里的 `[webhook]` 全删掉。

---

[⬅️ messages.yml 详解](messages.md) · [返回 README ➡️](../README.md)
