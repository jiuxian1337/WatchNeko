"""Storage: what a batch leaves behind, and how it is removed."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from app.store import SampleStore


def sample(sample_id: str, player_name: str = "Steve") -> dict:
    return {"sample_id": sample_id, "player_name": player_name, "features": [0.0] * 22}


@pytest.fixture
def store() -> SampleStore:
    return SampleStore()


def test_a_recorded_batch_is_kept_with_its_label(store: SampleStore) -> None:
    accepted, rejected = store.record("t1", "s1", "rs-1", "cheat", [sample("a"), sample("b")])

    assert (accepted, rejected) == (2, 0)
    assert store.sample_count("t1") == 2
    assert store.session_label("t1", "rs-1") == "cheat"


def test_the_label_is_taken_from_the_upload_not_from_the_sample(store: SampleStore) -> None:
    store.record("t1", "s1", "rs-cheat", "cheat", [sample("a")])
    store.record("t1", "s1", "rs-legit", "legit", [sample("b")])

    assert store.session_label("t1", "rs-cheat") == "cheat"
    assert store.session_label("t1", "rs-legit") == "legit"


def test_a_replayed_sample_is_rejected_rather_than_duplicated(store: SampleStore) -> None:
    store.record("t1", "s1", "rs-1", "cheat", [sample("a")])
    accepted, rejected = store.record("t1", "s1", "rs-1", "cheat", [sample("a")])

    assert (accepted, rejected) == (0, 1)
    assert store.sample_count("t1") == 1, "the dataset must not be inflated by a replay"


def test_samples_are_isolated_per_tenant(store: SampleStore) -> None:
    store.record("t1", "s1", "rs-1", "cheat", [sample("a")])
    store.record("t2", "s2", "rs-1", "legit", [sample("a")])

    assert store.sample_count("t1") == 1
    assert store.sample_count("t2") == 1
    assert store.sample_count() == 2


def test_a_players_samples_can_be_deleted_by_name(store: SampleStore) -> None:
    store.record("t1", "s1", "rs-1", "cheat", [sample("a", "Steve"), sample("b", "Steve")])
    store.record("t1", "s1", "rs-1", "cheat", [sample("c", "Alex")])

    removed = store.delete_player("t1", "Steve")

    assert removed == 2
    assert store.sample_count("t1") == 1
    assert store.player_names("t1") == ["Alex"]


def test_deleting_one_tenants_player_leaves_another_tenants_data_alone(
    store: SampleStore,
) -> None:
    store.record("t1", "s1", "rs-1", "cheat", [sample("a", "Steve")])
    store.record("t2", "s2", "rs-1", "cheat", [sample("a", "Steve")])

    store.delete_player("t1", "Steve")

    assert store.sample_count("t1") == 0
    assert store.sample_count("t2") == 1


def test_samples_older_than_the_retention_window_are_purged(store: SampleStore) -> None:
    old = datetime.now(timezone.utc) - timedelta(days=store.retention_days + 1)
    store.record("t1", "s1", "rs-old", "cheat", [sample("old")], now=old)
    store.record("t1", "s1", "rs-new", "cheat", [sample("new")])

    purged = store.purge_expired()

    assert purged == 1
    assert store.sample_count("t1") == 1


def test_samples_inside_the_retention_window_survive(store: SampleStore) -> None:
    recent = datetime.now(timezone.utc) - timedelta(days=store.retention_days - 1)
    store.record("t1", "s1", "rs-recent", "cheat", [sample("recent")], now=recent)

    assert store.purge_expired() == 0
    assert store.sample_count("t1") == 1


def test_the_retention_window_is_configurable() -> None:
    short = SampleStore(retention_days=7)
    old = datetime.now(timezone.utc) - timedelta(days=8)
    short.record("t1", "s1", "rs-1", "cheat", [sample("a")], now=old)

    assert short.purge_expired() == 1


def test_a_non_positive_retention_window_is_refused() -> None:
    with pytest.raises(ValueError):
        SampleStore(retention_days=0)
