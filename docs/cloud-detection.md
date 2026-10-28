# 云端检测接入指南

这份文档带你从零把云端旋转检测跑起来：搭服务 → 建租户 → 配插件 → 验证 → 训练出第一个模型。

只想看配置项的话，去 [`config.md` 的 AnalysisA 一节](config.md#analysisa--云端旋转检测)。

---

## 它是怎么工作的

```
玩家旋转 → 插件采样 → 提取 22 维特征向量 → 上传云端 → 云端返回裁决
                                                          ↓
                                              本地累计 VL，按 punishments.yml 决定是否踢人
```

三件事值得先说清楚：

- **原始数据不出服。** 上传的是归一化后的特征向量，角度、坐标不上传。但**玩家名是明文上传的**，见下面的「隐私说明」。
- **判定在云端，惩罚在本地。** 云端只回「cheat-like / legit-like / uncertain」，是否踢人仍由你 `punishments.yml` 里的规则决定。
- **云端挂了不会误伤。** 超时、报错、不可达一律 fail-open，不产生任何 VL。

---

## 第一步：准备云端服务

### 自己部署

需要一台能被你的 Minecraft 服务器访问到的机器，Python 3.12 或 Docker。

```bash
cd cloud-service
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python run.py
```

首次启动会**自动生成** `config.yaml` 并打印管理员密码：

```
WARNING 已生成配置文件 .../cloud-service/config.yaml
WARNING 管理员账号 admin，密码 7kR2xQ9mZpL4vN8sT1wY
WARNING 请修改密码后重启；该密码只在这里和配置文件里出现。
```

把密码记下来，然后访问 `http://<你的机器>:8000/admin/` 登录。

部署细节（systemd、MCSManager、反向代理、LightGBM 后端）见 [`cloud-service/README.md`](../cloud-service/README.md)。

### 用别人搭好的

向服务提供者要两样东西：**服务地址**和**租户 API key**。后面的步骤从第二步开始。

---

## 第二步：在控制台建租户

登录控制台 → 左侧 **租户** → **新建租户**：

| 字段 | 填什么 | 说明 |
|---|---|---|
| 租户 ID | 任意，如 `survival` | 只是个名字 |
| server-id | 你的服务器标识，如 `survival-1` | **要和插件里填的一致**。留空表示任意 server-id 都放行 |
| 配额 | 样本/分钟，**填 0 = 不限** | 见下方配额说明 |

点「创建」后弹窗显示 API key，格式是 `wnk_` 开头的一长串。

> ⚠️ **这个 key 只显示这一次。** 数据库里只存它的 SHA-256 摘要，关掉弹窗就再也拿不回来。复制不了就点一下密钥文本全选再 Ctrl+C。真丢了只能「轮换密钥」换一个新的——旧 key 会立刻失效。

### 配额怎么定

配额是**样本数/分钟**，按租户算。一个租户挂多个服务器时它们共享这个额度。

- 一个玩家在战斗中大约每秒产生 1–2 个样本窗口，稳态下每玩家每分钟几十个样本
- 50 人在线的服务器，建议从 `6000` 起
- 不确定就填 `0`（不限），跑一段时间后在仪表盘看实际用量再收紧

超额时云端返回 `429`，插件会进入冷却：只发送能量高的窗口，静止玩家不占用额度。**超额不会丢检测**，只是灵敏度下降。

---

## 第三步：配置插件

编辑 `plugins/WatchNeko/config.yml`，找到 `AnalysisA:` 块：

```yaml
AnalysisA:
    cloud:
        enabled: true                                # 默认 false，必须改
        endpoint: "http://45.138.70.202:8000"        # 只填到端口，不带路径
        api-key: "wnk_..."                           # 第二步拿到的
        server-id: "survival-1"                      # 和租户里填的一致
        timeout-ms: 2000
    batch-size: 8
    analysis-window-size: 10
    min-cheat-votes: 8
    local-gate-min-energy: 1.25
    quota-cooldown-ms: 30000
    debug-log: true                                  # 调试期开着，稳定后关掉
```

**`endpoint` 只填到端口。** `/v1/inference`、`/v1/model` 这些路径由插件自己拼。带不带末尾斜杠都一样。

然后重载：

```
/watchneko reload
```

权限是 `watchneko.reload`。

---

## 第四步：验证连通

看服务器控制台。**成功时没有任何输出**——`AnalysisA` 安静地拉一次模型元数据，然后进入等待。

**失败**时每 30 秒刷一条：

```
[ERROR]: [WatchNeko] AnalysisA could not reach the cloud; detection stays disabled:
  cc.watchneko.cloud.CloudContractException: model metadata answered 401:
  {"detail":"unknown or revoked credentials"}
```

按这个表排查：

| 报错 | 原因 |
|---|---|
| `answered 401` | `api-key` 或 `server-id` 不对。最常见的是把**控制台管理员密码**当成 API key 填进去了——管理员密码是登录网页用的，API key 是 `wnk_` 开头的那串 |
| `answered 403` | key 认得，但 `server-id` 不在该租户的 servers 列表里 |
| `answered 404` + `no_model` | **这不是错误**。云端还没发布模型，属于正常冷启动，见第六步。此时录制仍然可用 |
| `Connection refused` / `timed out` | 网络不通，或 `endpoint` 写错 |
| `enabled but endpoint, api-key or server-id is missing` | 三个必填项有空值 |

云端侧的确认方法：控制台仪表盘看 **请求数** 是否在涨，**租户** 页看「最近使用」时间。

---

## 第五步：训练出第一个模型

**冷启动期间检测是不启用的。** 云端 `GET /v1/model` 返回 `404 no_model`，插件收到后不发送任何推理请求——没有模型就没法判定，假装在工作只会浪费流量。

但**录制照常可用**。没有模型不等于云端不可用：`404 no_model` 恰恰证明地址通了、密钥被接受了，而这正是上传样本需要的全部条件。所以第一个模型永远能从一台还没模型的服务器上录出来——如果没模型就连录制也禁掉，那第一个模型就永远训不出来了。

训练流程：

**1. 录制作弊样本**

找一个明确的作弊客户端，或者用测试账号，进服后：

```
/watchneko record start <玩家> cheat
```

让它跑 30 秒到 2 分钟，尽量包含真实战斗中的瞄准行为，然后：

```
/watchneko record stop <玩家> upload
```

**2. 录制正常样本**

同样的流程，标 `legit`，找几个不同类型的玩家（PVP 强的、新手、用手柄的）。

**3. 检查数据集**

控制台 → **数据集** → 确认「样本」数量够、`cheat` 和 `legit` **两类都有**。只有一类时状态显示「缺一类」，不可训练。

**4. 发布模型**

控制台 → **仪表盘** → **立即重训**。

训练完会自动发布（默认 `logistic` 后端；想让训练走梯度提升树，把 `config.yaml` 的 `training.trainer` 改成 `lightgbm`）。

**5. 确认生效**

- 仪表盘「当前模型」不再是「无」
- 插件侧的 `401`/`404` 日志停止
- 请求数开始随在线玩家增长

---

## 第六步：调阈值

模型发布后，两个地方控制灵敏度：

### 本地投票（`config.yml`）

```yaml
analysis-window-size: 10    # 累计多少个样本裁决后开始判断
min-cheat-votes: 8          # 其中至少多少个 cheat-like 才告警
```

默认是 10 个裁决里 8 个判为作弊。**误报多就调高** `min-cheat-votes`（最高到 10），**漏报多就调低**（比如 6 或 7）。

### 惩罚（`punishments.yml`）

`AnalysisA` 自动继承 `Analysis` 组：

```yaml
Analysis:
    remove-violations-after: 300
    checks:
        - "Analysis"
    commands:
        - "1:1 [log]"
        - "5:5 [alert]"
        - "5:5 [webhook]"
        - "5:5 [proxy]"
        - "25:0 kick %player% cheating"
```

`25:0 kick` 表示累计 25 个 VL 时踢人。300 秒内没有新 VL 会自动衰减。

想先观察不踢人，把最后一行删掉或改成更高的数字。

---

## 常见问题

### 检测看起来完全没反应

按顺序确认：

1. `cloud.enabled` 是不是 `true`
2. 控制台里有没有租户、key 是否 `wnk_` 开头
3. 插件控制台有没有 30 秒一次的 ERROR
4. 云端「当前模型」是不是「无」——是的话还没训练，检测本来就不启用（**此时录制不受影响**）
5. `debug-log: true` 看有没有样本被丢

### 一直报 401

九成是 `api-key` 填错了。它**不是**控制台登录密码，是创建租户时弹窗里那串 `wnk_...`。丢了就轮换。

### 样本上传了但数据集是「缺一类」

只录了 `cheat` 或只录了 `legit`。两类都要有，而且都要有足够数量。

### 误报太多

先调高 `min-cheat-votes`，再看 `local-gate-min-energy`。误报集中在静止玩家身上时，提高 `local-gate-min-energy` 让低能量窗口在配额紧张时不上传。

### 想彻底关掉

```yaml
AnalysisA:
    cloud:
        enabled: false
```

改完 `/watchneko reload`。已上传的数据不受影响，随时可以重新打开。

### 玩家要求删除自己的数据

控制台 → **上传记录**，按玩家名找到那次上传并撤销；或直接调 `DELETE /v1/samples/{玩家名}` 删除该玩家的全部样本（只影响你自己租户的数据）。

---

## 隐私说明

| 数据 | 是否上传 |
|---|---|
| 22 维归一化特征向量 | ✅ 上传 |
| **玩家名（明文）** | ✅ 上传 |
| 玩家 UUID | ❌ |
| 视角角度、坐标 | ❌ |
| 聊天、IP | ❌ |

**玩家名是明文的，没有哈希、没有 salt。** 这意味着：

- 任何能访问云端服务或管理台的人，都能看到谁在什么时候玩过、以及他的操作特征。
- 传输层不强制加密：`endpoint` 填 `http://` 就是明文过网，填 `https://` 才有 TLS。**公网部署请务必用 HTTPS 或至少走内网。**
- 同一个玩家在两台不同租户的服务器上会被识别为同一个人。
- 你不再需要保管 salt，也不会因为换 salt 丢历史数据。

管理台 → **上传记录** 里可以按名字看到每次上传包含哪些玩家，并**撤销某一次上传**：该批样本会被删除，下一次训练就不会再用到它们。已经训练好的模型不受影响（要回滚模型请用「模型」页）。

撤销记录会留在列表里（样本数显示为 0），并且**仍然记得这批是谁的**——玩家名存在会话行上，不随样本一起删掉。如果同一会话后来又上传了新样本，那次上传会重新变成可撤销，因为库里又有数据了；样本已经被保留期清空的上传也能点「标记已撤销」，只是不删任何东西。

样本默认保留 90 天（`config.yaml` 的 `database.retention_days`），由重训周期顺带清理。

---

[⬅️ 返回 README](../README.md) · [config.yml 详解](config.md) · [云端服务部署](../cloud-service/README.md)
