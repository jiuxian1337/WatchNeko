"""Upload history and undoing an upload.

Undo deletes a batch's samples and leaves the session row behind as a tombstone, so the history still
records that the upload happened. A model already trained on the data is deliberately untouched — the next
retrain simply will not see those samples.
"""

from __future__ import annotations

import sqlite3
from datetime import datetime, timedelta, timezone

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
    assert uploads[0]["players"] == ["Alex", "Steve"]


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


def test_a_reupload_of_new_samples_makes_an_undone_upload_live_again(store: SampleStore) -> None:
    """The marker tracks what is in the store, not that an undo once happened.

    Undoing deletes the samples. Uploading the same session id again puts *new* samples back in the store,
    and those are in the next dataset — so the batch has to be undoable again. Leaving the marker set
    would hide the button and leave data nobody can remove from the console.
    """
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])
    store.undo_upload("t1", "rs-1")
    store.record("t1", "s", "rs-1", "cheat", [sample("b")])

    uploads = store.uploads("t1")

    assert uploads[0]["remaining"] == 1
    assert uploads[0]["undone_at"] is None, "the re-uploaded samples are live data and must be undoable"


def test_a_reupload_of_only_duplicates_leaves_the_undo_marker_alone(store: SampleStore) -> None:
    """Nothing came back, so nothing changed: the undo still describes the store.

    The duplicate has to come from *another* session — undoing a batch deletes its own samples, so
    re-sending those always stores them again.
    """
    store.record("t1", "s", "rs-1", "cheat", [sample("a")])
    store.record("t1", "s", "rs-2", "cheat", [sample("b")])
    store.undo_upload("t1", "rs-1")
    store.record("t1", "s", "rs-1", "cheat", [sample("b")])

    uploads = {row["session_id"]: row for row in store.uploads("t1")}

    assert uploads["rs-1"]["remaining"] == 0
    assert uploads["rs-1"]["undone_at"] is not None


def test_undoing_an_upload_whose_samples_are_already_gone_still_marks_it(store: SampleStore) -> None:
    """Retention can empty a batch before anyone gets round to undoing it.

    The undo is then a no-op on the samples, but the history still has to record the operator's decision —
    otherwise the button stays live forever and every click reports zero.
    """
    old = datetime.now(timezone.utc) - timedelta(days=store.retention_days + 1)
    store.record("t1", "s", "rs-old", "cheat", [sample("a")], now=old)
    store.purge_expired()

    assert store.undo_upload("t1", "rs-old") == 0

    row = store.uploads("t1")[0]
    assert row["remaining"] == 0
    assert row["undone_at"] is not None, "an undo of an already-empty batch is still an undo"


def test_the_history_remembers_who_was_in_a_batch_after_it_is_undone(store: SampleStore) -> None:
    """The names are read from the session row, not from the samples.

    Undoing a batch is usually *because* of a player, so the record of who was in it has to outlive the
    samples it described.
    """
    store.record("t1", "s", "rs-1", "cheat", [sample("a", "Steve"), sample("b", "Alex")])

    store.undo_upload("t1", "rs-1")

    assert store.uploads("t1")[0]["players"] == ["Alex", "Steve"]


def test_a_reupload_adds_its_players_to_the_batch_record(store: SampleStore) -> None:
    store.record("t1", "s", "rs-1", "cheat", [sample("a", "Steve")])
    store.record("t1", "s", "rs-1", "cheat", [sample("b", "Alex")])

    assert store.uploads("t1")[0]["players"] == ["Alex", "Steve"]


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


#: The schema a released version shipped, byte for byte, including its indexes. A migration test that
#: invents a simpler old table tests a database no operator ever had.
_OLD_SCHEMA = """
CREATE TABLE IF NOT EXISTS recording_sessions (
    tenant_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    server_id TEXT NOT NULL,
    label TEXT NOT NULL,
    created_at TEXT NOT NULL,
    accepted INTEGER NOT NULL,
    rejected INTEGER NOT NULL,
    PRIMARY KEY (tenant_id, session_id)
);
CREATE TABLE IF NOT EXISTS samples (
    tenant_id TEXT NOT NULL,
    sample_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    player_hash TEXT NOT NULL,
    features TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    PRIMARY KEY (tenant_id, sample_id)
);
CREATE INDEX IF NOT EXISTS samples_by_player ON samples (tenant_id, player_hash);
CREATE INDEX IF NOT EXISTS samples_by_time ON samples (recorded_at);
"""

_OLD_ROWS = """
INSERT INTO recording_sessions VALUES ('t1', 'rs-old', 's', 'cheat', '2026-01-01', 1, 0);
INSERT INTO samples VALUES ('t1', 'a', 'rs-old', 'deadbeef', '[]', '2026-01-01');
"""


def _write_old_database(path, schema: str = _OLD_SCHEMA) -> None:
    connection = sqlite3.connect(path)
    connection.executescript(schema + _OLD_ROWS)
    connection.commit()
    connection.close()


def test_an_old_database_drops_unattributable_samples_but_keeps_the_history(tmp_path) -> None:
    """A hash cannot be turned into a name, so old sample rows cannot be carried over.

    They are dropped rather than kept under a placeholder: a placeholder would be impossible to delete by
    name and would silently enter the next dataset. The session row survives, so the history still shows
    that the upload happened.
    """
    path = tmp_path / "old.db"
    _write_old_database(path)

    store = SampleStore(str(path))

    assert store.sample_count("t1") == 0, "old hash-keyed samples cannot be attributed, so they go"
    assert store.session_ids("t1") == ["rs-old"], "the upload history survives"
    assert store.uploads("t1")[0]["remaining"] == 0

    # And the store still works after the rebuild.
    store.record("t1", "s", "rs-new", "cheat", [sample("b")])
    assert store.sample_count("t1") == 1
    store.close()


def test_an_old_database_without_the_old_index_still_opens(tmp_path) -> None:
    """The index name is what ``IF NOT EXISTS`` tests, so an unindexed old table used to crash startup.

    ``_SCHEMA`` creates ``samples_by_player`` over ``player_name``. On a table that still has
    ``player_hash`` and no index of that name, SQLite resolves the column and fails — before the migration
    that would have renamed it ever runs. A database like that is not hypothetical: an older release, or a
    table rebuilt by hand, can be missing the index.
    """
    path = tmp_path / "unindexed.db"
    _write_old_database(
        path,
        schema=_OLD_SCHEMA.replace(
            "CREATE INDEX IF NOT EXISTS samples_by_player ON samples (tenant_id, player_hash);", ""
        ),
    )

    store = SampleStore(str(path))

    assert store.sample_count("t1") == 0
    assert store.session_ids("t1") == ["rs-old"]
    store.record("t1", "s", "rs-new", "cheat", [sample("b")])
    assert store.player_names("t1") == ["Steve"]
    store.close()


def test_opening_an_already_migrated_database_changes_nothing(tmp_path) -> None:
    """Migrations run on every startup, so a second open has to be a no-op."""
    path = tmp_path / "current.db"
    first = SampleStore(str(path))
    first.record("t1", "s", "rs-1", "cheat", [sample("a")])
    first.close()

    second = SampleStore(str(path))

    assert second.sample_count("t1") == 1
    assert second.player_names("t1") == ["Steve"]
    second.close()


def test_a_database_left_half_migrated_by_the_old_rebuild_is_repaired(tmp_path) -> None:
    """The first version of this migration rebuilt the table and could die mid-way.

    A crash after ``CREATE TABLE samples_new`` leaves both tables behind. Opening such a database has to
    finish the job and sweep up the leftover, not treat ``samples_new`` as the schema of record.
    """
    path = tmp_path / "half.db"
    _write_old_database(path)
    connection = sqlite3.connect(path)
    connection.executescript(
        """
        CREATE TABLE samples_new (
            tenant_id TEXT NOT NULL,
            sample_id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            player_name TEXT NOT NULL,
            features TEXT NOT NULL,
            recorded_at TEXT NOT NULL,
            PRIMARY KEY (tenant_id, sample_id)
        );
        """
    )
    connection.commit()
    connection.close()

    store = SampleStore(str(path))

    store.record("t1", "s", "rs-new", "cheat", [sample("b")])
    assert store.player_names("t1") == ["Steve"]
    assert store.uploads("t1")[0]["remaining"] == 1

    tables = {
        row[0]
        for row in sqlite3.connect(path).execute("SELECT name FROM sqlite_master WHERE type = 'table'")
    }
    assert "samples_new" not in tables, "the leftover from the abandoned migration must be dropped"
    store.close()


