# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目是什么

WatchNeko 是 Minecraft 反作弊插件（Paper / Spigot / Folia，1.8–1.21）。代码基底是 Grim 的换皮分支（upstream 为 `GrimAnticheat/Grim`），类名大量保留 `Grim*` 前缀，并实现 `ac.grim.grimac:GrimAPI` 的接口。仓库同时包含一个可选的 Python 云端服务，把旋转判定外包给模型。

| 路径 | 是什么 |
|---|---|
| `common/` | 全部检测逻辑、平台无关抽象、云端客户端 |
| `bukkit/` | 平台适配层与插件入口（`WatchNekoBukkitLoaderPlugin`），产物是 shadowJar |
| `cloud-service/` | Python / FastAPI 云端：推理、样本存储、数据集与模型版本、管理控制台 |
| `contract/` | 插件与云端之间**唯一**的 HTTP 契约；`fixtures/` 是两侧测试共用的黄金语料 |
| `docs/` | 面向用户的中文文档（每个配置文件一篇 + 命令 + 云端接入） |

## 构建与测试

Gradle 的启动 JVM 必须是 JDK 21：Gradle 8.14.3 的 Kotlin DSL 在 JDK 25 上直接失败（`./gradlew help` 报 `FAILURE: ... What went wrong: 25.0.5`）。项目 toolchain 为 21，字节码 `release` 为 17。先跑 `./gradlew --version` 看 `Launcher JVM` 一行确认；不是 21 就显式指定：

```bash
# macOS
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./gradlew build
# Windows (Git Bash)
JAVA_HOME="/c/Program Files/Zulu/zulu-21" ./gradlew build
```

仓库没有 CI（`.github/` 只有 issue 模板），构建与测试都在本地跑。每次构建都会向 gradle.com 上传 Develocity build scan（`settings.gradle.kts` 里硬编码了同意条款），离线或不想上传时加 `--no-scan`。

- 子模块的 `build` 依赖 `spotlessApply`（4 空格、去未用 import、去行尾空格），构建会顺手改写源码格式。只格式化：`./gradlew spotlessApply`。
- 可分发产物：`bukkit/build/libs/WatchNeko-bukkit-<yy.MM.dd>.jar`（`:common` 被 shade 进去）。版本号是构建日期 `yy.MM.dd`，不是语义化版本。
- 测试只在 `common` 模块（`bukkit` 没有测试目录，JUnit 5）：
  - 全量：`./gradlew :common:test`
  - 单个：`./gradlew :common:test --tests "cc.watchneko.cloud.ContractDriftTest"`
- 本地起服：`./gradlew :bukkit:runServer`（Paper 1.8.8，运行目录 `bukkit/run`）

构建开关在 `buildSrc/src/main/kotlin/versioning/BuildConfig.kt`，三种写法任选（`-Pkey=value` / `-Dkey=value` / 环境变量）：

| 开关 | 默认 | 作用 |
|---|---|---|
| `shadePE` | `true` | 把 PacketEvents 打进 jar；`false` 时 `plugin.yml` 改为 `depend: packetevents` |
| `relocate` | `true` | 重定位 shaded 依赖到 `ac.grim.grimac.shaded.*` |
| `release` | `false` | 只经 `processResources` 写进 `watchneko.properties` 的 `build.release`（`/watchneko dump` 会读取） |
| `mavenLocalOverride` | `false` | 优先使用 mavenLocal 中的依赖 |

重定位规则是**手写清单**：`buildSrc/.../grim.shadow-conventions.gradle.kts` 里的 `relocate(...)` 与 `verifyRelocations` 的 `sourcePackages` 必须同步。新 shade 一个库时两处都要加，否则 `assemble` 会因“类没被重定位”而失败（这正是为了防止写错包名的规则静默失效——Gson 曾因此用 Maven group id 当包名，运行时 `NoSuchMethodError`）。

云端服务（Python 3.11+，README 写 3.12+；Windows 下 venv 是 `.venv/Scripts/`，把 `.venv/bin/` 换掉即可）：

```bash
cd cloud-service
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest       # 两侧契约测试之一；单个：-k 或用 tests/xxx.py::test_yyy
.venv/bin/python run.py          # 首次启动生成 config.yaml 并打印管理员密码
```

`cloud-service/config.yaml` 与 `*.db` 被 gitignore（前者含明文密码与管理员密钥），不要提交。部署用 `cloud-service/deploy.sh`：它先跑测试，不过就不上传一个字节。

## 架构

### 启动与依赖装配

入口 `WatchNekoBukkitLoaderPlugin`（`onLoad` → `onEnable` → `onDisable`）只做平台适配，真正的装配在 `WatchNekoAPI` 单例 + `InitManager`：

- `load`（`LoadableInitable`）：PacketEvents 初始化、配置加载、注册 API service
- `start`（`StartableInitable`）：按固定顺序注册 packet 监听器、tick、命令、alert / discord / spectate / 违规数据库
- `stop`（`StoppableInitable`）

`common/platform/api` 的 `PlatformLoader` 抽象 Bukkit 与 Folia 的差异（scheduler、player、world、sender、command）；`WatchNekoAPI.detectPlatform()` 通过反射探测 Folia。新增平台 = 实现 `PlatformLoader`，不要把 Bukkit 类型塞进 `common`。

### 数据包 → 检测

`PacketManager` 注册 PacketEvents 监听器；`CheckManagerListener`（LOW priority）把移动 / 旋转 / 方块交互包解析成框架无关的更新对象，再交给每个玩家的 `CheckManager`。`CheckManager` 用 `ClassToInstanceMap` 按接口分派：

| 接口 | 回调 | 典型用途 |
|---|---|---|
| `PacketCheck` | `onPacketReceive` / `onPacketSend` | 包级检测；另有一组 pre-prediction 检查先于移动预测收到包（Timer 类） |
| `RotationCheck` | `process(RotationUpdate)` | 瞄准类 |
| `PositionCheck` | `onPositionUpdate` | 移动预测入口 |
| `PostPredictionCheck` | `onPredictionComplete` | 依赖预测结果的检测（Velocity、NoFall、Phase） |
| `BlockPlaceCheck` / `BlockBreakCheck` | 放置 / 破坏 | 交互类 |
| `VehicleCheck` | `process(VehiclePositionUpdate)` | 载具 |

注册顺序在 `packetChecks` 里是**语义**，不是风格：`ExploitC` 必须排在最前（注释：`必须前置这个检查`——它要在被撤销的移动进入模拟队列之前拦下），`SetbackBlocker` 必须排在最后（否则拦截期间无法继续检测）。`noneModules` 放没有监听的 check——它们由别的 check 直接调用，例如 `Hitboxes` 由 `Reach` 调 `getCheck(Hitboxes.class)`。

`PlayerData` 是每个玩家的状态中枢，持有 `CheckManager`、`ActionManager`、`PunishmentManager`、预测引擎、延迟补偿与 `RotationSequence`。类顶部注释是硬约束：**这些字段都在 anticheat 线程上，netty 线程的字段放 `PacketStateData`**。

移动模拟在 `predictionengine/`：`MovementCheckRunner` 复刻原版移动，`PredictionRunner`（PositionCheck）是入口，`Simulation` 惩罚组是它的输出。setback 走 `SetbackTeleportUtil`。

### 检测类与"启用"

每个检测是 `Check` 的子类，用 `@CheckData(name, configName, decay, setback, experimental)` 标注，并在 `CheckManager` 构造器里**手工登记**进对应 map——新增检测必须同时改这里，否则不会被调用。同一个类在同一张 map 里**只能登记一次**：`ImmutableClassToInstanceMap.Builder` 遇到重复 key 会在 `new PlayerData` 时抛 `IllegalArgumentException: Multiple entries with same key`，不会静默覆盖。

启用不是布尔开关：`PunishmentManager.reload()` 先把所有 check 置为 disabled，再遍历 `punishments.yml` 的 `Punishments` 组，按 check 名 / 别名做**子串匹配**，命中的才启用。所以没写进任何惩罚组的 check 永远不会 flag（`Check.flag()` 会因 `handleViolation` 返回 false 而退出）。`AnalysisA` 靠 `Analysis` 组的前缀匹配继承惩罚规则。子串匹配意味着组名写短会误伤同类 check（`"Aim"` 命中所有名字含 `Aim` 的检测），改名或新增同前缀检测时要回头核对。

flag 链路：`Check.flag()` → `FlagEvent`（可取消）→ `PunishmentManager.handleViolation` 记录 VL → `handleAlert` 在阈值处执行组内命令：`[alert]` / `[log]`（违规库）/ `[webhook]`（Discord）/ `[proxy]`（跨服）/ 控制台命令。

`@CheckData(experimental = true)` 是第二道闸：`experimental-checks` 默认 `false`，此时这些 check 的 `flag()` 直接返回 false，连 FlagEvent 都不会发。新增检测默认标了 experimental 就等于默认不生效。

### 配置

`configuralize` 的 `DynamicConfig` 加载 4 个文件（`config.yml`、`messages.yml`、`punishments.yml`、`discord.yml`），默认值来自 `common/src/main/resources/{config,messages,punishments,discord}/{en,zh}.yml`，按 `user.language` 系统属性选语言，缺失回退英文。改配置键要同时改 en / zh 两份资源与 `docs/config.md`。

**配置补齐**：`ConfigCompleter` 在 `saveAllDefaults(false)` 之后、`loadAll` 之前运行，把 `en/zh` 资源里有、用户文件里没有的键（连同注释）插入 `config.yml` / `messages.yml` / `discord.yml`，插入前备份为 `*.yml.bak`。它只做插入：已有行不重写、不排序、不删除，第二次运行是 no-op。`punishments.yml` 刻意排除（用户自定义数据）。曾经的 `config-version` 迁移体系（`upgrade()`）从未被调用，已于 2026-10-28 删除——不要恢复那套"按版本号往文件末尾追加"的做法，补齐由 `ConfigCompleter` 统一负责。改动补齐逻辑时必须跑 `ConfigCompleterTest` 与 `ConfigCompleterShippedFilesTest`（后者用真实资源文件验证）。

`/watchneko reload` → `WatchNekoExternalAPI.reloadAsync()` → 依次重载各 manager、重建 `CloudGateway`、再让在线玩家重载自己的 checks。数据库设置在文档里标注为需重启。

### 云端检测（AnalysisA）

`cc.watchneko.cloud` 是唯一与云端通信的包：

- `CloudGateway` 是进程级、`volatile` 发布。`current() == null` 是**正常状态**（未启用 / 未配置 / 不可达），此时检测整体不工作且 fail-open；恢复由 30 秒定时器重试，不需要重启。
- `canInfer() == false` 不等于 `current() == null`：冷启动（云端可达、已认证、但还没有已发布模型）和特征维度不符都产生一个 **非 null 但不可推理**的 gateway。这个区别是刻意的——录制样本只需要可达的云端，把「无模型」当成不可用会让第一个模型永远训练不出来。这两种状态下 `/watchneko record` 仍然可用。
- `AnalysisA`（RotationCheck）在攻击锚点处取 `RotationWindow`（22 维 = 11 次旋转更新 × yaw/pitch），攒批异步推理。云端裁决只变成**票**，是否告警由本地 `VerdictVotes` 按 `analysis-window-size` / `min-cheat-votes`（默认 10 / 8）决定——云端从不惩罚。429 不关检测，而是进入配额门控，只发有运动能量的窗口。
- 录制会话（`/watchneko record start`）期间样本只进 `RecordingSession`，**不送推理**：训练数据不能影响它要训练的检测。玩家断线自动 pause，回来 resume。样本 id 以会话 id 为作用域，否则重连会撞上云端已有的 id。
- 玩家 ID 用租户 salt 哈希后才离开服务器；原始角度不出服务器，只有归一化后的 `features` 上线。

### 契约纪律

`contract/README.md` 是权威。两侧测试（Java `common/src/test/java/cc/watchneko/cloud/Contract*`、Python `cloud-service/tests/test_*contract*`）读**同一批** `contract/fixtures/*.json`，并断言每一层对象的字段集合完全相等。改契约的唯一正确顺序：先改 fixture → 两侧测试一起红 → 再改两侧实现 → 一起绿。`ContractDriftTest` 专门证明这个比较是严格的（改名一个字段必须失败），不要为了让测试通过而放宽 `ContractAssertions`。两侧还各自断言 `case_names()` 的完整列表，新增 fixture 会同时迫使两边实现它。

### 云端服务

`app/` 分层：`main.py`（契约端点，只接受依赖注入）、`console.py` + `templates/`（服务端会话守卫；每个视图一个文档，非 SPA）、`schemas.py`（pydantic `extra="forbid"`）、`auth.py`（401 / 403 分开）、`tenants.py`（密钥只存 SHA-256）、`quota.py`、`store.py`（SQLite）、`dataset.py` / `train.py` / `publish.py` / `ops.py` / `scheduler.py`（训练 → 发布 → 回滚，健康判据是唯一刹车）、`metrics.py`。`serve.py` 是组合根。

`conftest.py` 把 `cloud-service/` 塞进 `sys.path`，所以 `pytest` 不用安装包就能跑；测试从 `contract/fixtures` 按相对路径读取语料（`parents[2]`），**不要在 `cloud-service/` 里移动测试目录**。

## 约定

- `checks/impl` 只放检测实现、检测入口与子检测（见该包 `package-info.java`）；工具类进 `utils`，特征提取与投票策略进 `analysis`，与云端通信进 `cloud`。
- 测试要能在没有 PacketEvents 的情况下驱动检测：用 `RotationSequence` / `AttackAnchor` 这类框架无关的接缝，不要构造 `PacketEvent`。云端侧同理——`CloudTransport` 是接口，测试用 `FixtureTransport` 回放 `contract/fixtures` 里的响应，不要起 HTTP。
- 面向用户的文档是中文（`docs/`、`README.md`）；Java 代码与注释以英文为主（个别历史注释是中文）；提交信息以中文为主。
- 与 Grim / EdGrim 不能共存：检测到就关服（`WatchNekoBukkitLoaderPlugin.onLoad`）。
- Issue 与 triage 流程见 `AGENTS.md` 与 `docs/agents/`（GitHub issues，`gh` CLI；标签 `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。
