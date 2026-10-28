"""The scheduled cycle: purge, judge, retrain — and the order it does them in."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from app.metrics import Metrics
from app.ops import OpsCycle
from app.publish import HealthPolicy, ModelPublisher
from app.store import SampleStore
from app.train import LogisticTrainer

TENANT = "t1"


def features(seed: float) -> list[float]:
    return [round(seed + i * 0.001, 6) for i in range(22)]


def seed(store: SampleStore, cheat: int = 5, legit: int = 5, when: datetime | None = None) -> None:
    store.record(
        TENANT, "s1", "rs-cheat", "cheat",
        [{"sample_id": f"c{i}", "player_name": "h", "features": features(0.8)} for i in range(cheat)],
        now=when,
    )
    store.record(
        TENANT, "s1", "rs-legit", "legit",
        [{"sample_id": f"l{i}", "player_name": "h", "features": features(0.2)} for i in range(legit)],
        now=when,
    )


def cycle(store: SampleStore, publisher: ModelPublisher, metrics: Metrics | None = None) -> OpsCycle:
    return OpsCycle(
        store=store,
        publisher=publisher,
        trainer=LogisticTrainer(),
        metrics=metrics if metrics is not None else Metrics(),
        policy=HealthPolicy(min_verdicts_before_judging=20),
    )


def test_a_cycle_publishes_a_model_from_a_trainable_dataset() -> None:
    store = SampleStore()
    seed(store)
    publisher = ModelPublisher()

    outcome = cycle(store, publisher).run_once([TENANT])

    assert outcome.published is not None
    assert publisher.current().model_id == outcome.published.model_id


def test_publishing_updates_the_exported_model_version() -> None:
    store = SampleStore()
    seed(store)
    metrics = Metrics()

    outcome = cycle(store, ModelPublisher(), metrics).run_once([TENANT])

    assert metrics.model_id == outcome.published.model_id


def test_a_second_cycle_with_unchanged_data_does_not_republish() -> None:
    store = SampleStore()
    seed(store)
    publisher = ModelPublisher()
    ops = cycle(store, publisher)

    ops.run_once([TENANT])
    second = ops.run_once([TENANT])

    assert second.published is None
    assert "unchanged" in second.skipped


def test_new_samples_cause_a_republish() -> None:
    store = SampleStore()
    seed(store)
    publisher = ModelPublisher()
    ops = cycle(store, publisher)

    ops.run_once([TENANT])
    first = publisher.current().model_id

    store.record(
        TENANT, "s1", "rs-cheat", "cheat",
        [{"sample_id": "c-extra", "player_name": "h", "features": features(0.9)}],
    )
    outcome = ops.run_once([TENANT])

    assert outcome.published is not None
    assert publisher.current().model_id != first


def test_a_dataset_with_one_class_is_not_published() -> None:
    store = SampleStore()
    seed(store, cheat=5, legit=0)

    outcome = cycle(store, ModelPublisher()).run_once([TENANT])

    assert outcome.published is None
    assert outcome.skipped is not None


def test_a_cycle_purges_expired_samples_before_training() -> None:
    store = SampleStore()
    old = datetime.now(timezone.utc) - timedelta(days=store.retention_days + 1)
    store.record(
        TENANT, "s1", "rs-old", "cheat",
        [{"sample_id": "old", "player_name": "h", "features": features(0.8)}],
        now=old,
    )
    seed(store)

    outcome = cycle(store, ModelPublisher()).run_once([TENANT])

    assert outcome.purged == 1


def test_a_misbehaving_model_is_rolled_back_and_nothing_new_is_published() -> None:
    store = SampleStore()
    seed(store)
    publisher = ModelPublisher()
    metrics = Metrics()
    ops = cycle(store, publisher, metrics)

    ops.run_once([TENANT])
    first = publisher.current().model_id

    store.record(
        TENANT, "s1", "rs-cheat", "cheat",
        [{"sample_id": "c-extra", "player_name": "h", "features": features(0.9)}],
    )
    ops.run_once([TENANT])
    second = publisher.current().model_id
    assert second != first

    # The new model starts calling nearly everything cheat.
    metrics.record_verdicts(["cheat_like"] * 30)
    outcome = ops.run_once([TENANT])

    assert outcome.rolled_back == second
    assert outcome.published is None, "a cycle that rolled back must not then publish over it"
    assert publisher.current().model_id == first


def test_a_rollback_is_recorded_in_the_metrics() -> None:
    store = SampleStore()
    seed(store)
    publisher = ModelPublisher()
    metrics = Metrics()
    ops = cycle(store, publisher, metrics)
    ops.run_once([TENANT])
    store.record(
        TENANT, "s1", "rs-cheat", "cheat",
        [{"sample_id": "c-extra", "player_name": "h", "features": features(0.9)}],
    )
    ops.run_once([TENANT])

    metrics.record_verdicts(["cheat_like"] * 30)
    outcome = ops.run_once([TENANT])

    assert metrics.rollbacks == 1
    assert outcome.rollback_reason is not None
    assert "cheat-like ratio" in outcome.rollback_reason


def test_a_rollback_with_nothing_to_restore_keeps_the_current_model() -> None:
    store = SampleStore()
    seed(store)
    publisher = ModelPublisher()
    metrics = Metrics()
    ops = cycle(store, publisher, metrics)
    ops.run_once([TENANT])
    only = publisher.current().model_id

    metrics.record_verdicts(["cheat_like"] * 30)
    outcome = ops.run_once([TENANT])

    assert outcome.rolled_back is None
    assert "no earlier model is retained" in outcome.skipped
    assert publisher.current().model_id == only
