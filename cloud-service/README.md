# 云端检测服务

WatchNeko 云端旋转检测的后端。接收插件上传的特征向量，返回裁决；同时负责样本存储、数据集版本、模型训练、发布与回滚，以及一个管理控制台。

插件侧怎么接进来，见 [`docs/cloud-detection.md`](../docs/cloud-detection.md)。

---

## 跑起来

```bash
cd cloud-service
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python run.py
```

首次启动**自动生成 `config.yaml`** 并打印管理员密码：

```
WARNING 已生成配置文件 /opt/watchneko-cloud-analysis/config.yaml
WARNING 管理员账号 admin，密码 7kR2xQ9mZpL4vN8sT1wY
WARNING 请修改密码后重启；该密码只在这里和配置文件里出现。
```

然后访问 `http://<host>:8000/admin/`。

需要 Python 3.12+。Linux 下 `lightgbm` 开箱可用；macOS 需要额外的 OpenMP 运行时，见[训练后端](#训练后端)。

---

## 配置

**没有环境变量。** 服务读取一个 YAML 文件，**首次启动时自动生成**，之后不再覆盖——改完重启生效。

```bash
.venv/bin/python run.py                      # 默认读 ./config.yaml
.venv/bin/python run.py --config /etc/wn.yaml
```

生成的文件每一项都带注释：

```yaml
server:
  host: 0.0.0.0
  port: 8000

admin:
  username: admin
  password: "首次生成时随机产生"      # 请改成你自己的
  secret: "会话签名密钥"              # 改动会让所有登录会话失效
  session_hours: 8

database:
  path: watchneko.db                 # 相对路径以本文件所在目录为准
  retention_days: 90

quota:
  samples_per_minute: 6000           # 新租户的默认配额；0 表示不限

training:
  trainer: logistic                  # logistic | lightgbm
  interval_seconds: 3600             # 设为 0 关闭自动重训
```

删掉任意一项会退回默认值；`port: 0` 这类非法值会在启动时报错并指出是哪一项。

**`config.yaml` 与数据库都不进版本库**（已在 `.gitignore` 里）——前者含明文密码。

Docker 下整份配置和数据都在一个卷里：

```bash
docker compose up -d          # 首次启动在 /data/config.yaml 生成配置
docker compose logs cloud     # 这里能看到生成的管理员密码
```

---

## 管理控制台

`http://<host>:8000/admin/`

**每个视图是一个独立文档，不是单页应用。**

| URL | 内容 |
| --- | --- |
| `/admin/login` | 登录 |
| `/admin/dashboard` | 五类指标、裁决分布、模型操作 |
| `/admin/tenants` | 租户增删改查、密钥轮换/吊销 |
| `/admin/datasets` | 每个租户的数据集版本与可训练状态 |
| `/admin/models` | 模型历史、回滚状态 |

外壳（导航栏、顶栏）只在 `templates/base.html` 里写一次，四个视图 `{% extends %}` 它。

**会话守卫在服务端**：未登录请求 `/admin/tenants` 拿到的是 303 重定向，页面标记根本不会发到浏览器。

界面用 Material Design 3 的 token 层手写，无构建步骤，由 FastAPI 直接托管静态文件。跟随系统亮/暗主题。

### 两种凭据是分开的

| 凭据 | 谁用 | 能做什么 |
| --- | --- | --- |
| 租户 API key（`wnk_` 开头） | 插件 | 上传样本、请求裁决 |
| 管理员登录 | 人 | 改租户、轮换密钥、回滚模型 |

插件手里的 key 不能开控制台——否则一个被攻破的服务器就能吊销所有租户。

### 密钥的生命周期

租户与密钥存在 SQLite 的 `tenants` 表里，密钥**只存 SHA-256 摘要**，明文仅在创建或轮换时显示一次。所以界面上有「复制」按钮，关掉对话框就再也拿不到——这是设计，不是缺陷。

轮换会**立即**让旧 key 失效，没有重叠期。正在用旧 key 的服务器会立刻开始 401，轮换确认框里写明了这一点。

### 配额

按**样本数/分钟**计，按租户算。一个租户挂多个服务器时它们共享额度。

- `0` = 不限
- 新建租户不填时，用 `config.yaml` 的 `quota.samples_per_minute`
- 超额返回 `429`，插件进入冷却：只发送能量高的窗口

在控制台的租户页随时可以改单个租户的配额。

---

## 训练后端

`available_trainers()` 探测可用后端：

| 后端 | 依赖 | 说明 |
| --- | --- | --- |
| `logistic` | 无 | 纯 Python 批量梯度下降，确定性，永远可用 |
| `lightgbm` | `lightgbm` + `scikit-learn` | 梯度提升树；`deterministic=True`、`force_row_wise=True` |

在 `config.yaml` 的 `training.trainer` 里选，默认 `logistic`。两者产出的模型**都可服务**：`predictor_from_artifact` 能从各自产物重建打分器（logistic 从权重，LightGBM 从 booster 模型字符串）。这一点很关键——模型若无法加载，发布与回滚就只是摆设。

### macOS 上的 libomp

LightGBM 的 macOS 轮子不带 OpenMP 运行时，`import lightgbm` 会报 `Library not loaded: @rpath/libomp.dylib`。**Linux 轮子自带**，容器里不需要任何额外步骤。

macOS 上有两条路：

1. `brew install libomp` —— 需要能写 `/usr/local`。
2. 写不了 `/usr/local` 时，把 conda-forge 的 `llvm-openmp` 取进 venv 并给 LightGBM 的 dylib 加 rpath：

```bash
curl -sL https://conda.anaconda.org/conda-forge/osx-64/llvm-openmp-23.1.4-h8c1e6b9_0.conda -o /tmp/lo.conda
mkdir -p /tmp/lo && cd /tmp/lo && unzip -q ../lo.conda && tar --zstd -xf pkg-*.tar.zst

LGB=.venv/lib/python3.14/site-packages/lightgbm/lib/lib_lightgbm.dylib
mkdir -p .venv/lib/libomp-runtime
cp /tmp/lo/lib/libomp.dylib .venv/lib/libomp-runtime/
install_name_tool -add_rpath "$PWD/.venv/lib/libomp-runtime" "$LGB"
codesign --force --sign - "$LGB"          # install_name_tool 会破坏签名
```

---

## 训练与发布

重训周期由 `training.interval_seconds` 驱动，每个周期做四件事：**清理过期样本 → 判断当前模型健康 → 重训 → 发布**。

**服务启动时会立刻跑一次重训**，不等第一个周期。模型不持久化（只存在于进程内存），重启后服务手上没有模型，不立刻重训的话会持续返回 `404 no_model`，所有连上来的插件都会处于检测关闭状态，最长可达一个完整的 `interval_seconds`。样本在 SQLite 里，恢复一次重训的成本而已。

也可以在控制台点「立即重训」。

**阈值随模型一起推导并下发**，投票阈值留在插件侧（`analysis-window-size` / `min-cheat-votes`）。这样换模型不需要改插件配置。

**发布是自动的，回滚也是。** 健康判据是唯一的刹车：错误率过高或裁决分布异常时自动退回上一个模型。回滚不删除任何东西——被回滚的模型仍保留、仍可原样服务，这正是回滚安全的原因。

**模型不落盘。** 发布历史只在进程内存里，重启即清空。重启后第一个重训周期（启动时立即执行）会从 SQLite 的样本重新训出模型，模型 ID 由数据集内容决定，所以数据没变时训出来的还是同一个 `model_id`。代价是重启后到重训完成之间检测是关闭的；收益是不用维护模型文件的版本、清理与损坏处理。

---

## 部署

`deploy.sh` 把「本地测试通过才上线」写成了代码，而不是靠记性：

```bash
./deploy.sh root@<host> /opt/watchneko-cloud-analysis            # 部署
./deploy.sh root@<host> /opt/watchneko-cloud-analysis --check    # 只核对，不上传

SSH_PORT=46011 ./deploy.sh root@<host> /opt/watchneko-cloud-analysis
SSH_EXTRA_OPTS="-o StrictHostKeyChecking=accept-new" ./deploy.sh ...
```

四步，任何一步失败即中止：

1. **跑测试** —— 不过就不发一个字节
2. 计算本地代码指纹
3. 上传（`.venv`、`config.yaml`、数据库、`tests/` 都不进包）
4. 重算服务器指纹逐文件比对 —— 不一致即报错

主机密钥的信任策略刻意不写死在脚本里，用 `SSH_EXTRA_OPTS` 由你的机器决定。认证走你的 ssh 配置，密码不进仓库。

### MCSManager

实例类型选**应用实例**，启动命令：

```
bash /opt/watchneko-cloud-analysis/start.sh
```

`start.sh` 用 `BASH_SOURCE` 推导自身目录，不硬编码路径——MCSManager 会把工作目录设成实例目录，相对路径在那儿会指错地方。它也 `exec` 掉 shell，SIGTERM 才能直达 uvicorn 完成优雅关闭。

### 反向代理

控制台是明文 HTTP，**管理员密码和 API key 会明文过网**。公网部署前必须有 TLS 终止：

```nginx
location /admin/ {
    proxy_pass http://127.0.0.1:8000;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

配好后把 `config.yaml` 的 `server.host` 改成 `127.0.0.1`，8000 端口就不再对外。

顺带一提：控制台的「复制密钥」按钮在非安全上下文里拿不到 `navigator.clipboard`，代码有降级（`execCommand` → 自动选中文本），但套上 HTTPS 才是根治。

---

## 运维

### 备份

两样东西值得备份，都在 `config.yaml` 所在目录：

| 文件 | 内容 | 丢了会怎样 |
| --- | --- | --- |
| `config.yaml` | 管理员密码、会话密钥、数据库路径 | 控制台登不进去（可重新生成，但密码变了） |
| `watchneko.db` | 租户、密钥摘要、样本、录制会话 | **租户全部消失**，插件立刻 401 |

```bash
systemctl stop watchneko     # 或 MCSManager 里停止实例
cp config.yaml watchneko.db /backup/
```

数据库是 SQLite，停服后复制即可。热备份请用 `sqlite3 watchneko.db ".backup /backup/wn.db"`。

### 升级

```bash
./deploy.sh <target> <dir>          # 代码
<dir>/.venv/bin/pip install -r <dir>/requirements.txt   # 依赖有变动时
# 在 MCSManager 里重启实例
```

依赖没变时只需要前者加重启。

### 观测

`GET /metrics`，Prometheus 文本格式，**不需要认证**（不含玩家数据）。主要指标：

| 指标 | 含义 |
| --- | --- |
| `watchneko_samples_total` | 各租户收到的样本数 |
| `watchneko_verdicts_total` | 按裁决类别计数 |
| `watchneko_requests_total` | 请求数 |
| `watchneko_errors_total` | 错误数 |
| `watchneko_model_id` | 当前模型 |
| `watchneko_queue_backlog` | 尚未被训练到的样本 |

`/metrics` 不认证是有意的：给运维 scraper 加 tenant key 只会让密钥多存在一个地方。

### 排错

| 现象 | 排查 |
| --- | --- |
| 控制台打不开 | 8000 是否监听、云厂商安全组是否放行 |
| 插件一直 401 | 租户不存在、密钥被轮换、`server-id` 不在租户的 servers 列表里 |
| 插件一直 404 `no_model` | **正常冷启动**，还没发布模型；或刚重启，等启动时那次重训跑完 |
| 数据集显示「缺一类」 | 只录了 cheat 或只录了 legit，训练需要两类都有 |
| 重训后模型没变 | 数据集内容没变（`dataset_id` 由样本内容决定），或新模型没通过健康判据被回滚 |
| 想登出所有会话 | 改 `config.yaml` 的 `admin.secret` 并重启 |

---

## 有意保留的限制

- **一次训练只服务一个租户的数据集**：`ops.py` 按租户遍历，第一个可训练的租户胜出。跨租户汇总训练需要先有租户级样本权重，否则样本量大的租户会主导模型。
- **回滚不会删除任何东西**：历史只前进指针。
- **`/metrics` 不做认证**：见上。
- **没有登录限速**：暴力破解无防护，公网暴露前建议在代理层加限流。
- **特征向量固定 22 维**：这是已知的检测能力上限，扩展维度需要同步改插件、契约和模型。

---

## 开发参考

### 模块

```
app/
  main.py       三个契约端点 + /metrics；create_app 只接受注入，不自己造依赖
  console.py    控制台页面路由（服务端会话守卫）
  templates/    每视图一个文档 + 共用外壳 base.html
  static/       md3.css、console.js、page-*.js
  schemas.py    线上形状（pydantic），未知字段一律拒绝
  auth.py       租户认证；401（不知道你是谁）与 403（知道但无权）分开
  tenants.py    租户表；密钥只存摘要，轮换与吊销
  admin_auth.py 管理员会话（HMAC 签名，无状态）
  admin_api.py  控制台调用的管理 API
  quota.py      按样本数计的滑动窗口，全有或全无；0 表示不限
  store.py      SQLite 双表（录制会话 + 样本），按租户隔离，保留期、上传记录与撤销、按玩家名删除
  dataset.py    按内容寻址的数据集快照
  train.py      可插拔训练后端；阈值随模型推导
  publish.py    发布历史与回滚；健康判据
  ops.py        一个周期：清理 → 判断 → 重训 → 发布
  scheduler.py  周期驱动 + 让服务跟随发布切换模型
  metrics.py    五类指标的 Prometheus 文本输出
  config.py     YAML 配置的读取与首次生成
  serve.py      组合根
```

线上契约见 [`contract/README.md`](../contract/README.md)，两侧测试读同一批 fixture。

### 跑测试

```bash
.venv/bin/python -m pytest        # 214 个用例
```
