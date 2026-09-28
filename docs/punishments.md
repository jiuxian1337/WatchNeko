# `punishments.yml` 详解

这个文件决定**抓到外挂之后会发生什么**：什么时候提醒你、什么时候踢人、什么时候封号。

位置：`plugins/WatchNeko/punishments.yml`

改完之后输入 `/watchneko reload` 生效。

> 💡 **想完全关闭自动踢人？** 把文件里所有带 `kick` 的行删掉就行，插件就只会提醒你。改完 `/watchneko reload`。

---

## 目录

- [默认处罚一览](#默认处罚一览)
- [文件结构](#文件结构)
- [命令格式：阈值:间隔](#命令格式阈值间隔)
- [四种特殊命令](#四种特殊命令)
- [可用变量](#可用变量)
- [remove-violations-after](#remove-violations-after)
- [checks：匹配哪些检测](#checks匹配哪些检测)
- [实战：常见改法](#实战常见改法)
- [完整示例](#完整示例)

---

## 默认处罚一览

这是插件装好后**默认的行为**。`remove-violations-after` 都是 300 秒（5 分钟）。

> 下表的次数是**该段的总违规数**，不是每个检测各自的次数（见[下面的说明](#-先搞懂违规次数是按段累计的)）。只有含单个检测的段才是「该检测的次数」。

| 配置段 | 管哪些检测 | 第几次开始提醒 | 第几次开始踢出 |
|---|---|---|---|
| `Crash` | 崩服攻击 | 1 | **1** |
| `Autoclicker` | 连点器 | 1 | **5** |
| `Hitboxes` | Reach、Hitboxes | 2 | **10** |
| `Aim` | 瞄准作弊 | 3 | **15** |
| `BadPackets` | BadPackets、PacketOrder | 5 | 25 |
| `Scaffold` | 自动搭桥 | 5 | 25 |
| `Place` | 方块放置 | 5 | 25 |
| `Break` | 方块破坏 | 5 | 25 |
| `TransactionOrder` | 事务顺序 | 5 | 25 |
| `MultiActions` | 多动作 | 5 | 25 |
| `Misc` | 载具、疾跑、Baritone、鞘翅、聊天、漏洞 | 5 | 25 |
| `Analysis` | 行为分析 | 5 | 25 |
| `PingSpoof` | 延迟伪造 | 5 | 25 |
| `Stuck` | 卡在空中 | 5 | 25 |
| `Inventory` | 背包作弊 | 5 | 25 |
| `NoFall` | 摔落伤害伪造 | 10 | 50 |
| `NoSlow` | 用物品不减速 | 10 | 50 |
| `Knockback` | 击退作弊 | 10 | 50 |
| `Simulation` | 移动模拟 | 50 | 500 |
| `Timer` | 时间加速 | 50 | 500 |
| `Interact` | 交互作弊 | 50 | **不踢** |

**没有一条 `ban`（封号）。** 默认只踢不封——这是刻意的设计，因为踢人损失小（重进就行），封号一旦误判就很麻烦。

---

## 文件结构

整个文件的骨架是这样的：

```yaml
Punishments:              # 最外层，不要动
    段名:                  # 你自己起的名字，随便叫什么都行
        remove-violations-after: 300   # 多少秒后清空违规次数
        checks:                        # 这一段管哪些检测
            - "检查名"
        commands:                      # 达标后执行什么
            - "阈值:间隔 命令"
```

**「段名」是你随便起的**，比如 `Simulation`、`Crash` 这些都是名字，改成中文也行（不影响功能，只是给你自己看的）。**代码只读取每段的 `checks`、`commands`、`remove-violations-after` 三个键，段名本身完全被忽略。**

**真正起作用的是 `checks` 和 `commands`。**

### 🚨 最关键的规则：没被列到的检测会被「关闭」

插件加载这个文件时的顺序是：

1. 先把**所有**检测**全部关闭**
2. 然后读取每一段的 `checks`
3. 只有**被某一段的 `checks` 匹配到的检测**才会被重新打开

**这意味着：如果你的 `checks` 里没写到某个检测，那个检测就完全不会运行。**

所以——**删掉一整段，等于把那组检测彻底关掉**，而不只是「取消处罚」。

| 你想要的效果 | 正确做法 | 错误做法 |
|---|---|---|
| 保留检测，但**不要踢人** | 只删掉带 `kick` 的行 | 删掉整段 ❌ |
| 保留检测，但**不要提醒** | 只删掉带 `[alert]` 的行 | 删掉整段 ❌ |
| **彻底关掉一组检测** | 删掉整段 ✅ | 删掉 `kick` 行（检测还在跑） |
| 关掉**某一个**检测 | 在 `checks` 里加 `!检测名` | 删掉整个 `Misc` 段 ❌ |

> 💡 **给只想「观察不干预」的人**：保留所有段和所有 `checks`，**只删掉带 `kick` 的行**。这样所有检测照常运行、照常提醒，但一个人都不会被踢。

---

## 命令格式：`阈值:间隔`

每条命令长这样：

```text
"阈值:间隔 要执行的命令"
```

| 部分 | 说明 |
|---|---|
| **阈值** | 玩家违规达到这个数字时，第一次执行 |
| **间隔** | 之后每隔多少次再执行一次。**填 `0` 表示只执行一次，不再重复** |
| **要执行的命令** | 实际执行的命令，可以用变量 |

### 🚨 先搞懂：违规次数是「按段」累计的

**这是最容易搞错的一点。** 每一段有**一个**计数器，这一段里 `checks` 列出的**所有检测共用它**。

举例，默认配置里的 `Misc` 段管着 6 个检测：

```yaml
    Misc:
        checks:
            - "Vehicle"
            - "Sprint"
            - "Baritone"
            - "Elytra"
            - "Chat"
            - "Exploit"
        commands:
            - "25:0 kick %player% cheating"
```

那个 `25` 是**这 6 个检测加起来的违规总数**，不是每个检测各 25 次。也就是说：载具违规 10 次 + 鞘翅 8 次 + 聊天 7 次 = 25 次 → 踢出。

**想让每个检测各算各的，就得给它们拆成独立的段**：

```yaml
Punishments:
    VehicleGroup:
        checks: ["Vehicle"]
        commands: ["25:0 kick %player% cheating"]
    ElytraGroup:
        checks: ["Elytra"]
        commands: ["25:0 kick %player% cheating"]
```

> 📌 反过来，`Crash`、`Scaffold`、`Aim` 这些段只含一个检测，所以它们的计数就等于该检测自己的次数。

**另外**：提醒里显示的 `%vl%` 是这个**段的总数**；而 `[log]` 记录到数据库里的是**该检测自己**的次数。两者可能不一样。

### 看几个例子就懂了

```yaml
commands:
    - "10:5 [alert]"
```

- 第 10 次违规 → 提醒
- 第 15、20、25…… 次 → 继续提醒
- 第 5、9 次 → **不提醒**（还没到 10）

```yaml
    - "100:0 kick %player% 作弊"
```

- 第 100 次违规 → 踢出
- 之后 → **不再执行**（因为间隔是 0）

```yaml
    - "100:50 say %player% 在作弊"
```

- 第 100 次 → 执行
- 第 150、200、250…… 次 → 再执行

### 为什么会重复执行

因为玩家可能一直挂着同一个违规不消失。`50:10` 的意思是「50 次时提醒，之后每多 10 次再提醒一次」，避免一次性刷屏，但也不会漏掉持续作弊的人。

---

## 四种特殊命令

命令前面带方括号的，是插件的**内置功能**，不是真的执行服务器命令：

| 写法 | 作用 |
|---|---|
| `[alert]` | **发送作弊提醒**给所有有 `watchneko.alerts` 权限的人 |
| `[webhook]` | **发送到 Discord**（需要在 [`discord.yml`](discord.md) 里启用并填 webhook 地址） |
| `[proxy]` | **发送给代理下的其他子服**（需要同时在 `config.yml` 里开 `alerts.proxy.send`） |
| `[log]` | **保存调试日志**，之后可以用 `/watchneko log <编号>` 上传查看 |

### 普通命令

不带方括号的，就是**以控制台身份执行服务器命令**：

```yaml
    - "100:0 kick %player% 检测到作弊"
    - "100:0 ban %player% 使用外挂"
    - "50:0 say %player% 请注意你的行为"
    - "100:0 lp user %player% parent set banned"
```

能执行什么取决于你服务器装了什么插件。

> ⚠️ **执行的是控制台命令**，所以能用的命令很多，**写错了后果也很严重**。改之前建议先备份。

---

## 可用变量

写在命令里的这些词会被替换成实际内容：

| 变量 | 会变成 |
|---|---|
| `%player%` | 玩家的名字 |
| `%check_name%` | 触发的检测名（比如 `Reach`） |
| `%description%` | 这个检测的说明文字 |
| `%vl%` | 当前违规次数 |
| `%verbose%` | 额外信息，比如「距离 3.14 方块」。**不是所有检测都有** |

例子：

```yaml
    - "25:0 kick %player% 检测到 %check_name% 违规 %vl% 次"
```

玩家看到的踢出理由会是：`检测到 Reach 违规 25 次`

> **提醒的文字长什么样**（`[alert]` 的内容）不在这里改，在 [`messages.yml`](messages.md) 的 `alerts-format` 里改。

### 隐藏选项：`test-mode`

`punishments.yml` **没有**这个键，但插件会读它——写在文件最外层（和 `Punishments` 平级）就能生效：

```yaml
Punishments:
    # ... 你的处罚配置 ...

# 写在最外层，注意缩进
test-mode: false
```

| 值 | 效果 |
|---|---|
| `false`（默认） | 正常：提醒发给所有有权限的管理员 |
| `true` | **只有被检测的那个人自己**能看到这条提醒，其他管理员收不到 |

代码里的注释叫它「secret test mode（秘密测试模式）」。用途是**测试检测准不准**——开着它，你开小号去试，提醒只出现给你自己看，不会打扰其他管理员。

> ⚠️ 这是**没有写进默认配置文件、也没有文档的隐藏选项**。作者随时可能在后续版本改掉或删掉它。

---

## `remove-violations-after`

```yaml
remove-violations-after: 300
```

**玩家违规多少次秒之后，违规计数清零。** 单位是秒。

| 值 | 效果 |
|---|---|
| `300`（默认） | 5 分钟没再违规，计数归零 |
| 调**大** | 违规记录存得更久，更容易攒够踢出阈值。**误判的代价更大** |
| 调**小** | 违规记录很快就清掉，外挂只要「作一次弊等一会儿」就能规避处罚 |

**这个值决定了作弊者要「持续作弊多久」才会被踢。** 默认 5 分钟是比较平衡的。

---

## `checks`：匹配哪些检测

```yaml
checks:
    - "Simulation"
```

- 每一项是一个检测名
- 匹配是**子串匹配**（不完全等于，也不只是开头匹配）
- **加感叹号 `!` 表示排除**：

```yaml
checks:
    - "BadPackets"
    - "!BadPacketsN"     # 除了 BadPacketsN，其他 BadPackets 都算
```

**什么时候需要排除**：某个检测误报多，但你不想关掉整组，就可以单独把它摘出来，放到单独的一段里给个宽松的阈值。

### ⚠️ 匹配是「包含」而不是「等于」

插件判断一个检测属不属于这一段，用的是**「配置里写的字符串是不是检测名的子串」**。所以：

| 你写的 | 会匹配到 | 说明 |
|---|---|---|
| `NoFall` | `NoFallA`、`NoFallB` | 因为 `"nofalla"` 里包含 `"nofall"` ✅ 符合预期 |
| `Aim` | `AimA` … `AimZ`、`AimAA`、`AimAB` | ✅ 符合预期 |
| `Fall` | `NoFallA`、`NoFallB` | ⚠️ **也能匹配**——因为 `"nofalla"` 里包含 `"fall"` |
| `a` | **几乎所有检测** | ⚠️ 灾难 |

> 📌 **所以不要写太短的字符串。** 想精确指定某一个检测，就写完整的检测名（比如 `NoFallA` 而不是 `NoFall`）。

**另一个特性**：检测还有一个「别名」（alternative name），两个名字**任一**匹配上就算匹配。所以 `AntiKB` 和 `AntiKnockback` 都能指向同一个检测。

### 关于 `Misc` 段

默认配置里 `Misc` 这一段管着一堆不同的检测：

```yaml
Misc:
    checks:
        - "Vehicle"
        - "Sprint"
        - "Baritone"
        - "Elytra"
        - "Chat"
        - "Exploit"
```

**如果你想给它们不同的处罚**，自己拆成多段就行：

```yaml
Punishments:
    ChatSpam:
        remove-violations-after: 600
        checks:
            - "Chat"
        commands:
            - "1:1 [log]"
            - "1:1 [alert]"
            - "3:0 kick %player% 请不要刷屏"
    ElytraCheat:
        remove-violations-after: 300
        checks:
            - "Elytra"
        commands:
            - "5:5 [alert]"
            - "25:0 kick %player% 检测到鞘翅作弊"
```

---

## 实战：常见改法

### 1. 完全关闭自动踢人（只提醒）

把**所有带 `kick` 的行删掉**。比如：

```yaml
    Crash:
        remove-violations-after: 300
        checks:
            - "Crash"
        commands:
            - "1:1 [log]"
            - "1:1 [alert]"
            - "1:1 [webhook]"
            - "1:1 [proxy]"
            # - "1:0 kick %player% cheating"    ← 注释掉或删掉
```

> 在行首加 `#` 是 YAML 的注释语法，也能达到同样效果。

### 2. 改成自动封号

在想要的阈值上加一条 ban 命令：

```yaml
    Aim:
        remove-violations-after: 300
        checks:
            - "Aim"
        commands:
            - "1:1 [log]"
            - "3:3 [alert]"
            - "15:0 kick %player% cheating"
            - "30:0 ban %player% 使用瞄准外挂"      # ← 新增
```

> ⚠️ **封号是不可逆的**（取决于你用的封禁插件）。建议先跑一两周只提醒的模式，确认不误判了再加。

### 3. 让某个检测更宽容

`Simulation` 是最容易误判的（因为它管所有移动）。如果玩家老是被它踢：

```yaml
    Simulation:
        remove-violations-after: 60     # 违规记录 1 分钟就清空
        checks:
            - "Simulation"
        commands:
            - "1:1 [log]"
            - "200:50 [alert]"           # 从 50 次改到 200 次才提醒
            - "1000:0 kick %player% cheating"   # 从 500 次改到 1000 次
```

### 4. 关掉某一整组检测

**注意**：因为[前面说的规则](#-最关键的规则没被列到的检测会被关闭)，删掉一整段就是**把这组检测彻底关掉**。

```yaml
    # 删掉整个 Chat 段 → Chat 检测不再运行
```

如果你只是不想处理它、但想留着记录，可以保留段落、只删掉处罚命令：

```yaml
    Chat:
        remove-violations-after: 300
        checks:
            - "Chat"
        commands:
            - "1:1 [log]"      # 只记日志，不提醒、不踢人
```

### 5. 加管理员的提醒音效

```yaml
        commands:
            - "1:1 [log]"
            - "10:5 [alert]"
            - "10:5 execute as @a[tag=admin] run playsound minecraft:block.note_block.pling master @s"
```

（需要你有权限系统配合，这里只是举例——**任何控制台能执行的命令都能写**。）

---

## 完整示例

一个从零写的自定义段，带详细注释：

```yaml
Punishments:
    # 段名，随便起
    我的自定义检测组:
        # 违规记录保留 10 分钟
        remove-violations-after: 600

        # 管这两个检测，但排除 NoFallB
        checks:
            - "NoFall"
            - "!NoFallB"

        commands:
            # 每次违规都记日志，方便用 /watchneko log 查
            - "1:1 [log]"

            # 第 3 次开始提醒，之后每 3 次提醒一次
            - "3:3 [alert]"

            # 同步发到 Discord
            - "3:3 [webhook]"

            # 第 20 次踢出，只踢一次
            - "20:0 kick %player% 检测到摔落伤害伪造，已自动踢出"

            # 第 50 次封号（假设你装了 LiteBans）
            - "50:0 ban %player% 使用外挂 - 自动封禁"
```

---

## 排错

| 现象 | 原因 |
|---|---|
| 改完没生效 | 忘了 `/watchneko reload`，或者 YAML 格式写错了（检查缩进，YAML 用空格不用 Tab） |
| `[webhook]` 没反应 | `discord.yml` 里的 `enabled` 是 `false`，或者 webhook 地址没填 |
| `[proxy]` 没反应 | `config.yml` 里的 `alerts.proxy.send` 是 `false` |
| `[log]` 上传失败 | 编号不存在，或者服务器连不上 paste 服务器 |
| 命令不执行 | 阈值还没到。用 `/watchneko verbose` 看实际的违规次数 |
| 提醒刷屏 | 把 `间隔` 调大，或者把 `阈值` 调高 |

---

[⬅️ config.yml 详解](config.md) · [命令详解](commands.md) · [messages.yml 详解 ➡️](messages.md)
