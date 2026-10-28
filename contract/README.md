# 插件 ↔ 云端 HTTP 契约

这是 WatchNeko 插件与云端服务之间**唯一**的契约面。两侧各有一份实现，除此之外不共享任何代码：

- 插件侧：`cc.watchneko.cloud`（`common` 模块）
- 服务侧：`cloud-service/`（Python，FastAPI）

契约由本目录下的 `fixtures/` 语料固定。两侧的测试读同一批文件：**任一侧改动契约，两侧测试必须同时变红**。改动契约的唯一正确姿势是先改 fixture，再让两侧测试一起红、一起绿。


---

## 通用约定

| 项 | 约定 |
| --- | --- |
| 版本 | 每个请求与响应体都带 `contract_version`（整数）。当前为 `1`。 |
| 编码 | JSON，UTF-8。 |
| 字段命名 | `snake_case`（线上唯一形态；Java 侧用 `@SerializedName` 映射）。 |
| 认证 | 请求头 `Authorization: Bearer <租户 API key>` 与 `X-Server-Id: <server-id>`。本 ticket 只固定头名与形状，不实现认证。 |
| 特征向量 | `features` 是长度为 `22` 的数值数组。维度见下文。 |
| 错误形状 | 非 2xx 响应体为 `{"contract_version": 1, "error": "<code>"}`。已固定的 code：`no_model`（冷启动）、`invalid_request`（请求形状不合法）。 |

裁决只累加 VL，惩罚由本地规则判定；超时、4xx、5xx 一律 fail-open。这是**插件侧**的行为，不属于线上契约，故此处不固定。

---

## 特征向量

`features` 固定 22 维 = 窗口内每个旋转更新的 yaw / pitch 增量各派生 11 项归一化量。窗口以攻击锚点为中心，向前后各取固定次数的**旋转更新**（见 ADR-0001）。

契约只固定「22 个数值、顺序固定、已归一化」这三件事。具体是哪 11 项派生量属于特征提取实现（ticket #12），不在本契约内；因此本契约**不**扩展特征、也**不**允许两侧对维度产生分歧——`feature_dimensions` 由模型元数据下发，插件必须与自己的特征向量长度核对。

原始角度不出服务器：`features` 是离开服务器的唯一**行为**数据形态。但玩家名以明文随样本上行（见下节），所以「服务器上发生了什么」仍然可以从云端看出来。

---

## 玩家标识

`player_name` 是玩家的名字，**明文传输、明文存储、明文显示**。没有哈希，没有 salt。

这样做的代价要说清楚：

- 任何能访问云端服务、管理台或抓到流量的人，都能看到谁在什么时候玩过、以及他的操作特征。
- 传输层**不强制**加密。endpoint 填 `http://` 就是明文过网，填 `https://` 才有 TLS。
- 同一个玩家在两个租户的服务器上会被识别为同一个人——租户之间不再有哈希隔离。

这样做的收益：运营者能在管理台按名字找到某个人的数据、撤销某一次上传；不需要保管 salt，也不会因为换 salt 而丢失历史数据。

玩家名不唯一（正版改名、离线服重名），所以 `sample_id` 里同时带会话 id 和计数器，云端靠 `sample_id` 去重。

---

## 1. 推理 — `POST /v1/inference`

插件攒批上传特征向量，云端按 `sample_id` 各回一条裁决。

**请求**

```json
{
    "contract_version": 1,
    "samples": [
        {
            "sample_id": "s-0001",
            "player_name": "Steve",
            "features": [22 个数值]
        }
    ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `samples[].sample_id` | string | 插件侧生成的样本标识，用于把裁决对回样本。 |
| `samples[].player_name` | string | 玩家名，**明文**。用于把样本归属到人，以及在管理台按名字查找和撤销一次上传。 |
| `samples[].features` | number[22] | 归一化特征向量。 |

**响应 200**

```json
{
    "contract_version": 1,
    "model_id": "m-0001",
    "verdicts": [
        {
            "sample_id": "s-0001",
            "score": 0.0,
            "verdict": "legit_like"
        }
    ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `model_id` | string | 产生这批裁决的模型版本，裁决可回溯到它。 |
| `verdicts[].sample_id` | string | 与请求中的样本一一对应。 |
| `verdicts[].score` | number | `[0, 1]`，cheat 方向的分数。 |
| `verdicts[].verdict` | enum | `cheat_like` \| `legit_like` \| `uncertain`。由服务侧按分类阈值把 `score` 映射得到。 |

`verdicts` 的顺序与 `samples` 一致；插件按 `sample_id` 而不是下标对回。

**没有已发布模型时**：`POST /v1/inference` 返回 404，错误码 `no_model`，复用下文的错误形状。插件不会走到这条路径（冷启动时检查根本没启用），因此没有为它单独立 fixture。

---

## 2. 样本上传 — `POST /v1/samples`

一次**录制会话**结束时，把该批已标注的**样本**上传。标注在开始时确定、在此处随批上行，云端不再事后分拣。

**请求**

```json
{
    "contract_version": 1,
    "recording_session_id": "rs-0001",
    "label": "cheat",
    "samples": [
        {
            "sample_id": "s-1001",
            "player_name": "Steve",
            "features": [22 个数值]
        }
    ]
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `recording_session_id` | string | 一次录制会话的标识，使每批样本可追溯来源。 |
| `label` | enum | `cheat` \| `legit`。整批共用同一标注。 |
| `samples[]` | object[] | 与推理请求同形状。 |

**响应 200**

```json
{
    "contract_version": 1,
    "recording_session_id": "rs-0001",
    "accepted": 2,
    "rejected": 0
}
```

`accepted + rejected` 等于请求里的样本数。`rejected` 指形状合法但服务侧拒绝入库的样本（例如重复）；形状不合法的**整个请求**会被 422 `invalid_request` 挡在入口，不会产生回执。

样本入库后组成**数据集**版本；数据集版本与模型版本构成可追溯链，通过 `GET /v1/model` 与推理响应的 `model_id` 暴露。

---

## 3. 删除某玩家的样本 — `DELETE /v1/samples/{player_name}`

按玩家名删除**调用者自己租户内**的全部样本，用于响应玩家的删除请求。

**请求**：无请求体。`player_name` 是路径参数，明文，与上传时的 `player_name` 完全一致。

**响应 200**

```json
{
    "contract_version": 1,
    "player_name": "Steve",
    "deleted": 2
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `deleted` | int | 实际删掉的样本数；没有这个玩家时是 `0`，不是错误。 |

**作用域**：只影响认证租户自己的数据。一个服务器所有者代玩家执行删除请求时，不应能碰到别人的数据。这条路径没有 fixture——它不参与插件运行时的调用，只被运营者或运维脚本使用。

删除只动样本，不动录制会话行，也不动已训练的模型；上传历史里那次上传会显示剩余样本数为 0。

---

## 4. 模型元数据 — `GET /v1/model`

插件启动时先拉这个端点。拉不到（404）则整个检查不启用、不发任何请求——这是冷启动路径。

**请求**：无请求体。

**响应 200**

```json
{
    "contract_version": 1,
    "model_id": "m-0001",
    "dataset_id": "ds-0001",
    "feature_dimensions": 22,
    "thresholds": {
        "cheat_like": 0.7,
        "legit_like": 0.3
    }
}
```

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `model_id` | string | 当前生效的模型版本。 |
| `dataset_id` | string | 训练该模型的数据集版本。 |
| `feature_dimensions` | int | 模型接受的**特征向量**维度。插件必须与自己的向量长度核对。 |
| `thresholds.cheat_like` | number | `score >= cheat_like` 判为 `cheat_like`。 |
| `thresholds.legit_like` | number | `score <= legit_like` 判为 `legit_like`；两者之间为 `uncertain`。 |

阈值分两类：**分类阈值**（上表，随模型版本下发）与**投票阈值**（把一串裁决映射为一次告警，留在本地配置）。本契约只涉及前者。

**响应 404（冷启动）**

```json
{
    "contract_version": 1,
    "error": "no_model"
}
```

---

## fixture 语料

位置：`contract/fixtures/<case>.json`，一个 case 一个文件。

```json
{
    "case": "inference_single",
    "description": "人类可读的一句话说明",
    "request": {
        "method": "POST",
        "path": "/v1/inference",
        "headers": { "authorization": "...", "content-type": "...", "x-server-id": "..." },
        "body": { }
    },
    "response": {
        "status": 200,
        "body": { }
    }
}
```

规则：

- `case` 必须等于文件名去后缀。两侧都断言这一点。
- `request.body` 为 `null` 表示该端点无请求体（GET）。
- 请求头名一律小写；两侧比较前统一小写化。
- 数值按数值比较（`0` 与 `0.0` 等价），不按 JSON 文本比较。
- **两侧都断言每一层对象的字段名集合恰好等于 fixture 的字段名集合**（不多不少）。因此把 `features` 改成 `feature_vector` 会让两侧同时红：服务侧响应形状不再匹配，插件侧字段集合不再匹配。
- 语料里的取值（`m-0001`、`0.0` 等）由契约期的桩实现产出，不代表真实模型。真实模型接上后（#13）会连同 fixture 一起改，这正是防漂移机制应有的表现。

### 当前语料

| case | 端点 | 固定的是什么 |
| --- | --- | --- |
| `inference_single` | `POST /v1/inference` | 单样本推理的请求与响应形状 |
| `inference_batch` | `POST /v1/inference` | 攒批推理：多样本、按 `sample_id` 对回 |
| `sample_upload` | `POST /v1/samples` | 录制会话批次上传与回执 |
| `model_metadata` | `GET /v1/model` | 模型版本、数据集版本、分类阈值下发 |
| `model_metadata_absent` | `GET /v1/model` | 冷启动 404 |

### 契约期的桩实现

服务侧在本阶段用桩分类器：对每个样本返回 `score = 0.0`、`verdict = "legit_like"`。它只验证形状与流程，不验证准确率（见 spec 的 Out of Scope）。`cheat_like` 与 `uncertain` 两个枚举值由两侧的枚举断言固定，而不是由桩产出。
