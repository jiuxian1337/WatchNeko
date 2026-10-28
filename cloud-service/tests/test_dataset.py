"""Datasets: content-addressed, immutable, and only ever labelled."""

from __future__ import annotations

import pytest

from app.dataset import build_dataset
from app.store import SampleStore

FEATURES = [0.1] * 22


def seed(store: SampleStore, session_id: str, label: str, ids: list[str], player="Steve") -> None:
    store.record(
        "t1",
        "s1",
        session_id,
        label,
        [{"sample_id": i, "player_name": player, "features": FEATURES} for i in ids],
    )


@pytest.fixture
def store() -> SampleStore:
    return SampleStore()


def test_a_dataset_counts_both_classes(store: SampleStore) -> None:
    seed(store, "rs-cheat", "cheat", ["c1", "c2"])
    seed(store, "rs-legit", "legit", ["l1"])

    dataset = build_dataset(store, "t1")

    assert dataset.cheat_count == 2
    assert dataset.legit_count == 1
    assert dataset.is_trainable()


def test_the_same_samples_produce_the_same_dataset_id(store: SampleStore) -> None:
    seed(store, "rs-cheat", "cheat", ["c1"])
    seed(store, "rs-legit", "legit", ["l1"])

    first = build_dataset(store, "t1")
    second = build_dataset(store, "t1")

    assert first.dataset_id == second.dataset_id
    assert first.dataset_id.startswith("ds-")


def test_adding_a_sample_changes_the_dataset_id(store: SampleStore) -> None:
    seed(store, "rs-cheat", "cheat", ["c1"])
    seed(store, "rs-legit", "legit", ["l1"])
    before = build_dataset(store, "t1")

    seed(store, "rs-cheat", "cheat", ["c2"])
    after = build_dataset(store, "t1")

    assert before.dataset_id != after.dataset_id


def test_a_single_class_dataset_is_not_trainable(store: SampleStore) -> None:
    seed(store, "rs-cheat", "cheat", ["c1", "c2"])

    dataset = build_dataset(store, "t1")

    assert dataset.cheat_count == 2
    assert dataset.legit_count == 0
    assert not dataset.is_trainable()


def test_an_empty_store_yields_an_empty_dataset(store: SampleStore) -> None:
    dataset = build_dataset(store, "t1")
    assert dataset.samples == ()
    assert not dataset.is_trainable()


def test_another_tenants_samples_never_enter_this_dataset(store: SampleStore) -> None:
    seed(store, "rs-cheat", "cheat", ["c1"])
    store.record(
        "t2",
        "s2",
        "rs-cheat",
        "cheat",
        [{"sample_id": "c1", "player_name": "Alex", "features": FEATURES}],
    )

    assert len(build_dataset(store, "t1").samples) == 1


def test_samples_keep_the_label_of_the_session_they_came_from(store: SampleStore) -> None:
    seed(store, "rs-cheat", "cheat", ["c1"])
    seed(store, "rs-legit", "legit", ["l1"])

    labels = {sample.sample_id: sample.label for sample in build_dataset(store, "t1").samples}

    assert labels == {"c1": "cheat", "l1": "legit"}
