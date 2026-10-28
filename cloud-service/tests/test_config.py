"""Configuration: a YAML file generated on first run, then owned by the operator."""

from __future__ import annotations

from pathlib import Path

import pytest
import yaml
from fastapi.testclient import TestClient

from app.config import Settings, load, load_or_create
from app.serve import build_service


def test_a_missing_config_is_generated(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"

    settings, created = load_or_create(path)

    assert created
    assert path.exists()
    assert settings.admin_password != ""
    assert len(settings.admin_password) >= 16


def test_the_generated_file_is_commented_and_complete(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    load_or_create(path)

    text = path.read_text(encoding="utf-8")

    assert "# WatchNeko 云端服务配置" in text
    for section in ("server:", "admin:", "database:", "quota:", "training:"):
        assert section in text
    # Every key is explained in place; a dumped dict would have lost all of this.
    assert "# 管理会话签名密钥" in text


def test_generated_secrets_differ_between_installs(tmp_path: Path) -> None:
    first, _ = load_or_create(tmp_path / "a" / "config.yaml")
    second, _ = load_or_create(tmp_path / "b" / "config.yaml")

    assert first.admin_password != second.admin_password
    assert first.admin_secret != second.admin_secret


def test_an_existing_config_is_never_overwritten(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    load_or_create(path)

    raw = yaml.safe_load(path.read_text(encoding="utf-8"))
    raw["admin"]["password"] = "operator-chose-this"
    raw["server"]["port"] = 9999
    path.write_text(yaml.safe_dump(raw), encoding="utf-8")

    settings, created = load_or_create(path)

    assert not created
    assert settings.admin_password == "operator-chose-this"
    assert settings.port == 9999


def test_a_relative_database_path_resolves_next_to_the_config(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump({"database": {"path": "data/wn.db"}}), encoding="utf-8")

    settings = load(path)

    assert Path(settings.database_path) == (tmp_path / "data" / "wn.db").resolve()


def test_an_absolute_database_path_is_left_alone(tmp_path: Path) -> None:
    # `tmp_path` is absolute on every platform; a literal POSIX path is not absolute on Windows, so
    # using one here would test the platform rather than the resolver.
    absolute = tmp_path / "elsewhere" / "wn.db"
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump({"database": {"path": str(absolute)}}), encoding="utf-8")

    assert load(path).database_path == str(absolute)


def test_a_partial_config_falls_back_to_defaults(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text("admin:\n  password: pw\n", encoding="utf-8")

    settings = load(path)

    assert settings.port == 8000
    assert settings.retention_days == 90
    assert settings.trainer == "logistic"
    assert settings.admin_password == "pw"


def test_the_in_memory_database_name_is_not_treated_as_a_path(tmp_path: Path) -> None:
    """``:memory:`` is SQLite's name for an anonymous database, not a filename.

    Resolving it against the config directory — which is what happens to every other relative value —
    produces a path to a file literally called ``:memory:``, and the service then fails to open it.
    """
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump({"database": {"path": ":memory:"}}), encoding="utf-8")

    assert load(path).database_path == ":memory:"


def test_an_empty_file_is_valid_and_means_all_defaults(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text("", encoding="utf-8")

    settings = load(path)

    assert settings.port == 8000
    assert not settings.has_admin_password


def test_malformed_yaml_is_reported_not_swallowed(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text("server: [unclosed\n", encoding="utf-8")

    with pytest.raises(yaml.YAMLError):
        load(path)


def test_a_non_mapping_document_is_refused(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text("- just\n- a list\n", encoding="utf-8")

    with pytest.raises(ValueError, match="mapping"):
        load(path)


@pytest.mark.parametrize(
    ("body", "match"),
    [
        ({"server": {"port": 0}}, "server.port"),
        ({"database": {"retention_days": 0}}, "database.retention_days"),
        ({"quota": {"samples_per_minute": -5}}, "quota.samples_per_minute"),
        ({"training": {"interval_seconds": -1}}, "training.interval_seconds"),
        ({"server": "not a mapping"}, "server"),
    ],
)
def test_invalid_values_are_refused_with_the_key_named(
    tmp_path: Path, body: dict, match: str
) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump(body), encoding="utf-8")

    with pytest.raises(ValueError, match=match):
        load(path)


def test_a_quota_of_zero_in_the_yaml_means_unlimited(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump({"quota": {"samples_per_minute": 0}}), encoding="utf-8")

    assert load(path).quota_per_minute == 0


def test_an_interval_of_zero_turns_automatic_retraining_off(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump({"training": {"interval_seconds": 0}}), encoding="utf-8")

    settings = load(path)

    assert settings.auto_retrain is False


# -- the composition root builds from YAML settings -------------------------------------------------


def test_the_composition_root_serves_from_yaml_settings(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(
        yaml.safe_dump(
            {
                "admin": {"username": "root", "password": "pw", "secret": "s"},
                "database": {"path": "wn.db"},
            }
        ),
        encoding="utf-8",
    )
    settings = load(path)

    service = build_service(settings)
    client = TestClient(service.app, follow_redirects=False)

    assert client.get("/admin/").status_code == 303
    assert client.get("/metrics").status_code == 200
    assert (tmp_path / "wn.db").exists(), "the database lands beside the config, not in the CWD"


def test_authentication_goes_through_the_tenant_store(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump({"database": {"path": ":memory:"}}), encoding="utf-8")
    service = build_service(load(path))
    _, key = service.tenants.create("t1", servers=["s1"])
    client = TestClient(service.app)

    refused = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": "Bearer wrong", "x-server-id": "s1"},
    )
    allowed = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": f"Bearer {key}", "x-server-id": "s1"},
    )

    assert refused.status_code == 401
    # A freshly assembled service has published no model, so the authenticated answer is the cold start.
    assert allowed.status_code == 404
    assert allowed.json()["error"] == "no_model"


def test_the_trainer_can_be_selected_from_the_yaml(tmp_path: Path) -> None:
    path = tmp_path / "config.yaml"
    path.write_text(yaml.safe_dump({"training": {"trainer": "lightgbm"}}), encoding="utf-8")

    assert load(path).trainer == "lightgbm"


def test_a_service_built_for_lightgbm_can_train_and_serve(tmp_path: Path) -> None:
    from app.dataset import CHEAT, LabelledSample
    from app.train import available_trainers

    if "lightgbm" not in available_trainers():
        pytest.skip("lightgbm is not installed in this environment")

    path = tmp_path / "config.yaml"
    path.write_text(
        yaml.safe_dump(
            {"training": {"trainer": "lightgbm", "interval_seconds": 60}, "database": {"path": ":memory:"}}
        ),
        encoding="utf-8",
    )
    service = build_service(load(path))

    def features(seed: float) -> list[float]:
        return [round(seed + i * 0.001, 6) for i in range(22)]

    for label, seed in (("cheat", 0.8), ("legit", 0.2)):
        service.store.record(
            "t1", "s1", f"rs-{label}", label,
            [{"sample_id": f"{label}{i}", "player_name": "h", "features": features(seed)} for i in range(6)],
        )
    service.tenants.create("t1")

    service.ops.run_once()

    assert service.registry.current() is not None
    assert service.registry.scorer().score(features(0.8)) > service.registry.scorer().score(features(0.2))
