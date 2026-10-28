"""Configuration, from a YAML file the operator edits.

The file is **generated on first run** and then left alone: it carries comments explaining every key, and a
generator that overwrote it would throw away the operator's edits. Only a missing file is created.

Generated secrets are written into the file rather than printed once and lost, because a console nobody can
log into is worse than one whose first password lives in a file only the operator can read. The startup log
says plainly where that file is and what the password is, so the first run is usable.

Nothing here reads the environment: a service configured by a file it wrote itself is one an operator can
inspect, diff and back up.
"""

from __future__ import annotations

import secrets
from dataclasses import dataclass
from pathlib import Path

import yaml

DEFAULT_FILENAME = "config.yaml"
DEFAULT_QUOTA_PER_MINUTE = 6000
DEFAULT_RETENTION_DAYS = 90
DEFAULT_RETRAIN_INTERVAL_SECONDS = 3600
DEFAULT_SESSION_HOURS = 8

#: Written verbatim when no config exists. The comments are the documentation — a dumped dict loses them.
TEMPLATE = """\
# WatchNeko 云端服务配置
#
# 本文件由服务在首次启动时自动生成，之后不再被覆盖：改完重启生效。
# 每一项下面都有说明；删掉某一项会退回括号里的默认值。

server:
  # 监听地址。0.0.0.0 表示所有网卡；只想本机访问就改成 127.0.0.1。
  host: 0.0.0.0
  port: 8000

admin:
  # 控制台登录账号。这与租户 API key 是两套东西：插件手里的 key 不能登录控制台。
  username: admin
  # 首次生成时随机产生。请改成你自己的密码，然后重启。
  password: "__PASSWORD__"
  # 管理会话签名密钥。改动会让所有已登录会话立刻失效。
  secret: "__SECRET__"
  # 管理会话有效期（小时）。
  session_hours: 8

database:
  # SQLite 路径。相对路径以本文件所在目录为准。
  path: watchneko.db
  # 样本保留天数，由重训周期顺带清理过期样本。
  retention_days: 90

quota:
  # 新建租户时的默认配额（样本/分钟）。已有租户在控制台里单独调整。
  # 0 表示不限——想对所有租户放开就把它设为 0。
  samples_per_minute: 6000

training:
  # 训练后端：logistic（无依赖，永远可用）或 lightgbm（需要 OpenMP 运行时）。
  trainer: logistic
  # 自动重训周期（秒）。设为 0 关闭自动重训，只保留控制台手动触发。
  interval_seconds: 3600
"""


@dataclass(frozen=True)
class Settings:
    host: str = "0.0.0.0"
    port: int = 8000
    admin_username: str = "admin"
    admin_password: str = ""
    admin_secret: str = ""
    session_hours: int = DEFAULT_SESSION_HOURS
    database_path: str = "watchneko.db"
    retention_days: int = DEFAULT_RETENTION_DAYS
    quota_per_minute: int = DEFAULT_QUOTA_PER_MINUTE
    trainer: str = "logistic"
    retrain_interval_seconds: float = DEFAULT_RETRAIN_INTERVAL_SECONDS

    @property
    def has_admin_password(self) -> bool:
        return bool(self.admin_password)

    @property
    def auto_retrain(self) -> bool:
        return self.retrain_interval_seconds > 0


def load_or_create(path: Path) -> tuple[Settings, bool]:
    """Load the config, generating it first when it does not exist.

    Returns the settings and whether the file was just created, so the caller can tell the operator where it
    is and what password it got.
    """
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(
            TEMPLATE.replace("__PASSWORD__", _new_password()).replace(
                "__SECRET__", secrets.token_urlsafe(32)
            ),
            encoding="utf-8",
        )
        return load(path), True

    return load(path), False


def load(path: Path) -> Settings:
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(raw, dict):
        raise ValueError(f"{path} must contain a YAML mapping")

    server = _section(raw, "server")
    admin = _section(raw, "admin")
    database = _section(raw, "database")
    quota = _section(raw, "quota")
    training = _section(raw, "training")

    database_path = str(database.get("path", Settings.database_path))
    # A relative database path means "next to this config", not "wherever the process happened to start".
    # SQLite's in-memory name is not a path at all, so it is passed through untouched: joining it onto the
    # config directory would turn `:memory:` into a file called that.
    if database_path != ":memory:" and not Path(database_path).is_absolute():
        database_path = str((path.parent / database_path).resolve())

    return Settings(
        host=str(server.get("host", Settings.host)),
        port=_positive(server.get("port", Settings.port), "server.port"),
        admin_username=str(admin.get("username", Settings.admin_username)),
        admin_password=str(admin.get("password", "")),
        admin_secret=str(admin.get("secret", "")),
        session_hours=_positive(
            admin.get("session_hours", DEFAULT_SESSION_HOURS), "admin.session_hours"
        ),
        database_path=database_path,
        retention_days=_positive(
            database.get("retention_days", DEFAULT_RETENTION_DAYS), "database.retention_days"
        ),
        quota_per_minute=_non_negative_int(
            quota.get("samples_per_minute", DEFAULT_QUOTA_PER_MINUTE), "quota.samples_per_minute"
        ),
        trainer=str(training.get("trainer", "logistic")),
        retrain_interval_seconds=_non_negative(
            training.get("interval_seconds", DEFAULT_RETRAIN_INTERVAL_SECONDS),
            "training.interval_seconds",
        ),
    )


def _section(raw: dict, name: str) -> dict:
    value = raw.get(name, {})
    if value is None:
        return {}
    if not isinstance(value, dict):
        raise ValueError(f"{name} must be a YAML mapping")
    return value


def _positive(value, key: str) -> int:
    number = int(value)
    if number < 1:
        raise ValueError(f"{key} must be positive, got {number}")
    return number


def _non_negative_int(value, key: str) -> int:
    number = int(value)
    if number < 0:
        raise ValueError(f"{key} must not be negative, got {number}")
    return number


def _non_negative(value, key: str) -> float:
    number = float(value)
    if number < 0:
        raise ValueError(f"{key} must not be negative, got {number}")
    return number


def _new_password() -> str:
    """Strong enough that the generated password is never the weak link."""
    return secrets.token_urlsafe(18)
