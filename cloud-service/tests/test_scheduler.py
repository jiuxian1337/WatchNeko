"""The periodic wrapper: it ticks, it survives its own failures, and it keeps serving in step."""

from __future__ import annotations

import time

import pytest

from app.metrics import Metrics
from app.model import ModelRegistry
from app.ops import OpsCycle
from app.publish import HealthPolicy, ModelPublisher
from app.scheduler import PeriodicOps, apply_to_registry
from app.store import SampleStore
from app.train import LogisticTrainer, train_model
from app.dataset import make_dataset, LabelledSample

TENANT = "t1"


def features(seed: float) -> list[float]:
    return [round(seed + i * 0.001, 6) for i in range(22)]


def seed(store: SampleStore) -> None:
    store.record(
        TENANT, "s1", "rs-cheat", "cheat",
        [{"sample_id": f"c{i}", "player_name": "h", "features": features(0.8)} for i in range(5)],
    )
    store.record(
        TENANT, "s1", "rs-legit", "legit",
        [{"sample_id": f"l{i}", "player_name": "h", "features": features(0.2)} for i in range(5)],
    )


def build(store: SampleStore, metrics: Metrics | None = None) -> tuple[OpsCycle, ModelRegistry]:
    registry = ModelRegistry()
    cycle = OpsCycle(
        store=store,
        publisher=ModelPublisher(),
        trainer=LogisticTrainer(),
        metrics=metrics if metrics is not None else Metrics(),
        policy=HealthPolicy(min_verdicts_before_judging=20),
        on_model_changed=apply_to_registry(registry),
    )
    return cycle, registry


def test_one_tick_publishes_and_the_registry_serves_it() -> None:
    store = SampleStore()
    seed(store)
    cycle, registry = build(store)

    cycle.run_once([TENANT])

    assert registry.current() is not None
    assert registry.current().model_id.startswith("m-")
    assert registry.current().dataset_id.startswith("ds-")


def test_the_registry_actually_scores_with_the_published_model() -> None:
    """Serving must follow publication, not just the metadata."""
    store = SampleStore()
    seed(store)
    cycle, registry = build(store)
    cycle.run_once([TENANT])

    high = registry.scorer().score(features(0.8))
    low = registry.scorer().score(features(0.2))

    assert high > low, "a trained model must separate the classes it was trained on"


def test_a_failing_cycle_is_counted_and_does_not_escape() -> None:
    class Exploding:
        metrics = Metrics()

        def run_once(self, tenants):
            raise RuntimeError("training blew up")

    metrics = Exploding.metrics
    periodic = PeriodicOps(cycle=Exploding(), tenants=[TENANT], interval_seconds=1.0, metrics=metrics)

    periodic.run_once()

    assert metrics.errors == 1


def test_the_scheduler_ticks_on_its_interval() -> None:
    store = SampleStore()
    seed(store)
    cycle, registry = build(store)
    periodic = PeriodicOps(cycle=cycle, tenants=[TENANT], interval_seconds=0.01)

    periodic.start()
    try:
        deadline = time.monotonic() + 5.0
        while registry.current() is None and time.monotonic() < deadline:
            time.sleep(0.01)
    finally:
        periodic.stop()

    assert registry.current() is not None


def test_a_non_positive_interval_is_refused() -> None:
    store = SampleStore()
    cycle, _ = build(store)
    with pytest.raises(ValueError):
        PeriodicOps(cycle=cycle, tenants=[TENANT], interval_seconds=0)


def test_an_unservable_artifact_leaves_the_previous_model_answering() -> None:
    registry = ModelRegistry()
    apply = apply_to_registry(registry)

    dataset = make_dataset(
        [LabelledSample(f"c{i}", "h", tuple(features(0.8)), "cheat") for i in range(3)]
        + [LabelledSample(f"l{i}", "h", tuple(features(0.2)), "legit") for i in range(3)]
    )
    good = train_model(dataset, LogisticTrainer())
    apply(good)
    before = registry.current().model_id

    unservable = type(good)(
        model_id="m-bad",
        dataset_id=good.dataset_id,
        trainer_name="future-v9",
        thresholds=good.thresholds,
        artifact={"kind": "future-v9", "payload": "..."},
        feature_dimensions=22,
    )
    apply(unservable)

    assert registry.current().model_id == before, "an unservable model must not take serving offline"


def test_inference_actually_serves_the_published_model() -> None:
    """The bug this guards: the app used to hold a fixed stub scorer, so publishing changed nothing."""
    from fastapi.testclient import TestClient

    from app.main import create_app

    store = SampleStore()
    seed(store)
    cycle, registry = build(store)
    cycle.run_once([TENANT])

    client = TestClient(create_app(registry=registry, store=store))
    response = client.post(
        "/v1/inference",
        json={
            "contract_version": 1,
            "samples": [
                {"sample_id": "cheaty", "player_name": "h", "features": features(0.8)},
                {"sample_id": "clean", "player_name": "h", "features": features(0.2)},
            ],
        },
        headers={"authorization": "Bearer k", "x-server-id": "s"},
    )

    assert response.status_code == 200
    scores = {v["sample_id"]: v["score"] for v in response.json()["verdicts"]}
    assert scores["cheaty"] > scores["clean"], "the published model must separate the classes it was trained on"
    assert scores["cheaty"] > 0.5


def test_a_cold_start_still_reports_no_model() -> None:
    from fastapi.testclient import TestClient

    from app.main import create_app
    from app.model import ModelRegistry

    client = TestClient(create_app(registry=ModelRegistry.cold_start(), store=SampleStore()))

    response = client.post(
        "/v1/inference",
        json={"contract_version": 1, "samples": []},
        headers={"authorization": "Bearer k", "x-server-id": "s"},
    )

    assert response.status_code == 404
    assert response.json()["error"] == "no_model"


def test_the_queue_backlog_reports_samples_not_yet_trained_on() -> None:
    store = SampleStore()
    seed(store)
    metrics = Metrics()
    cycle, _ = build(store, metrics)

    cycle.run_once([TENANT])

    assert metrics.queue_backlog == 0, "a published model has no backlog left"

    store.record(
        TENANT, "s1", "rs-cheat", "cheat",
        [{"sample_id": "c-new", "player_name": "h", "features": features(0.9)}],
    )
    cycle.run_once([TENANT])

    assert metrics.queue_backlog == 0, "the new samples were trained on in this same cycle"


def test_start_retrains_immediately_rather_than_waiting_a_full_interval() -> None:
    """Models are not persisted, so a restart starts with none.

    Waiting an interval first would leave the service answering no_model - and every plugin running with
    detection disabled - for up to an hour after each restart. The first tick must happen at startup.
    """
    store = SampleStore()
    seed(store)
    cycle, registry = build(store)
    # An interval far longer than the test: only the immediate tick can publish anything.
    periodic = PeriodicOps(cycle=cycle, tenants=[TENANT], interval_seconds=3600.0)

    periodic.start()
    try:
        deadline = time.monotonic() + 5.0
        while registry.current() is None and time.monotonic() < deadline:
            time.sleep(0.01)
    finally:
        periodic.stop()

    assert registry.current() is not None, "the service must recover its model without waiting an interval"


def test_an_empty_dataset_at_startup_does_not_prevent_a_later_retrain() -> None:
    """The immediate tick runs against whatever exists; no samples is not an error, just nothing to do."""
    store = SampleStore()
    metrics = Metrics()
    cycle, registry = build(store, metrics)
    periodic = PeriodicOps(cycle=cycle, tenants=[TENANT], interval_seconds=0.01)

    periodic.start()
    try:
        time.sleep(0.05)
        assert registry.current() is None, "nothing to train on yet"
        assert metrics.errors == 0, "an empty dataset is not a failure"

        seed(store)
        deadline = time.monotonic() + 5.0
        while registry.current() is None and time.monotonic() < deadline:
            time.sleep(0.01)
    finally:
        periodic.stop()

    assert registry.current() is not None, "samples arriving later must still be trained on"
