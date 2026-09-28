# `config.yml` 详解

这是 WatchNeko 的**主配置文件**，控制所有检测的开关、灵敏度、性能表现和数据库。

位置：`plugins/WatchNeko/config.yml`

改完之后输入 `/watchneko reload` 生效（**数据库设置除外**，那个必须重启服务器）。

> ⚠️ 文件末尾有一行 `config-version: 9`，**不要动它**。插件靠这个数字判断你的配置是不是旧版本，需要时自动升级。

---

## 目录

- [alerts — 提醒](#alerts--提醒)
- [verbose — 详细模式](#verbose--详细模式)
- [check-for-updates — 更新检查](#check-for-updates--更新检查)
- [client-brand — 客户端品牌](#client-brand--客户端品牌)
- [spectators — 旁观者隐藏](#spectators--旁观者隐藏)
- [max-transaction-time — 超时踢出](#max-transaction-time--超时踢出)
- [数据包相关](#数据包相关)
- [Simulation — 移动模拟](#simulation--移动模拟)
- [其余检测的单独设置](#其余检测的单独设置)
- [exploit — 漏洞缓解](#exploit--漏洞缓解)
- [实验性检查](#实验性检查)
- [物品使用重置](#物品使用重置)
- [数据包限制](#数据包限制)
- [飞行与鞘翅的延迟限制](#飞行与鞘翅的延迟限制)
- [history — 违规历史子系统](#history--违规历史子系统)

---

## `alerts` — 提醒

```yaml
alerts:
    print-to-console: true
    proxy:
        send: false
        receive: false
    hover:
        enabled: true
    click:
        enabled: true
        command: "/watchneko spectate %player%"
```

| 设置 | 默认 | 说明 |
|---|---|---|
| `print-to-console` | `true` | 除了发给有权限的玩家，是否**同时在控制台打印**作弊信息。开着方便查日志 |
| `proxy.send` | `false` | 是否把本服的作弊信息**发给同一代理下的其他子服** |
| `proxy.receive` | `false` | 是否**接收**其他子服发来的作弊信息 |
| `hover.enabled` | `true` | 鼠标悬停在提醒上时，是否显示详细信息（检查名称、延迟、版本、客户端、灵敏度等） |
| `click.enabled` | `true` | 提醒是否**可点击** |
| `click.command` | `/watchneko spectate %player%` | 点击后执行什么命令。`%player%` 会被替换成作弊玩家名字 |

### 跨服提醒怎么配

如果你用 BungeeCord 或 Velocity 开了多个子服，想让 A 服的管理员也能看到 B 服的作弊提醒：

**每个子服**都这样设置：

```yaml
alerts:
    proxy:
        send: true      # 把本服的作弊信息发出去
        receive: true   # 接收别的服发来的
```

> **Velocity 用户注意**：必须在 Velocity 的配置里启用 `bungee-plugin-message-channel`，否则跨服消息传不过去。

### 提醒的显示效果

`hover` 打开后，把鼠标停在提醒上会看到：

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

这些文字在 [`messages.yml`](messages.md) 的 `alerts-hover-format` 里改。

---

## `verbose` — 详细模式

```yaml
verbose:
    print-to-console: false
```

| 设置 | 默认 | 说明 |
|---|---|---|
| `print-to-console` | `false` | 玩家开启 verbose 后，是否**同时打到控制台** |

**详细模式是什么**：平时检测攒够一定次数才提醒一次（防止刷屏），开启 verbose 后**每一次拉回都显示**，不做缓冲。排查「这人到底有没有问题」的时候用。

设置成 `true` 会让控制台很吵，建议排查问题时临时打开，查完就关。

---

## `check-for-updates` — 启动时打印版本

```yaml
check-for-updates: true
```

| 值 | 效果 |
|---|---|
| `true`（默认） | 服务器启动时，在**控制台打印一次当前版本号** |
| `false` | 不打印 |

> ⚠️ **注意**：这个选项**并不会真的去检查有没有新版本**。名字和上游 Grim 一样，但在这个分支里，它只负责打印版本号。想知道有没有新版，请自己看 [Releases 页面](https://github.com/jiuxian1337/WatchNeko/releases)。

---

## `client-brand` — 客户端品牌

```yaml
client-brand:
    ignored-clients:
        - "^vanilla$"
        - "^fabric$"
        - "^lunarclient:v\\d+\\.\\d+\\.\\d+-\\d{4}$"
        - "^Feather Fabric$"
        - "^labymod$"
    disconnect-blacklisted-forge-versions: true
```

玩家进服时会显示「某某用某客户端加入了游戏」。这个设置决定**哪些客户端不提示**。

| 设置 | 默认 | 说明 |
|---|---|---|
| `ignored-clients` | 见上 | 匹配这些**正则表达式**的客户端不提示 |
| `disconnect-blacklisted-forge-versions` | `true` | 禁止特定 Forge 版本进入 |

### `ignored-clients` 怎么改

这里填的是**正则表达式**，不是普通文字。默认忽略了原版、Fabric、Lunar、Feather、LabyMod——因为用这些的人太多，全提示会刷屏。

**想让所有人都不提示**（等于关掉这个功能）：

```yaml
    ignored-clients:
        - ".*"
```

`.*` 表示「匹配任何内容」，所以所有客户端都被忽略。

**想只提示 Lunar 客户端**（反过来做，其他都不提示）：

```yaml
    ignored-clients:
        - "^(?!lunarclient).*$"
```

**常见客户端的匹配写法**：

默认文件里已经写好的这 5 条是**这个插件唯一已知的客户端名字**（从默认配置里抄的）：

| 客户端 | 正则 |
|---|---|
| 原版 | `^vanilla$` |
| Fabric | `^fabric$` |
| Lunar Client | `^lunarclient:v\\d+\\.\\d+\\.\\d+-\\d{4}$` |
| LabyMod | `^labymod$` |
| Feather | `^Feather Fabric$` |

> ⚠️ **其他客户端（Badlion、Forge 等）的品牌字符串我没有在代码里找到依据**，别照抄网上的值。
>
> 想确认某个客户端的准确名字，有个简单办法：**让它进服，看聊天栏显示的 `%brand%` 是什么**（需要先清空 `ignored-clients`），把那串字原样填进来即可。

> 写成 `^名字$` 表示「完全等于这个名字」。注意 YAML 里反斜杠要写两个（`\\d` 而不是 `\d`）。

### `disconnect-blacklisted-forge-versions`

黑名单：**Forge 1.18.2 – 1.19.3**。这些版本的 Forge 内置了**攻击距离修改**功能，等于自带外挂。

| 值 | 效果 |
|---|---|
| `true`（默认，**推荐**） | 这些 Forge 版本的玩家**进不来服务器** |
| `false` | 允许他们进来。**自行承担风险**——他们天然有比正常玩家更远的攻击距离 |

报错信息会告诉玩家为什么进不来，可以在 `messages.yml` 的 `disconnect.blacklisted-forge` 里改。

---

## `spectators` — 旁观者隐藏

```yaml
spectators:
    hide-regardless: false
    allowed-worlds:
        - ""
```

用 `/watchneko spectate` 旁观玩家时，你在他的玩家列表里是**隐藏的**（你看得见他，他看不见你）。这两个设置控制隐藏规则。

| 设置 | 默认 | 说明 |
|---|---|---|
| `hide-regardless` | `false` | `true` = 有 **`watchneko.spectate`** 权限的人**一进服就永远隐藏**，不管他在不在旁观 |
| `allowed-worlds` | `- ""` | 见下方说明——**代码行为和文件里的注释是反的** |

> ⚠️ **两点和文件里的注释对不上，以这里为准（下面都是从代码里读出来的）：**
>
> **1. `hide-regardless` 认的权限是 `watchneko.spectate`，不是 `watchneko.spectator`。**
> 配置文件注释里写的是 `watchneko.spectator`，但代码里检查的是 `watchneko.spectate`。给错权限不会生效。
>
> **2. `allowed-worlds` 的语义和注释相反。**
> 文件里的注释写「观察者在这些世界**不会**隐藏」，但代码的逻辑是：**只有在这个列表里的世界才会隐藏**。
>
> | 列表内容 | 实际效果 |
> |---|---|
> | `- ""`（默认） | 列表视为空 → **所有世界都隐藏** |
> | `- "arena"` | **只在 arena 隐藏**，其他世界谁都能看见你 |
>
> 所以这个选项的作用是「**限定只在某些世界隐藏**」，不是「在这些世界不要隐藏」。填多个就限定多个世界：

```yaml
    allowed-worlds:
        - "arena"
        - "pvp_world"
```

> 另外：旁观者之间**互相可见**（两个管理员同时旁观时能看到对方），这条也是代码里写死的。

---

## `max-transaction-time` — 超时踢出

```yaml
max-transaction-time: 60
```

**玩家多久没回应反作弊的确认数据包，就把他踢出。** 单位：秒。

踢出原因是 `disconnect.timeout`（可以在 `messages.yml` 里改）

| 值 | 说明 |
|---|---|
| `60`（默认） | 一分钟 |
| 调**大** | 网络差的玩家不容易被误踢，但网络卡顿的外挂能活更久 |
| 调**小** | 外挂更容易断线，但可能误踢高延迟玩家 |

> **正常不用改。** 除非你的服务器玩家普遍延迟极高（比如跨国），可以调到 120。

> ⚠️ **有效范围是 1–180**。填超出这个范围的数字，插件会把它夹回范围内，并在控制台警告：
>
> ```text
> Detected invalid max-transaction-time! This setting is clamped between 1 and 180 to prevent issues.
> ```
>
> 设太高会导致内存占用问题。

---

## 数据包相关

```yaml
disable-pong-cancelling: false
cancel-duplicate-packet: true
ignore-duplicate-packet-rotation: false
```

| 设置 | 默认 | 说明 |
|---|---|---|
| `disable-pong-cancelling` | `false` | 阻止插件取消玩家发来的 pong 响应。**除非你明确知道自己在干什么，否则别动** |
| `cancel-duplicate-packet` | `true` | 撤销重复的移动数据包。修复 1.8 服务器上 1.17–1.20.5 玩家的「桶不同步」问题（[MC-12363](https://bugs.mojang.com/browse/MC-12363)）。Mojang 已在 1.21 修复，所以这项只对旧版本生效 |
| `ignore-duplicate-packet-rotation` | `false` | 处理重复数据包时，是否**忽略里面的转头数据** |

`disable-pong-cancelling` 的说明（翻译自原文）：**开启它可以改善和其他反作弊的兼容性，但可能导致数据包限制器出问题。不确定的话不要开。**

---

## `mitigate-damage` — ⚠️ 当前版本无效

```yaml
mitigate-damage:
    enabled: true
```

> ⚠️ **这个设置在当前版本里不生效，改了没有任何区别。**
>
> 代码里 `mitigateDamage()` 这个方法会在约 100 处被检测调用，但它只做一件事：把一个叫 `mitigateDamageTime` 的内部变量设成「当前时间 + 2000 毫秒」。**这个变量在全部代码里没有任何地方读取**——写了就没人用。
>
> 所以无论 `enabled` 填 `true` 还是 `false`，玩家行为都不会有任何变化。这个功能应该是从上游继承下来后改造了一半，暂时保持默认即可。

---

## `Simulation` — 移动模拟

**这是整个插件最核心的检测**，判断玩家移动是否违反游戏物理。抓 Fly、Speed、Step 等。

```yaml
Simulation:
    setback-decay-multiplier: 0.999
    threshold: 0.03
    immediate-setback-threshold: -1
    max-advantage: 3
    max-ceiling: 4
    setback-violation-threshold: 1
```

| 设置 | 默认 | 说明 |
|---|---|---|
| `threshold` | `0.03` | **判定阈值**，单位是方块。玩家偏移超过这个值就记录 |
| `setback-decay-multiplier` | `0.999` | 玩家**表现正常时**，累计违规乘以多少（相当于缓慢清零） |
| `immediate-setback-threshold` | `-1`（关闭） | 单次偏移超过多少就**立刻拉回**。`-1` = 关闭 |
| `max-advantage` | `3` | 累计偏移达到多少就拉回 |
| `max-ceiling` | `4` | 累计违规的**上限**，防止玩家攒一堆违规永远清不掉 |
| `setback-violation-threshold` | `1` | 达到多少违规等级才拉回。`1` 是旧行为 |

### 这些数字是什么意思

**「优势（advantage）」** = 玩家实际位置比模拟位置多走的距离，单位是方块。

- 每次玩家移动，插件算出「按照游戏物理他最多能走到哪」，然后和他实际位置比
- 差得越多，优势越大
- 优势累计到 `max-advantage` 就拉回
- 玩家合法移动时，优势按 `setback-decay-multiplier` 慢慢衰减

**默认配置下的衰减曲线**：50 秒后，玩家的容忍度从 4 格降到 1 格。作者提供了可视化图表：
- [衰减曲线](https://www.desmos.com/calculator/4lovswdarj)
- [违规累积曲线](https://www.desmos.com/calculator/d4ufgxrxer)

### 什么时候需要调

| 症状 | 怎么调 |
|---|---|
| 玩家被误拉回（尤其用 OptiFine 的） | 把 `threshold` 调大，比如 `0.05` |
| OptiFine 的 FastMath 误报 | `threshold` 设到 `0.001` 可以减少误报 |
| 漏掉外挂 | 把 `threshold` 调小，但误报会变多 |
| 玩家被拉回得太晚 | 设置 `immediate-setback-threshold`，比如 `0.07` |

**默认值经过大量测试，没有明确问题不要改。**

---

## 其余检测的单独设置

### `Phase` — 穿墙

```yaml
Phase:
    setbackvl: 1
    decay: 0.005
```

检查玩家是否穿墙。`setbackvl: 1` 表示违规 1 次就拉回（因为错误方块可能让人爬墙，这个检查相对稳定，可以激进一点）。

### `NoFallB` — 摔落伤害伪造

```yaml
NoFallB:
    ignore-teleport: false
```

| 值 | 说明 |
|---|---|
| `false`（默认） | 正常检测 |
| `true` | 忽略传送导致的摔落距离重置。**如果玩家莫名其妙摔伤，改成 `true` 并去 GitHub 提 issue** |

### 方块放置类

```yaml
AirLiquidPlace:
    cancelvl: 0
FabricatedPlace:
    cancelvl: 5
FarPlace:
    cancelvl: 5
PositionPlace:
    cancelvl: 5
RotationPlace:
    cancelvl: 5
```

`cancelvl` = 违规达到多少次后，**取消这个玩家的方块放置**。

> 🚨 **注意 `0` 的含义和直觉相反。** 代码里的判断是 `cancelVL >= 0 && violations >= cancelVL`：
>
> | 值 | 实际效果 |
> |---|---|
> | `-1` | **永不取消**（唯一能关闭的值） |
> | `0` | **从第 1 次违规就取消**（最激进） |
> | `5` | 违规满 5 次后取消 |
>
> 所以默认配置里 `AirLiquidPlace: cancelvl: 0` 表示**它一抓到就取消放方块**，不是「只记录不取消」。

### `ScaffoldA` – `ScaffoldD` — 自动搭桥

```yaml
ScaffoldA:
    cancelvl: 5
    cancel-for-ms: 500
```

| 设置 | 说明 |
|---|---|
| `cancelvl` | 违规多少次后触发取消（`-1` 关闭，`0` = 第一次就触发） |
| `cancel-for-ms` | 触发后，**持续取消放置多少毫秒**。**填 `0` 或负数等于关闭这项功能** |

> 🚨 **`cancel-for-ms` 的作用范围是全服，不是单个玩家。**
>
> 代码里控制「取消到什么时候」的变量是 `static`（全服共用一份）。只要有**任何一个人**触发了 Scaffold 的取消，接下来 `cancel-for-ms` 毫秒内，**服务器上所有玩家**低头放方块都会被取消（客户端方块会被重新同步）。
>
> 这可能导致的现象：某个玩家触发了搭桥检测，然后**全服玩家都感觉放方块「回弹」了一下**。
>
> 缓解办法：把 `cancel-for-ms` 調小（比如 100），或者用 `cancelvl: -1` 关掉取消、只保留检测。

`ScaffoldB` 还有更细的开关：

```yaml
ScaffoldB:
    angle: true            # 检查放置时是否没朝向支撑方块面
    time:
        active: true       # 检查低头连续搭路的放块间隔
        average: 2         # 平均间隔低于多少 tick 判定
    sprint: true           # 检查冲刺后短时间向下放块
    rotate:
        active: true       # 检查放置后下一 tick 的水平转向变化
        difference: 90     # 触发判定的最小偏航差值
    tool-switch: true      # 检查放置后是否切换了快捷栏
    cancelvl: 10
    cancel-for-ms: 500
```

想临时关掉某一项，把它设成 `false` 即可。

### `NoSlowA` — 用物品不减速

```yaml
NoSlowA:
    threshold: 0.01
    setbackvl: 0
    decay: 0.25
```

吃东西、拉弓、喝药时应该减速，外挂会取消这个减速。

| 设置 | 说明 |
|---|---|
| `threshold` | 多少偏移算作弊。作者说 **0.03–0.2 之间的标志值和原版 NoSlow 一致** |
| `setbackvl` | 违规多少次拉回。`0` = 不拉回 |
| `decay` | 玩家正常使用物品并被减速时，违规值衰减多少 |

### `VelocityA` / `VelocityB` — 击退

检测「被打不后退」外挂（AntiKnockback）。

> ✅ **从 `26.09.29.2` 起，这两段配置可以正常生效了。**
>
> 在那之前，代码读取的键名是 `Knockback.*` 和 `Explosion.*`，和配置文件里的段名对不上，导致 `VelocityA:` / `VelocityB:` 两段**写了也没用**。这个 bug 已经修掉，现在代码读的就是这两个段。

```yaml
VelocityA:
    setback-decay-multiplier: 0.999
    threshold: 0.03
    immediate-setback-threshold: 0.1
    max-advantage: 1
    max-ceiling: 4
VelocityB:
    threshold: 0.0003
    # setbackvl 没写就默认 10
```

| 设置 | 说明 |
|---|---|
| `threshold` | 判定阈值，单位方块。偏移超过它就记录 |
| `max-advantage` | 累计偏移达到多少就拉回 |
| `immediate-setback-threshold` | 单次偏移超过多少立刻拉回 |
| `setback-decay-multiplier` | 表现正常时累计值衰减的倍率 |
| `max-ceiling` | 累计值的上限 |
| `setbackvl`（仅 VelocityB） | 违规多少次后拉回 |

> ⚠️ **`threshold` 的量级很小，改的时候别按直觉来。**
>
> - `VelocityA` 默认 `0.03`
> - `VelocityB` 默认 `0.0003`（**注意小数点后多两个零**）
>
> 这两个值如果写错一个数量级，检测要么完全失效要么疯狂误报。改之前先记下原值。
>
> `max-advantage` / `immediate-setback-threshold` 填负数会被当成 `Double.MAX_VALUE`（等于关闭）。

### `TimerA` / `TimerLimit` / `NegativeTimer` / `VehicleTimer` — 时间加速

```yaml
TimerA:
    setbackvl: 10
    drift: 120
TimerLimit:
    ping-abuse-limit-threshold: 1000
NegativeTimer:
    drift: 1200
VehicleTimer:
    setbackvl: 10
```

| 设置 | 说明 |
|---|---|
| `TimerA.drift` | 玩家卡顿时**可以攒下来的毫秒数**，供之后补偿 |
| `TimerLimit.ping-abuse-limit-threshold` | 检查延迟时对 timer 余额的限制。**合法玩家延迟超过这个值可能被误拉回**，填 `-1` 关闭 |

> ⚠️ **`drift` 不能设太高**。原文警告：设太高可能让 1.8 服务器的「快速使用 / 快速治疗 / 快速弓箭」绕过检测。**120 毫秒是比较好的平衡点。**

### `Reach` — 攻击距离

```yaml
Reach:
    threshold: 0.03
    block-impossible-hits: true
    enable-post-packet: false
```

| 设置 | 说明 |
|---|---|
| `threshold` | 碰撞箱扩大多少。`0.0005` 能检测到 3.0005 以上的攻击距离 |
| `block-impossible-hits` | 是否**取消**那些明确不可能的攻击 |
| `enable-post-packet` | 每个 tick 末多发一个数据包来检查。**会增加所有玩家的带宽占用** |

**关于 `enable-post-packet`**：

- 开启能抓到更多外挂，**不会**导致误报，**不会**降低服务器性能，但**会增加带宽使用**
- 关闭也能抓到外挂，只是少一点
- **除非你是专注 1.8 PvP 的服务器，否则不建议开**

> 关于 `threshold: 0.03`：1.9–1.18.1（不含 1.18.2）或某些客户端/服务器组合存在 **0.03 的距离增加**，这是协议变更导致的。这个检查在 **1.8 服务器上的 1.7/1.8 客户端**效果最好。

### `PingSpoofB` / `C` / `D` — 延迟伪造

```yaml
PingSpoofB:
    cancelVL: 0
PingSpoofC:
    cancelVL: 0
PingSpoofD:
    cancelVL: 0
    base-pending-allowance: 8
    max-ping-ticks: 20
```

`PingSpoofD` 的两个参数：

- `base-pending-allowance`：叠加 ping 容错之前，基础允许的待确认事务数
- `max-ping-ticks`：根据 ping 推导的额外容错**最多**这么多个 50ms tick

> 📌 这三个的 `cancelVL: 0` 同样是**「第一次违规就取消」**的意思（和上面 `cancelvl` 规则一致，只是大小写不同）。想关掉就填 `-1`。

### `InteractA` / `InteractB` — 交互

```yaml
InteractA:
    cancelVL: 0
InteractB:
    cancelVL: 0
```

> 📌 同上：`0` = 第一次违规就取消，`-1` = 永不取消。

### `AutoclickerA` — 连点器

```yaml
AutoclickerA:
    max-delay-ticks: 25
    stack-size: 100
    window-size: 20
    require-move-within-ms: 500
    require-attack-within-ms: 7000
    entropy-jiff-min: 0.04
    entropy-jiff-max: 0.06
```

| 设置 | 说明 |
|---|---|
| `max-delay-ticks` | 收集挥手延迟，`ticks = 毫秒 / 50`，大于等于这个值的不计入 |
| `stack-size` | 收集多少个样本后开始分析 |
| `window-size` | 每个窗口的样本数（用于峰度/熵分析） |
| `require-move-within-ms` | 需要最近这么多毫秒内有移动包 |
| `require-attack-within-ms` | 需要最近这么多毫秒内有攻击包 |
| `entropy-jiff-min` / `max` | 熵的判定区间 |

**看不懂这些参数很正常**——它们是通过统计学方法分析点击规律的。改动前建议先用 `/watchneko verbose` 观察一段时间，确认当前默认值在你服务器上是否误报。

### 瞄准检测

```yaml
AimG:
    max-streak: 12
    vl-for-streak: 2
AimK:
    minimum-level-threshold: 90
AimS:
    buffer: 7
    min-deltaX: 1.0
    max-deltaY: 0.0001
    ratio-threshold: 1000.0
    window-size: 5
```

| 检查 | 设置 | 说明 |
|---|---|---|
| `AimG` | `max-streak` | 存在有效水平转动时，检测连续下压式的极小 pitch 平滑，最多连续多少次 |
| `AimG` | `vl-for-streak` | 连续多少次算违规 |
| `AimK` | `minimum-level-threshold` | 检测窗口内极小旋转过多且平均转动过低的异常平滑模式 |
| `AimS` | `buffer` | 缓冲值 |
| `AimS` | `min-deltaX` / `max-deltaY` | 水平/垂直转动的最小/最大变化量 |
| `AimS` | `ratio-threshold` | yaw 与 pitch 的比值阈值 |
| `AimS` | `window-size` | 检测窗口大小 |

---

## `exploit` — 漏洞缓解

```yaml
exploit:
    allow-sprint-jumping-when-using-elytra: true
    allow-building-on-ghostblocks: true
    distance-to-check-if-ghostblocks: 2
```

| 设置 | 默认 | 说明 |
|---|---|---|
| `allow-sprint-jumping-when-using-elytra` | `true` | 是否允许玩家用鞘翅时疾跑跳跃 |
| `allow-building-on-ghostblocks` | `true` | 遇到幽灵方块时重新同步玩家位置，减少玩家卡在幽灵方块上的情况 |
| `distance-to-check-if-ghostblocks` | `2` | 检查幽灵方块的距离，单位方块 |

**幽灵方块是什么**：服务端认为那里没方块，客户端认为有，玩家就会卡在半空中。这两个设置是**缓解措施**，不是检测。

---

## 实验性检查

```yaml
experimental-checks: false
```

| 值 | 效果 |
|---|---|
| `false`（默认） | 不启用实验性检测 |
| `true` | **启用实验性检测** |

实验性检测在提醒里会带一个 `*` 号（符号可以在 `messages.yml` 的 `experimental-symbol` 里改）。

> ⚠️ **实验性检测误报率可能较高**。开之前建议先配合 `verbose` 模式观察一段时间。

---

## 物品使用重置

```yaml
reset-item-usage-on-item-update: true
reset-item-usage-on-attack: true
reset-item-usage-on-slot-change: true
```

取消有问题的格挡状态。三项分别对应：物品更新时、攻击时、切换快捷栏时。

**没遇到相关问题不要动。**

---

## 数据包限制

```yaml
packet-spam-threshold: 100
debug-packet-cancel: false
```

| 设置 | 说明 |
|---|---|
| `packet-spam-threshold` | 一秒内取消多少个非法数据包就**踢掉这个玩家** |
| `debug-packet-cancel` | 玩家因此被踢时，是否打印堆栈追踪 |

**为什么需要这个**：有些数据包限制器不计算被反作弊取消的数据包，外挂可以借此刷包。默认 100。

---

## 飞行与鞘翅的延迟限制

```yaml
max-ping-out-of-flying: 1000
max-ping-firework-boost: 1000
```

| 设置 | 说明 |
|---|---|
| `max-ping-out-of-flying` | 被设为非飞行状态的玩家，ping 不能超过这个值（毫秒）。因为**插件目前不检查处于飞行状态的玩家**。填 `-1` 关闭 |
| `max-ping-firework-boost` | 限制用鞘翅时烟花加速的延迟。**解决高延迟玩家一个烟花就能一直加速的问题**。填 `-1` 关闭 |

两个都是 `-1` 关闭，默认 1000 毫秒。

---

## `history` — 违规历史子系统

支撑 `/watchneko history` 命令。

```yaml
history:
    enabled: true
    entries-per-page: 15
    server-name: Prison
    database:
        type: SQLITE
        host: localhost
        port: 3306
        database: grim
        username: root
        password: ""
```

| 设置 | 默认 | 说明 |
|---|---|---|
| `enabled` | `true` | 是否启用历史记录。关掉后 `/watchneko history` 会提示「历史子系统已禁用」 |
| `entries-per-page` | `15` | `/watchneko history` 每页显示多少条 |
| `server-name` | `Prison` | 记录里显示的服务器的名字。**多个服务器共用一个数据库时很有用**，这样能看出是哪个服记的 |
| `database.type` | `SQLITE` | `SQLITE` = 本地文件存储；`MYSQL` = 外部数据库 |

### 什么时候用 MySQL

| 场景 | 选什么 |
|---|---|
| 单个服务器 | `SQLITE`（默认，零配置） |
| 多个子服想共用一个违规记录 | `MYSQL` |

用 SQLite 时，数据存在 `plugins/WatchNeko/violations.sqlite`。

> ⚠️ **记录只增不减。** 代码里**没有任何自动清理逻辑**——`remove-violations-after` 只负责重置「违规次数」，**不会删除历史数据库里的记录**。跑得久了这个文件会一直变大，需要你自己定期处理（删文件或写 SQL 清理）。
>
> 想控制体积，可以把 `enabled` 设成 `false` 彻底关掉记录功能。

### MySQL 配置

```yaml
    database:
        type: MYSQL
        host: 127.0.0.1      # 数据库地址
        port: 3306           # 端口
        database: grim       # 数据库名
        username: root       # 用户名
        password: "你的密码"  # 密码
```

> 📌 数据库（例子里的 `grim`）需要**你自己先在 MySQL 里建好**——插件不会替你创建库。

> ⚠️ **改数据库类型必须重启服务器**，`/watchneko reload` 不生效。原文明确写了「仅在重启服务器后生效」。

---

## 常见调整场景

### 我在 1.8 PvP 服务器，想抓更多外挂

```yaml
Reach:
    enable-post-packet: true    # 代价是带宽增加
experimental-checks: true       # 代价是误报可能变多
```

### 我的玩家总被误拉回

```yaml
Simulation:
    threshold: 0.05             # 从 0.03 放宽
```

还是不行的话，检查玩家是不是用了 OptiFine，把 `threshold` 设成 `0.001` 试试。

### 我想先只观察，不干扰玩家

**最可靠的做法是只改 [`punishments.yml`](punishments.md)**：把所有带 `kick` 的行删掉。这样插件照常检测、照常提醒，但不会踢人。

> ⚠️ **不要靠「把 `cancelvl` / `setbackvl` 全设成 0」来做到这件事**——**`0` 恰恰是最激进的值**（见[方块放置类](#方块放置类)的说明）。想关掉取消，得把 `cancelvl` 设成 `-1`；想关掉拉回，得把 `setbackvl` 设成 `-1`。

### 服务器性能吃紧

```yaml
Reach:
    enable-post-packet: false   # 默认就是 false，确认一下
```

用 `/watchneko perf` 看预测耗时，正常应该在 0.05 毫秒以下。

---

[⬅️ 返回 README](../README.md) · [punishments.yml 详解 ➡️](punishments.md)
