# `messages.yml` 详解

这个文件决定**所有提示文字长什么样**：作弊提醒、命令回执、踢出理由。

位置：`plugins/WatchNeko/messages.yml`

改完之后输入 `/watchneko reload` 生效。

---

## 目录

- [颜色代码怎么写](#颜色代码怎么写)
- [prefix — 前缀](#prefix--前缀)
- [作弊提醒的格式](#作弊提醒的格式)
- [开关类提示](#开关类提示)
- [命令回执](#命令回执)
- [踢出理由](#踢出理由)
- [profile — 玩家信息面板](#profile--玩家信息面板)
- [help — 帮助菜单](#help--帮助菜单)
- [history — 历史记录显示](#history--历史记录显示)
- [完整可复制示例](#完整可复制示例)

---

## 颜色代码怎么写

这个文件支持**两套**写法，可以混用。

### 写法一：`&` 加数字（传统）

| 代码 | 颜色 | 代码 | 颜色 |
|---|---|---|---|
| `&0` | 黑色 | `&8` | 深灰色 |
| `&1` | 深蓝色 | `&9` | 蓝色 |
| `&2` | 深绿色 | `&a` | 绿色 |
| `&3` | 青色 | `&b` | 浅蓝色 |
| `&4` | 深红色 | `&c` | 红色 |
| `&5` | 紫色 | `&d` | 粉色 |
| `&6` | 金色 | `&e` | 黄色 |
| `&7` | 灰色 | `&f` | 白色 |

**样式**：

| 代码 | 效果 |
|---|---|
| `&l` | **加粗** |
| `&o` | *斜体* |
| `&n` | 下划线 |
| `&m` | ~~删除线~~ |
| `&k` | 乱码（随机字符） |
| `&r` | 重置所有格式 |

例子：

```yaml
prefix: "&7[&bWatchNeko&7]"
```

显示为 `[WatchNeko]`——灰色方括号，青色文字。

### 写法二：`<标签>` （MiniMessage）

| 标签 | 颜色 |
|---|---|
| `<red>` `<blue>` `<green>` | 红、蓝、绿 |
| `<gold>` `<yellow>` `<gray>` | 金、黄、灰 |
| `<dark_red>` `<aqua>` `<light_purple>` | 深红、青、粉 |

**还有特殊功能**：

| 标签 | 作用 |
|---|---|
| `<bold>` `<italic>` `<underlined>` | 粗体、斜体、下划线 |
| `<newline>` | 换行 |
| `<click:run_command:/命令>` | 点击后执行命令 |
| `<hover:show_text:文字>` | 鼠标悬停显示文字 |
| `<lang:键名>` | 使用客户端自带的翻译 |

**两种可以混用**，比如默认配置里就有：

```yaml
spectate-return: "<click:run_command:/watchneko stopspectating><hover:show_text:\"/watchneko stopspectating\">\n%prefix% &f点击这里返回之前的位置\n</hover></click>"
```

这里面 `<click:...>` 是 MiniMessage，`&f` 是传统写法，`\n` 是换行。

---

## `prefix` — 前缀

```yaml
prefix: "&7[&bWatchNeko&7]"
```

**所有**信息前面的 `[WatchNeko]`。其他地方的 `%prefix%` 会替换成它。

想换成自己的服务器名：

```yaml
prefix: "&8[&6我的服务器&8]"
```

### 关于文件开头的注释

文件第一行是：

```yaml
# » 表示 » (相当于缩小版的 >>), ANSI和UTF-8的显示方式有所不同,有时你甚至可以看到乱码.
```

这是上游留下来的说明，讲的是 `»` 这个符号（两个小箭头）在某些控制台编码下会显示成乱码。**它和你的配置没有关系**，不用管。

---

## 作弊提醒的格式

这是**最重要**的三个设置。

### `alerts-format` — 提醒主行

```yaml
alerts-format: "%prefix% &f%player% &7failed &b%check_name%%experimental% &7[x&b%vl%&7]"
```

显示效果：

```text
[WatchNeko] Steve failed Reach [x12]
```

| 变量 | 变成 |
|---|---|
| `%prefix%` | 上面的前缀 |
| `%player%` | 玩家名 |
| `%check_name%` | 检测名，比如 `Reach` |
| `%experimental%` | 如果这个检测是实验性的，显示 `*`，否则什么都不显示 |
| `%vl%` | 违规次数 |

**想改成中文**：

```yaml
alerts-format: "%prefix% &e%player% &7被检测到 &c%check_name%%experimental% &7[&c%vl%&7次]"
```

效果：

```text
[WatchNeko] Steve 被检测到 Reach [12次]
```

### `alerts-format-proxy` — 跨服提醒

```yaml
alerts-format-proxy: "%prefix% &f[&cproxy&f] &f%player% &7failed &b%check_name%%experimental% &7[x&b%vl%&7]"
```

和上面一样，但**多一个 `[proxy]` 标记**，表示这条提醒来自其他子服。变量和上面相同。

### `alerts-hover-format` — 鼠标悬停显示的内容

```yaml
alerts-hover-format: "&f检查解释: &b%description%<newline>&f详细信息: <newline>&b&7%verbose%<newline>&f延迟: &b%ping%<newline>&f版本: &b%version%<newline>&f客户端型号: &b%brand%<newline>&f水平灵敏度: &b%h_sensitivity%%<newline>&f垂直灵敏度: &b%v_sensitivity%%<newline>&fFastMath: &b%fast_math%"
```

把鼠标停在提醒上会看到：

```text
检查解释: 玩家攻击距离过远
详细信息:
距离 3.14 方块
延迟: 46
版本: 1.8.9
客户端型号: lunarclient
水平灵敏度: 100%
垂直灵敏度: 100%
FastMath: false
```

| 变量 | 变成 |
|---|---|
| `%description%` | 这个检测是干什么的 |
| `%verbose%` | 额外数据，比如距离、角度。**不是所有检测都有** |
| `%player%` | 玩家名 |
| `%uuid%` | 玩家的 UUID |
| `%ping%` | 玩家延迟 |
| `%version%` | 玩家的游戏版本 |
| `%brand%` | 客户端型号（vanilla / lunarclient / forge 等） |
| `%client%` | 和 `%brand%` 完全一样，两种写法都行 |
| `%h_sensitivity%` | 玩家的**水平**鼠标灵敏度（0–200） |
| `%v_sensitivity%` | 玩家的**垂直**鼠标灵敏度（0–200） |
| `%fast_math%` | 客户端是否用了 FastMath，显示 `true` 或 `false`（OptiFine 相关） |
| `%tps%` | 服务器当前的 TPS，保留两位小数，比如 `19.98` |
| `%grim_version%` | 插件版本号，比如 `26.09.27.2` |

> 📌 **这份列表是整个插件通用的**。也就是说，下面 `profile` 面板、`grim-history-*` 里也能用这些变量。

> 💡 `%v_sensitivity%`（垂直灵敏度）和 `%h_sensitivity%`（水平灵敏度）显示的是玩家客户端设置里的数值（0–200）。**插件本身不对这两个值做任何判断**——它只是展示出来给你参考。

想精简成只显示关键信息：

```yaml
alerts-hover-format: "&f%description%<newline>&7%verbose%<newline>&f延迟: &b%ping% &f灵敏度: &b%v_sensitivity%%"
```

### `experimental-symbol`

```yaml
experimental-symbol: "*"
```

实验性检测后面跟的符号，出现在 `%experimental%` 的位置。想换成别的：

```yaml
experimental-symbol: " &7[&e实验性&7]"
```

---

## 开关类提示

这些是你输入命令开关功能时，插件回复你的话。

```yaml
alerts-enabled: "%prefix% &f警报 已开启"
alerts-disabled: "%prefix% &f警报 已禁用"
verbose-enabled: "%prefix% &f详细警报 已开启"
verbose-disabled: "%prefix% &f详细警报 已禁用"
brands-enabled: "%prefix% &f显示客户端名牌 已开启"
brands-disabled: "%prefix% &f显示客户端名牌 已禁用"
```

分别对应 `/watchneko alerts`、`/watchneko verbose`、`/watchneko brands` 三个命令的开关回执。

### `client-brand-format` — 客户端品牌提示

```yaml
client-brand-format: "%prefix% &f%player% 用 %brand% 加入了游戏"
```

玩家进服时的提示：

```text
[WatchNeko] Steve 用 lunarclient 加入了游戏
```

| 变量 | 变成 | 备注 |
|---|---|---|
| `%player%` | 玩家名 | |
| `%brand%` | 客户端型号 | |

**前提**：玩家得先有 `watchneko.brand` 权限才能看到这个提示，而且那个客户端不能在 `config.yml` 的 `ignored-clients` 里。

---

## 命令回执

### 重载

```yaml
reloading: "%prefix% &7重载配置中..."
reloaded: "%prefix% &f配置已经重载完毕."
reload-failed: "%prefix% &c重载配置失败."
```

### 找不到玩家

```yaml
player-not-found: "%prefix% &c指定玩家不存在或离线!"
player-not-this-server: "%prefix% &c指定玩家不在此服务器上！"
```

| 消息 | 什么时候出现 |
|---|---|
| `player-not-found` | 玩家被豁免、不在线、或者插件没在检测他 |
| `player-not-this-server` | 用 `spectate` 或 `profile` 指定了其他子服的玩家 |

### 旁观相关

```yaml
spectate-return: "<click:run_command:/watchneko stopspectating><hover:show_text:\"/watchneko stopspectating\">\n%prefix% &f点击这里返回之前的位置\n</hover></click>"
cannot-spectate-return: "%prefix% &c您只能在观看玩家后执行此操作"
cannot-run-on-self: "%prefix% &c你不能对自己使用此命令!"
```

`spectate-return` 是一条**可点击的消息**：进入旁观的瞬间发给你，点一下就能回到原来的位置。

拆开看：

```text
<click:run_command:/watchneko stopspectating>    ← 点击执行这个命令
<hover:show_text:"/watchneko stopspectating">    ← 悬停显示这个文字
\n%prefix% &f点击这里返回之前的位置\n            ← 实际显示的内容（前后换行）
</hover></click>                                  ← 关闭标签
```

> ⚠️ YAML 里双引号内部的引号要转义成 `\"`，所以写成了 `show_text:\"/watchneko stopspectating\"`。

### 日志上传

```yaml
upload-log: "%prefix% &f已经将日志文件上传至: %url%"
upload-log-start: "%prefix% &f上传中...请等待"
upload-log-not-found: "%prefix% &c找不到该日志."
upload-log-upload-failure: "%prefix% &c上载此日志时出错, 有关详细信息, 请参阅控制台"
```

用在 `/watchneko log <编号>` 和 `/watchneko dump` 上。`%url%` 会变成上传后的网址。

> **上传到哪里？** 默认上传到 `https://paste.grim.ac/`（上游 Grim 提供的粘贴服务）。如果你自己的服务器有粘贴服务，可以用启动参数改：`-Dwatchneko.paste-url=你的地址`。

### 权限/环境错误

```yaml
console-specify-target: "%prefix% &c您必须指定一个目标作为控制台!"
run-as-player: "%prefix% &c此命令只能由玩家使用！"
run-as-player-or-console: "%prefix% &c此命令只能由玩家或控制台使用！"
```

| 消息 | 什么时候出现 |
|---|---|
| `console-specify-target` | 控制台执行 `/watchneko debug` 却没写玩家名 |
| `run-as-player` | 控制台执行了只有玩家能用的命令（比如 `spectate`） |
| `run-as-player-or-console` | 其他执行者限制 |

---

## 踢出理由

```yaml
disconnect:
    timeout: "<lang:disconnect.timeout>"
    closed: "<lang:disconnect.closed>"
    error: "<red>An error occurred whilst processing packets. Please contact the administrators."
    blacklisted-forge: "<red>Your forge version is blacklisted due to inbuilt reach hacks.<newline><gold>Versions affected: 1.18.2-1.19.3<newline><newline><red>Please see https://github.com/MinecraftForge/MinecraftForge/issues/9309."
```

| 键 | 什么时候出现 |
|---|---|
| `timeout` | 玩家超过 `max-transaction-time` 没响应（默认 60 秒） |
| `closed` | 连接关闭 |
| `error` | 处理数据包时出错 |
| `blacklisted-forge` | 用了被拉黑的 Forge 版本（1.18.2–1.19.3，自带攻击距离修改） |

### 关于 `<lang:...>`

`<lang:disconnect.timeout>` 的意思是「**用玩家客户端自带的翻译**」。这样英文客户端看到英文，中文客户端看到中文，不用你自己翻译。

想改成自定义文字，直接写就行：

```yaml
disconnect:
    timeout: "&c你太久没响应服务器了，请检查网络后重连"
```

`blacklisted-forge` 这条默认是英文的（上游遗留），想改成中文：

```yaml
    blacklisted-forge: "<red>你的 Forge 版本内置了攻击距离修改，已被禁止进入。<newline><gold>受影响版本：1.18.2-1.19.3<newline><newline><red>详见 https://github.com/MinecraftForge/MinecraftForge/issues/9309"
```

---

## `profile` — 玩家信息面板

`/watchneko profile <玩家>` 显示的内容。**这是一个列表**，每一项是一行：

```yaml
profile:
    - "&7======================"
    - "&f%player% &b 的信息"
    - "&f延迟: &b%ping%"
    - "&f版本: &b%version%"
    - "&f客户端型号: &b%brand%"
    - "&f水平灵敏度: &b%h_sensitivity%%"
    - "&f垂直灵敏度: &b%v_sensitivity%%"
    - "&fFastMath: &b%fast_math%"
    - "&7======================"
```

变量和 `alerts-hover-format` 一样（见上面的[可用变量表](#可用变量)）。想删掉某一行，直接把那一行删掉；想加中文说明，改文字就行。

> ⚠️ **只有上面表格里列出的变量有效。** 写了不存在的变量（比如 `%violation_count%`），它会**原样显示**在游戏里，而不是被替换成数字。目前插件支持的变量就是那 13 个，没有「总违规次数」这类额外变量——想看历史请用 `/watchneko history`。

---

## `help` — 帮助菜单

`/watchneko help` 显示的内容。同样是列表：

```yaml
help:
    - "&7======================"
    - "/watchneko alerts &f- &7显示/隐藏 警报"
    - "/watchneko brands &f- &7显示/隐藏 客户端名牌"
    - "/watchneko profile <player> &f- &7查看玩家信息"
    - "/watchneko help &f- &7查看此帮助消息"
    - "/watchneko debug <player> &f- &7开发者预测输出"
    - "/watchneko perf &f- &7开发者毫秒预测"
    - "/watchneko reload &f- &7重新加载配置"
    - "/watchneko spectate <player> &f- &7观看玩家"
    - "/watchneko verbose &f- &7显示无缓冲区的每个拉回"
    - "/watchneko log [1-999] &f- &7预测标志的调试日志"
    - "/watchneko history <player> [页码] &f- &7显示玩家之前的警报"
    - "&7======================"
```

**这个列表和实际命令是分开的**——你改了命令的权限，这里不会自动更新。想让帮助菜单只显示你的管理员用得上的命令，直接编辑这个列表。

想加一条中文说明：

```yaml
help:
    - "&7====== &bWatchNeko &7======"
    - "&b/watchneko alerts &7- 开关作弊提醒"
    - "&b/watchneko history <玩家> &7- 查这人作弊过几次"
    - "&b/watchneko spectate <玩家> &7- 悄悄盯着他"
    - "&b/watchneko reload &7- 改完配置重载"
    - "&7======================"
```

---

## `history` — 历史记录显示

支撑 `/watchneko history` 命令。

```yaml
grim-history-load-failure: "%prefix% &c历史子系统加载失败！请检查服务器控制台中的错误。"
grim-history-disabled: "%prefix% &c历史子系统已禁用！"
grim-history-header: "%prefix% &b显示 &f%player% 的日志 (&f%page%&b/&f%maxPages%&f)"
grim-history-entry: "%prefix% &8[&f%server%&8] &b失败 &f%check% (x&c%vl%&f) &7%verbose% (&b%timeago% 前&7)"
```

| 键 | 什么时候出现 |
|---|---|
| `grim-history-disabled` | `config.yml` 里 `history.enabled` 是 `false` |
| `grim-history-load-failure` | 数据库连不上（通常是 MySQL 配置错了） |
| `grim-history-header` | 每一页的标题 |
| `grim-history-entry` | 每一条记录 |

### `grim-history-header` 的变量

| 变量 | 变成 |
|---|---|
| `%player%` | 玩家名 |
| `%page%` | 当前页码 |
| `%maxPages%` | 总页数 |

### `grim-history-entry` 的变量

| 变量 | 变成 |
|---|---|
| `%server%` | 服务器名（`config.yml` 里的 `history.server-name`） |
| `%check%` | 检测名 |
| `%vl%` | 当时的违规次数 |
| `%verbose%` | 额外信息 |
| `%timeago%` | 多久以前，比如 `5m`、`2h`、`3d` |
| `%grim_version%` | 当时的插件版本 |
| `%client_brand%` | 客户端型号 |
| `%client_version%` | 客户端版本 |
| `%server_version%` | 服务器版本 |

默认的效果：

```text
[WatchNeko] 显示 Steve 的日志 (1/3)
[WatchNeko] [Prison] 失败 Reach (x12) 3.14000 blocks (5m 前)
```

> `3.14000 blocks` 是 Reach 检测自己输出的额外信息。**注意这部分是英文的、且格式由各个检测自己决定**——有的检测没有额外信息，那个位置就是空的。

**想精简**：

```yaml
grim-history-entry: "&8[&b%timeago%前&8] &f%check% &7x%vl%"
```

效果：`[5m前] Reach x12`

---

## 完整可复制示例

一套**完全中文化**的配置，可以直接复制替换：

```yaml
# 前缀
prefix: "&8[&b反作弊&8]"

# 开关提示
alerts-enabled: "%prefix% &a✓ 作弊提醒已开启"
alerts-disabled: "%prefix% &c✗ 作弊提醒已关闭"
verbose-enabled: "%prefix% &a✓ 详细模式已开启 &7(会刷屏)"
verbose-disabled: "%prefix% &c✗ 详细模式已关闭"
brands-enabled: "%prefix% &a✓ 客户端提示已开启"
brands-disabled: "%prefix% &c✗ 客户端提示已关闭"
client-brand-format: "%prefix% &7玩家 &f%player% &7使用 &b%brand% &7进入游戏"

# 命令回执
console-specify-target: "%prefix% &c控制台必须指定玩家名"
reloading: "%prefix% &7正在重载..."
reloaded: "%prefix% &a✓ 配置已重载"
reload-failed: "%prefix% &c✗ 重载失败，请检查控制台"
player-not-found: "%prefix% &c玩家不存在或已离线"
player-not-this-server: "%prefix% &c该玩家不在本服务器"
spectate-return: "<click:run_command:/watchneko stopspectating><hover:show_text:\"点我回去\">\n%prefix% &a点击这里返回原来的位置\n</hover></click>"
cannot-spectate-return: "%prefix% &c你当前不在旁观状态"
cannot-run-on-self: "%prefix% &c不能对自己使用"
upload-log: "%prefix% &a✓ 日志已上传: &f%url%"
upload-log-start: "%prefix% &7上传中，请稍候..."
upload-log-not-found: "%prefix% &c找不到这条日志"
upload-log-upload-failure: "%prefix% &c上传失败，详见控制台"
run-as-player: "%prefix% &c此命令只能由玩家执行"
run-as-player-or-console: "%prefix% &c执行者不允许"

# 踢出理由
disconnect:
    timeout: "<lang:disconnect.timeout>"
    closed: "<lang:disconnect.closed>"
    error: "<red>处理数据包时出错，请联系管理员"
    blacklisted-forge: "<red>你的 Forge 版本自带攻击距离修改，已被禁止进入<newline><gold>受影响版本：1.18.2 - 1.19.3"

# 提醒格式
alerts-format: "%prefix% &f%player% &7被检测到 &c%check_name%%experimental% &7[&c%vl%&7次]"
alerts-format-proxy: "%prefix% &8[&6跨服&8] &f%player% &7被检测到 &c%check_name%%experimental% &7[&c%vl%&7次]"
alerts-hover-format: "&7检查：&b%description%<newline>&7详情：&b%verbose%<newline>&7延迟：&b%ping% &7灵敏度：&b%v_sensitivity%% &7版本：&b%version%"
experimental-symbol: " &e[实验性]"

# 玩家信息
profile:
    - "&7========== &b玩家信息 &7=========="
    - "&7玩家：&f%player%"
    - "&7延迟：&b%ping% ms"
    - "&7版本：&b%version%"
    - "&7客户端：&b%brand%"
    - "&7灵敏度：&b水平 %h_sensitivity%% &7/ &b垂直 %v_sensitivity%%"
    - "&7FastMath：&b%fast_math%"
    - "&7================================"

# 帮助菜单
help:
    - "&7========== &bWatchNeko &7=========="
    - "&b/watchneko alerts &7- 开关作弊提醒"
    - "&b/watchneko history <玩家> [页码] &7- 查看违规历史"
    - "&b/watchneko spectate <玩家> &7- 悄悄旁观"
    - "&b/watchneko stopspectating &7- 结束旁观"
    - "&b/watchneko profile <玩家> &7- 查看客户端信息"
    - "&b/watchneko verbose &7- 详细模式（会刷屏）"
    - "&b/watchneko log <编号> &7- 上传调试日志"
    - "&b/watchneko dump &7- 生成诊断报告"
    - "&b/watchneko brands &7- 开关客户端提示"
    - "&b/watchneko reload &7- 重载配置"
    - "&7================================"

# 历史记录
grim-history-load-failure: "%prefix% &c历史子系统加载失败，请检查控制台"
grim-history-disabled: "%prefix% &c历史子系统已禁用"
grim-history-header: "%prefix% &f%player% &7的记录 &8(&f%page%&7/&f%maxPages%&8)"
grim-history-entry: "%prefix% &8[&7%server%&8] &c%check% &7x&c%vl% &8| &7%verbose% &8| &f%timeago%前"
```

---

## 排错

| 现象 | 原因 |
|---|---|
| 显示成了 `&c` 这样的乱码 | 用了 `&` 但插件没解析——检查是不是漏了引号，或者 YAML 语法错误 |
| 中文变成问号 | 文件编码不是 UTF-8。用支持 UTF-8 的编辑器（VS Code、Notepad++）另存为 UTF-8 |
| 悬停文字不显示 | `config.yml` 里 `alerts.hover.enabled` 是 `false` |
| 点击没反应 | `config.yml` 里 `alerts.click.enabled` 是 `false` |
| 提醒里 `%verbose%` 是空的 | 正常——不是所有检测都会提供额外信息 |
| `<newline>` 直接显示出来了 | 写法有误，注意是尖括号包起来的 `<newline>`，不是 `%newline%` |

---

[⬅️ punishments.yml 详解](punishments.md) · [discord.yml 详解 ➡️](discord.md)
