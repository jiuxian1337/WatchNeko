# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目是什么

WatchNeko 是 Minecraft 反作弊插件（Paper / Spigot / Folia，1.8–1.21）。代码基底是 Grim 的换皮分支（upstream 为 `GrimAnticheat/Grim`），类名大量保留 `Grim*` 前缀，并实现 `ac.grim.grimac:GrimAPI` 的接口。仓库同时包含一个可选的 Python 云端服务，把旋转判定外包给模型。

| 路径 | 是什么 |
|---|---|
| `common/` | 全部检测逻辑、平台无关抽象、云端客户端 |
| `bukkit/` | 平台适配层与插件入口（`WatchNekoBukkitLoaderPlugin`），产物是 shadowJar |
| `cloud-service/` | Python / FastAPI 云端：推理、样本存储、数据集与模型版本、管理控制台 |
| `contract/` | 插件与云端之间**唯一**的 HTTP 契约；`fixtures/` 是黄金语料（当前由 Python 测试读取，见「契约纪律」） |
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
- 构建过一次之后，`common/build/generated/sources/delombok/java/main/` 里会有全部 Lombok 类的展开副本（约 586 个 `.java`，反映上一次构建；`bukkit` 另有约 40 个）。按类名 grep 会先命中这里，读到的行号与源码对不上；搜索时排除 `build/`。只生成它们：`./gradlew :common:delombok`。
- **Java 侧当前没有测试**：仓库里没有任何 `common/src/test` 源文件（本机只留下空目录），JUnit 5 依赖与 `useJUnitPlatform()` 仍留在 `common/build.gradle.kts` 里，`./gradlew :common:test` 报 `NO-SOURCE`。`bukkit` 从来没有测试目录。这套测试覆盖契约、配置补齐、投票与窗口，于 `0c178da`（2026-10-31）整批删除，所以本文档提到某个 Java 测试类时都只是**意图**，不是现有保障——改相关代码没有红绿灯可依赖。新增测试放回 `common/src/test/java`，全量 `./gradlew :common:test`，单个 `--tests "cc.watchneko.xxx.YyyTest"`。
- 可分发产物：`bukkit/build/libs/WatchNeko-bukkit-<yy.MM.dd>.jar`（`:common` 被 shade 进去）。版本号是构建日期 `yy.MM.dd`，不是语义化版本。
- 本地起服：`./gradlew :bukkit:runServer`（Paper 1.8.8，运行目录 `bukkit/run`）

构建开关在 `buildSrc/src/main/kotlin/versioning/BuildConfig.kt`，三种写法任选（`-Pkey=value` / `-Dkey=value` / 环境变量）：

| 开关 | 默认 | 作用 |
|---|---|---|
| `shadePE` | `true` | 把 PacketEvents 打进 jar；`false` 时 `plugin.yml` 改为 `depend: packetevents` |
| `relocate` | `true` | 重定位 shaded 依赖到 `ac.grim.grimac.shaded.*` |
| `release` | `false` | 只经 `processResources` 写进 `watchneko.properties` 的 `build.release`（`/watchneko dump` 会读取） |
| `mavenLocalOverride` | `false` | 优先使用 mavenLocal 中的依赖 |

重定位规则是**手写清单**：`buildSrc/.../grim.shadow-conventions.gradle.kts` 里的 `relocate(...)` 与 `verifyRelocations` 的 `sourcePackages` 必须同步。新 shade 一个库时两处都要加，否则 `assemble` 会因“类没被重定位”而失败（这正是为了防止写错包名的规则静默失效——Gson 曾因此用 Maven group id 当包名，运行时 `NoSuchMethodError`）。

云端服务（Python 3.11+，README 写 3.12+；Windows 下 venv 是 `.venv/Scripts/`，把 `.venv/bin/` 换掉即可）。**仓库里不带 `.venv`**，没建过就先建，否则 `pytest` 起不来：

```bash
cd cloud-service
python3 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m pytest       # 全量（cloud-service/README 记的是 214 个用例）；单个：tests/xxx.py::test_yyy 或 -k 过滤
.venv/bin/python run.py          # 首次启动生成 config.yaml 并打印管理员密码
```

这是仓库里**唯一还在跑的自动化测试套件**，契约、存储迁移、配额、训练/发布/回滚都由它覆盖，改云端代码后必跑。

`cloud-service/config.yaml` 与 `*.db` 被 gitignore（前者含明文密码与管理员密钥），不要提交。部署用 `cloud-service/deploy.sh`：它先跑测试，不过就不上传一个字节。仓库根目录另有 `docker-compose.yml`（`./cloud-service` 构建，配置与数据库挂在 `watchneko-data` 卷的 `/data`），适合快速起一个实例而不是正式部署。

## 架构

### 启动与依赖装配

入口 `WatchNekoBukkitLoaderPlugin`（`onLoad` → `onEnable` → `onDisable`）只做平台适配，真正的装配在 `WatchNekoAPI` 单例 + `InitManager`：

- `load`（`LoadableInitable`）：PacketEvents 初始化、配置加载、注册 API service
- `start`（`StartableInitable`）：按固定顺序注册 packet 监听器、tick、命令、alert / discord / spectate / 违规数据库
- `stop`（`StoppableInitable`）

`common/platform/api` 的 `PlatformLoader` 抽象 Bukkit 与 Folia 的差异（scheduler、player、sender、command、plugin / server 访问）；`WatchNekoAPI.detectPlatform()` 通过反射探测 `io.papermc.paper.threadedregions.RegionizedServer`，也可用 `-Dwatchneko.platform-override=FOLIA|BUKKIT` 强制指定。新增平台 = 实现 `PlatformLoader`，不要把 Bukkit 类型塞进 `common`——但 `PlayerData`、`utils/math/Location`、`utils/ray/RayUtils`、`AimProcessor` 里已有历史 Bukkit 引用，新增代码不要照抄这个形状。

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

注册顺序在 `packetChecks` 里是**语义**，不是风格：`ExploitC` 排在很靠前（源码注释：`必须前置这个检查`），`SetbackBlocker` 必须排在最后（否则拦截期间无法继续检测）。`ImmutableClassToInstanceMap` 保留插入顺序，监听器按 `values()` 顺序收到回调。`noneModules` 放没有监听的 check——它们由别的 check 直接调用，例如 `Hitboxes` 由 `Reach` 调 `getCheck(Hitboxes.class)`。

`RotationCheck.onNoRotation()`（默认空实现）是第二个回调：`process` 只在包真的带了 yaw/pitch 变化时才被调用，需要区分「这一 tick 没转」的检测覆写它，由 `CheckManagerListener` 在 `hasPosition && !hasLook && 位置真的变了` 时通过 `CheckManager.onNoRotationUpdate()` 派发。它**不**进 `RotationSequence`（否则攻击锚点会漂移）。

`PlayerData` 是每个玩家的状态中枢，持有 `CheckManager`、`ActionManager`、`PunishmentManager`、预测引擎、延迟补偿与 `RotationSequence`。类顶部注释是硬约束：**这些字段都在 anticheat 线程上，netty 线程的字段放 `PacketStateData`**。

移动模拟在 `predictionengine/`：`MovementCheckRunner` 复刻原版移动，`checks/impl/movement/PredictionRunner`（PositionCheck）是入口；结果由 `checks/impl/prediction/OffsetHandler` 消费，它的 `@CheckData(name = "Simulation")` 才是 `punishments.yml` 里 `Simulation` 组的来源——**仓库里没有叫 `Simulation` 的类**，按这个名字 grep 源码是找不到的。setback 走 `SetbackTeleportUtil`。

### 检测类与"启用"

每个检测是 `Check` 的子类，用 `@CheckData(name, configName, decay, setback, experimental)` 标注，并在 `CheckManager` 构造器里**手工登记**进对应 map——新增检测必须同时改这里，否则不会被调用。同一个类**只能登记一次**，跨 map 也不行：各 map 最后会 `putAll` 合并成 `allChecks`，`ImmutableClassToInstanceMap.Builder` 遇到重复 key 会在 `new PlayerData` 时抛 `IllegalArgumentException: Multiple entries with same key`，不会静默覆盖。

启用不是布尔开关：`PunishmentManager.reload()` 先把所有 check 置为 disabled，再遍历 `punishments.yml` 的 `Punishments` 组，按 check 名 / 别名做**子串匹配**，命中的才启用。所以没写进任何惩罚组的 check 永远不会 flag（`Check.flag()` 会因 `handleViolation` 返回 false 而退出）。子串匹配意味着组名写短会误伤同类 check（`"Aim"` 命中所有名字含 `Aim` 的检测），改名或新增同前缀检测时要回头核对。

**默认组里没有 `Analysis` 段**：`Analysis` 系列靠 `Aim` 组里的 `- "Analysis"` 这一行被匹配到（子串命中所有名字含 `analysis` 的 check，包括 `AnalysisA`），再被后面的 `!AnalysisC` 等逐条剔除。`docs/punishments.md` 的表格与 `config/en.yml`/`zh.yml` 里 `AnalysisA` 段的注释都还写着独立的 `Analysis` 组，那是改名前的状态，实际并不存在。

**`!name` 排除项与顺序无关**：`excluded` 在整段内累积，且**每处理完一条 pattern 就重放一次** `checksList.remove(...)`，所以 `!AnalysisC` 写在 `"Analysis"` 之前也会把它剔出该组的 VL 计数（这时检测被 `setEnabled(true)` 启用过，但不在 `group.checks` 里，`handleViolation` 返回 false，不会累计）。真正的坑是**重复加入**：`checksList` 是 `List`，同一个 check 被两条 pattern 命中就会进两次，而 `remove` 只删第一个——例如 `- "Analysis"` 之后再写 `- "AnalysisC"`，即使后面有 `!AnalysisC` 也会留下第二份，被排除的检测照样累计 VL。写重叠 pattern 时要避开这种情况。

flag 链路：`Check.flag()` → `FlagEvent`（可取消）→ `PunishmentManager.handleViolation` 记录 VL → `handleAlert` 在阈值处执行组内命令：`[alert]` / `[log]`（违规库）/ `[webhook]`（Discord）/ `[proxy]`（跨服）/ 控制台命令。

`@CheckData(experimental = true)` 是第二道闸：`experimental-checks` 默认 `false`，此时这些 check 的 `flag()` 直接返回 false，连 FlagEvent 都不会发。新增检测默认标了 experimental 就等于默认不生效。

**`Check` 的构造器在 `super()` 里调 `reload()`**（`GrimProcessor.reload()` → `reload(configManager.getConfig())` → `onReload(...)`），因此每个子类的 `onReload(ConfigManager)` 会在**子类字段初始化之前**执行一次。任何在 `onReload` 里读/清子类字段的代码，第一趟看到的都是 `null` 或默认值——`AnalysisA.onReload` 的 `if (window != null)` 守卫就是为此而存在（见 `0c178da` 修掉的进服 NPE）。新写的 check 若在 `onReload` 里碰集合或配置派生状态，要么判空，要么改成惰性初始化。同理，`onReload` 里 `config.getDoubleElse(key, 字段初值)` 的第二参数在这一趟读到的是 0/`null`，所以默认值要以字面量给出，不能依赖字段初始值。

### 瞄准检测的拆分模式

`AnalysisB`–`AnalysisO` 是同一族检测，从上游移植后按组件拆开。**读懂这族只需要理解一个模式**：父检查持有采样窗口，子检查只负责惩罚。

- `AnalysisB`（basic）、`AnalysisJ`（statistics）、`AnalysisM`（complex）各自维护窗口和分数；分数越过上限时调 `getCheck(AnalysisC.class)` 之类的子检查 `flagAndAlert(...)`。`AnalysisE`/`F`/`G`/`H`/`I` 则是自己采样、自己 flag 的单体检查。
- `AnalysisC`/`D`/`K`/`L`/`N`/`O` 是纯惩罚类：没有监听、登记在 `noneModules`、只有一个 `public long mitigateDamageMs`（从各自配置段读，`onReload` 里覆盖；`AnalysisK` 不设初值，默认 0 即不减伤）。它们与 `Hitboxes` 被 `Reach` 调用是同一形状。
- 这个拆分的唯一目的是**让每个路径有自己的惩罚组**：上游有些路径不加 VL，所以 `AnalysisC/D/E/F/K/N` 被 `!` 从 `Aim` 组剔除（`E`/`F` 本来就自带 flag，但被排除出 VL 累计）并放进 `NoPenalty`（只提醒、永不踢人），其余留在 `Aim` 组。删掉这个拆分，这些路径就会跟着父检查一起累计 VL。
- 减伤由**调用方**施加，不在子检查里：`AnalysisB` 读 `C`/`D` 的 `mitigateDamageMs` 后调 `player.mitigateDamage(...)`，`AnalysisJ` 对 `K`/`L` 做同样的事，`AnalysisM` 对 `N`/`O` 同样。`AnalysisE`/`F`/`G` 自己 flag 并自己减伤；`AnalysisH`/`I` 与 `AnalysisB` 的随机化缺陷只加 VL、完全不减伤。

**采样门控集中在 `analysis/aim/AimHeuristicGate`**，不要在子检查里重写：

| 方法 | 作用 |
|---|---|
| `blocks(player)` | 传送 / 载具 / 马 / 1.17 重复包 / `ignoreNextRotation` 一次性跳过 / 传送后 500ms 宽限。`AnalysisB`/`E`/`F`/`G`/`H`/`I` 与 `BadPacketsZ` 用它 |
| `blocksNoRotation(player)` | 只看传送后 1000ms 宽限（无旋转 tick 本身无可信与否）。`AnalysisH` 用 |
| `dropRotation(player, update)` | `blocks` + NaN/Infinity/\|v\|>3E8 守卫。`AnalysisJ`/`AnalysisM` 用它 |
| `noPitchChange(update)` | 纯水平旋转当作「无旋转」。**上游是复制粘贴 bug（pitch 比较两次），刻意保留** |
| `referenceDelta(update)` | 返回取反的 delta。上游构造事件时把 `to`/`from` 传反，所以它的 delta 是 `from - to`；随机化缺陷、Pattern、Smooth 依赖这个符号 |

攻击门是**按检查分别加的**，不在 gate 里：只有 `AnalysisJ`/`AnalysisM` 要求 `hasAttackedSince(3500L)`，其余启发式不看攻击。`AnalysisA` 走的是另一条路（`RotationSequence` + `AttackSignalListener`，见「云端检测」）。

**改动这些检测前先读 `AimHeuristicGate` 的类注释**：里面每一条「看起来像 bug」的行为都是与上游对齐的结果，修正任何一条都会改变触发人群，通常表现为误判变多。本文档旧版说 `AnalysisHeuristicWiringTest` 钉住了这几条——该测试在 git 历史里从未存在过，现在也没有任何自动检查守着它们。

### 另一族瞄准检测（`checks/impl/aim/`）

`aim/` 是换皮前就存在的旧检测族，与 `analysis/` 并存，**不要按 `analysis/` 的模式去理解它**：

- `AimA`–`AimW`（现存 17 个，字母不连续：A B C D E G H J K M N P Q R S V W）各自独立实现，没有父子拆分，也没有共用的采样门控；很多用 `AimTargetTraceUtil`（在 `utils/aim/`）算目标框方向。
- `aim/processor/AimProcessor` 不是检测，是**旋转量预处理器**：它在 `process()` 里 `rotationUpdate.setProcessor(this)`，算出 `deltaYaw`/`deltaPitch`/`optimalYaw`/`sensitivityX` 等供别的检查读取。`PlayerData.getHorizontalSensitivity()` 也走它。
- `aim/processor/Cinematic` 只负责在 `RotationUpdate` 上写 `isCinematic`/`isCinematic2`，不 flag。
- `AimV` 是 `PacketCheck`（不是 `RotationCheck`），把旋转流喂给 `analysis/aim/trajectory/` 的 5 个 `AimDetectionStrategy`（`AccelerationDetection`、`AimPathAnalysis`、`CorrelationAnalysis`、`CoordinationDetection`、`FrictionDetection`）；策略通过 `DetectionContext.flagDetection()` 回调 `getCheck(AimV.class).flagAndAlert(...)`。这是另一处「父检查持窗口、子策略只报」的形状，但实现在 `PacketCheck` 上。

**`rotationChecks` 的插入顺序同样是语义**：`AimProcessor` 必须在最前（后面的检查在 `process` 里读它写好的 `update.getProcessor()`），`Cinematic` 必须在 `AimB`/`AimD` 之前（后者读它写的 `isCinematic2`）。但注意 `Cinematic` 现在**恒定写 `false`**（原先的 `function.allowed-cinematic` 配置项已删），所以 `isCinematic()`/`isCinematic2()` 实际上永远是 `false`——`AimB`/`AimD`/`AimG`/`AimK` 里读它们的分支不会命中，这不是 bug，是配置项移除后的遗留。

### 配置

`configuralize` 的 `DynamicConfig` 加载 4 个文件（`config.yml`、`messages.yml`、`punishments.yml`、`discord.yml`），默认值来自 `common/src/main/resources/{config,messages,punishments,discord}/{en,zh}.yml`，按 `user.language` 系统属性选语言，缺失回退英文。改配置键要同时改 en / zh 两份资源与 `docs/config.md`。

**配置补齐**：`ConfigCompleter` 在 `saveAllDefaults(false)` 之后、`loadAll` 之前运行，把 `en/zh` 资源里有、用户文件里没有的键（连同注释）插入 `config.yml` / `messages.yml` / `discord.yml`，插入前备份为 `*.yml.bak`。它只做插入：已有行不重写、不排序、不删除，第二次运行是 no-op。`punishments.yml` 刻意排除（用户自定义数据）。

`config.yml` 末尾的 `config-version` 是**纯标记**：每次运行都从资源文件取值并强制写入（就地改写旧值、缺失则追加），**不用它决定补哪些键**——补齐永远看实际缺了什么。资源文件里没有标记时不写（`currentVersion` 返回 -1，不是 0）：没有版本可盖就不该在每台服务器上凭空写一个。新增配置键时把它加进 en/zh 资源即可，不需要写迁移分支。它的取值约定是**上一个 release 的 config-version + 1**（见 `e63a8de`），不是开发过程中随手递增——数字要能对应到某个已发布版本。曾经的 `upgrade()` 迁移体系从未被调用，已于 2026-10-28 删除，不要恢复。

写入前会用 SnakeYAML 解析一遍合并结果，解析失败就放弃写入并报错；写入走临时文件 + `ATOMIC_MOVE`。补齐逻辑原先由 `ConfigCompleterTest` 与 `ConfigCompleterShippedFilesTest`（后者用真实资源文件验证）守着，它们已于 `0c178da` 随 `common/src/test` 删除；改动 `ConfigCompleter` 时要手工验证：改一个键 → 启动 → 确认它出现在用户的 `config.yml` 里且 `*.yml.bak` 已生成 → 再启动一次确认是 no-op。

`/watchneko reload` → `WatchNekoExternalAPI.reloadAsync()` → 依次重载各 manager、重建 `CloudGateway`、再让在线玩家重载自己的 checks。数据库设置在文档里标注为需重启。

### 云端检测（AnalysisA）

`cc.watchneko.cloud` 是唯一与云端通信的包：

- `CloudGateway` 是进程级、`volatile` 发布。`current() == null` 是**正常状态**（未启用 / 未配置 / 不可达），此时检测整体不工作且 fail-open；恢复由 30 秒定时器重试，不需要重启。
- `canInfer() == false` 不等于 `current() == null`：冷启动（云端可达、已认证、但还没有已发布模型）和特征维度不符都产生一个 **非 null 但不可推理**的 gateway。这个区别是刻意的——录制样本只需要可达的云端，把「无模型」当成不可用会让第一个模型永远训练不出来。这两种状态下 `/watchneko record` 仍然可用。
- `AnalysisA`（RotationCheck）在攻击锚点处取 `RotationWindow`（`RADIUS = 5`，即 11 次旋转更新 × yaw/pitch = 22 维），攒批异步推理。云端裁决只变成**票**，是否告警由本地 `VerdictVotes` 按 `analysis-window-size` / `min-cheat-votes`（默认 10 / 8）决定——云端从不惩罚。429 不关检测，而是进入配额门控（`quota-cooldown-ms`，默认 30s），期间只发 `energy >= local-gate-min-energy` 的窗口。锚点队列在 `onAttack` 时若检测不可用就不入队，`collectCompletedSamples` 丢弃已经落到窗口最老帧之前的锚点——否则队首一条永远 extract 不出来的锚点会堵死队列，检测对这名玩家永久失效（`83737eb`）。
- 录制会话（`/watchneko record start`）期间样本只进 `RecordingSession`，**不送推理**：训练数据不能影响它要训练的检测。玩家断线自动 pause，回来 resume。样本 id 以会话 id 为作用域，否则重连会撞上云端已有的 id。
- 玩家名以**明文**随样本上行（无哈希、无 salt），用于归属与按名撤销；原始角度不出服务器，只有归一化后的 `features` 上线。上传历史里的玩家名存在 `recording_sessions.players`，不查 `samples`——撤销会删样本，而"这批是谁的"正是撤销之后最该留住的记录。

### 契约纪律

`contract/README.md` 是权威。语料在 `contract/fixtures/*.json`，**现在只有 Python 侧在读它**（`cloud-service/tests/test_*contract*.py` + `contract_assertions.py` 的 `assert_same_json`，逐层断言对象字段集合完全相等）。Java 侧那套镜像测试（`ContractAssertions` / `ContractFixtures` / `ContractDriftTest` 等）随 `common/src/test` 一起删掉了，所以「两侧一起红」这个保障目前只剩一侧——改契约时要么先把 Java 侧测试补回来，要么手工核对 `cc.watchneko.cloud` 的 `@SerializedName` 映射。

改契约的唯一正确顺序：先改 fixture → 测试变红 → 再改实现 → 变绿。`test_contract_drift.py` 专门证明这个比较是严格的（改名一个字段、删一个字段、多一维向量都必须被拒），不要为了让测试通过而放宽 `contract_assertions.py`。测试还断言 `case_names()` 的完整列表，新增 fixture 会迫使实现跟上。

### 云端服务

`app/` 分层：`main.py`（契约端点，只接受依赖注入）、`console.py` + `templates/`（服务端会话守卫；每个视图一个文档，非 SPA）、`admin_api.py` + `admin_auth.py`（控制台 API 与管理员会话，与租户密钥是两套身份）、`static/`（每个视图一个 `page-*.js` + 共享的 `console.js`）、`schemas.py`（pydantic `extra="forbid"`）、`auth.py`（401 / 403 分开）、`tenants.py`（密钥只存 SHA-256）、`quota.py`、`store.py`（SQLite）、`dataset.py` / `train.py` / `publish.py` / `ops.py` / `scheduler.py`（训练 → 发布 → 回滚，健康判据是唯一刹车）、`model.py`（`ModelRegistry` 持有「当前生效的模型 + 与它绑定的 scorer」，`classify()` 用模型自带的阈值把分数变成裁决）、`metrics.py`。`serve.py` 是组合根，也是唯一决定每个接缝用哪个实现的模块。

`model.py` 的 `StubScorer` 恒返回 0.0，只用于契约测试固定裁决的**形状**，不代表任何检测能力；`ModelRegistry` 冷启动时 `current()` 为 `None`，正是插件侧 `canInfer() == false` 对应的服务端状态。训练后端在 `config.yaml` 的 `training.trainer` 选（默认 `logistic`，无原生依赖；`lightgbm` 需要 OpenMP），`serve.build_service()` 在请求的后端不可用时回退并打日志，不会静默。

新增一个控制台页面要同时改三处：`templates/<page>.html`、`static/page-<page>.js`、`console.py` 的 `NAV` 与 `PAGES`。`tests/test_console_pages.py` 会枚举页面列表，漏改会红。

**存储迁移**：`store.py` 的 `_TABLES` / `_migrate()` / `_INDEXES` 三段**顺序是语义**——建表 → 迁移 → 建索引。`CREATE INDEX IF NOT EXISTS` 判的是索引名而不是列，所以把引用新列的索引写进建表段，会让**正好需要迁移的旧库**在建索引时因列不存在而启动失败（`no such column`）。新增列要同时改 `_TABLES`（新库）和 `_migrate()`（旧库），迁移必须可重入（按 `PRAGMA table_info` 判断列是否已存在）。改列名用 `ALTER TABLE ... RENAME COLUMN`，不要 DROP + 重建：前者会一并改写索引定义、保留主键，也不会在两条语句之间丢表。

`conftest.py` 把 `cloud-service/` 塞进 `sys.path`，所以 `pytest` 不用安装包就能跑；测试从 `contract/fixtures` 按相对路径读取语料（`parents[2]`），**不要在 `cloud-service/` 里移动测试目录**。

**测试要能在没有 Python 的机器上验证**：`test_config.py` 里有几个用例曾经假设 POSIX 路径语义（`/var/lib/wn.db` 在 Windows 上不是绝对路径），一跑就红，掩盖了真正的失败。写路径断言时用 `tmp_path`，不要写字面量绝对路径。

## 约定

- `checks/impl` 只放检测实现、检测入口与子检测（见该包 `package-info.java`）；工具类进 `utils`，特征提取与投票策略进 `analysis`，与云端通信进 `cloud`。
- 测试要能在没有 PacketEvents 的情况下驱动检测：用 `RotationSequence` / `AttackAnchor` 这类框架无关的接缝，不要构造 `PacketEvent`（这正是它们存在的原因，尽管现在没有 Java 测试在用）。云端侧同理——`CloudTransport` 是接口，契约测试用 `TestClient` + `contract/fixtures` 回放，不要起 HTTP 服务器。
- 面向用户的文档是中文（`docs/`、`README.md`）；Java 代码与注释以英文为主（个别历史注释是中文）；提交信息以中文为主。
- **面向用户的文本只讲用户能做什么**：`docs/`、`README.md`、`common/src/main/resources/*/*.yml` 的注释会原样出现在用户服务器上。不要写类名 / 方法名 / 包路径、不要写「参考实现」「上游」「移植」这类来源说明、不要写修改历史与版本对比、不要解释代码为什么这么写。配置注释只回答「这个参数影响什么」。实现细节和对齐说明写进代码注释，不要进配置。
- 许可证：`LICENSE` 是完整的 GPLv3，`README.md` 与两个 `config*.yml` 头部都标明基于 Grim 修改。不要往这些地方加超出 GPLv3 的附加限制（历史上配置头部曾有一句「修改版必须私有」，与 GPLv3 冲突，已删除）。
- 与 Grim / EdGrim 不能共存：检测到就关服（`WatchNekoBukkitLoaderPlugin.onLoad`）。
- Issue 与 triage 流程见 `AGENTS.md` 与 `docs/agents/`（GitHub issues，`gh` CLI；标签 `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`）。
