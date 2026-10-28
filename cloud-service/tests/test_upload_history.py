"""Upload history and undoing an upload.

Undo deletes a batch's samples and leaves the session row behind as a tombstone, so the history still
records that the upload happened. A model already trained on the data is deliberately untouched — the next
retrain simply will not see those samples.
"""

from __future__ import annotations

import sqlite3
from datetime import datetime, timezone

import pytest

from app.dataset import build_dataset
from app.store import SampleStore


def sample(sample_id: str, player_name: str = "Steve") -> dict:
    return {"sample_id": sample_id, "player_name": player_name, "features": [0.0] * 22}


@pytest.fixture
def store() -> SampleStore:
    return SampleStore()


def test_an_upload_appears_in_history_with_its_players(store: SampleStore) -> None:
    store.record("t1", "survival-1", "rs-1", "cheat", [sample("a", "Steve"), sample("b", "Alex")])

    uploads = store.uploads("t1")

    assert len(uploads) == 1
    assert uploads[0]["session_id"] == "rs-1"
    assert uploads[0]["server_id"] == "survival-1"
    assert uploads[0]["label"] == "cheat"
    assert uploads[0]["accepted"] == 2
    assert uploads[0]["remaining"] == 2
    assert store.players_of_session("t1", "rs-1") == ["Alex", "Steve"]


def test_history_is_newest_first(store: SampleStore) -> None:
    store.record("t1", "s", "rs-old", "cheat", [sample("a")], now=datetime(2026, 1, 1, tzinfo=timezone.utc))
    store.record("t1", "s", "rs-new", "legit", [sample("b")], now=datetime(2026, 2, 1, tzinfo=timezone.utc))

    assert [row["session_id"] for row in store.uploads("t1")] == ["rs-new", "rs-old"]


def test_history_is_scoped_to_a_tenant_when_asked(store: SampleStore) -> None:
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])
    store.record("t2", "s", "rs-2", "cheat", [sample("b")])

    assert [row["session_id"] for row in store.uploads("t1")] == ["rs-1"]
    assert len(store.uploads()) == 2


def test_undoing_an_upload_deletes_its_samples(store: SampleStore) -> None:
    store.record("t1", "s", "rs-1", "cheat", [sample("a"), sample("b")])
    store.record("t1", "s", "rs-2", "cheat", [sample("c")])

    removed = store.undo_upload("t1", "rs-1")

    assert removed == 2
    assert store.sample_count("t1") == 1
    assert store.session_ids("t1") == ["rs-1", "rs-2"], "the session row stays as a tombstone"


def test_an_undone_upload_stays_in_history_with_nothing_left(store: SampleStore) -> None:
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])
    store.undo_upload("t1", "rs-1")

    uploads = store.uploads("t1")

    assert len(uploads) == 1
    assert uploads[0]["remaining"] == 0
    assert uploads[0]["undone_at"] is not None


def test_an_upload_with_no_accepted_samples_is_not_marked_undone(store: SampleStore) -> None:
    """An upload that was all duplicates leaves nothing behind, but it was not undone by anyone."""
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])

    uploads = store.uploads("t1")

    assert uploads[0]["remaining"] == 1
    assert uploads[0]["undone_at"] is None


def test_a_reupload_does_not_clear_the_undone_marker(store: SampleStore) -> None:
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])
    store.undo_upload("t1", "rs-1")
    store.record("t1", "s", "rs-1", "cheat", [sample("b")])

    uploads = store.uploads("t1")

    assert uploads[0]["undone_at"] is not None, "re-uploading must not make the undo disappear"


def test_undoing_one_upload_leaves_another_tenant_alone(store: SampleStore) -> None:
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])
    store.record("t2", "s", "rs-1", "cheat", [sample("b")])

    store.undo_upload("t1", "rs-1")

    assert store.sample_count("t1") == 0
    assert store.sample_count("t2") == 1


def test_undoing_twice_is_harmless(store: SampleStore) -> None:
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])

    assert store.undo_upload("t1", "rs-1") == 1
    assert store.undo_upload("t1", "rs-1") == 0


def test_undone_samples_are_gone_from_the_next_dataset(store: SampleStore) -> None:
    store.record("t1", "s", "rs-cheat", "cheat", [sample("c1"), sample("c2")])
    store.record("t1", "s", "rs-legit", "legit", [sample("l1")])

    before = build_dataset(store, "t1")
    store.undo_upload("t1", "rs-cheat")
    after = build_dataset(store, "t1")

    assert len(before.samples) == 3
    assert len(after.samples) == 1
    assert after.dataset_id != before.dataset_id
    assert not after.is_trainable(), "only the legit class is left, so there is nothing to learn a boundary from"


# -- migrating a database written before the switch to plain names ---------------------------------


def test_an_old_database_drops_unattributable_samples_but_keeps_the_history(tmp_path) -> None:
    """A hash cannot be turned into a name, so old sample rows cannot be carried over.

    They are dropped rather than kept under a placeholder: a placeholder would be impossible to delete by
    name and would silently enter the next dataset. The session row survives, so the history still shows
    that the upload happened.
    """
    path = tmp_path / "old.db"
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        CREATE TABLE recording_sessions (
            tenant_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            server_id TEXT NOT NULL,
            label TEXT NOT NULL,
            created_at TEXT NOT NULL,
            accepted INTEGER NOT NULL,
            rejected INTEGER NOT NULL,
            PRIMARY KEY (tenant_id, session_id)
        );
        CREATE TABLE samples (
            tenant_id TEXT NOT NULL,
            sample_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            player_hash TEXT NOT NULL,
            features TEXT NOT NULL,
            recorded_at TEXT NOT NULL,
            PRIMARY KEY (tenant_id, sample_id)
        );
        INSERT INTO recording_sessions VALUES ('t1', 'rs-old', 's', 'cheat', '2026-01-01', 1, 0);
        INSERT INTO samples VALUES ('t1', 'a', 'rs-old', 'deadbeef', '[]', '2026-01-01');
        """
    )
    connection.commit()
    connection.close()

    store = SampleStore(str(path))

    assert store.sample_count("t1") == 0, "old hash-keyed samples cannot be attributed, so they go"
    assert store.session_ids("t1") == ["rs-old"], "the upload history survives"
    assert store.uploads("t1")[0]["remaining"] == 0

    # And the store still works after the rebuild.
    store.record("t1", "s", "rs-new", "cheat", [sample("b")])
    assert store.sample_count("t1") == 1
    store.close()
