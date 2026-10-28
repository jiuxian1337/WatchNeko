"""Storage for labelled samples, and the retention rules that govern them.

Two tables: a recording session is the batch an admin uploaded in one go, and a sample belongs to exactly
one session. Keeping the session as a row is what makes an upload traceable — "which recording did this
sample come from" is a question that gets asked the moment a model starts behaving oddly.

Samples are stored per tenant. The player is identified by name in plain text, which is what lets an
operator find an upload by name and undo it. Names are not unique across servers, so the same person on
two tenants is one name here — that is intended.
"""

from __future__ import annotations

import json
import sqlite3
from datetime import datetime, timedelta, timezone

DEFAULT_RETENTION_DAYS = 90


def _join_names(names: list[str]) -> str:
    """The player names of a batch as one column value.

    JSON rather than a delimiter: a name can contain anything, and a delimiter that a name can contain
    turns one player into two. Empty is stored as an empty string so old rows (which have none) and a
    batch with no names read back the same way.
    """
    return json.dumps(sorted(set(names))) if names else ""


def _split_names(value: str | None) -> list[str]:
    if not value:
        return []
    try:
        decoded = json.loads(value)
    except ValueError:
        # A row written by something other than _join_names is not worth crashing a console page over.
        return []
    return [str(name) for name in decoded] if isinstance(decoded, list) else []


_TABLES = """
CREATE TABLE IF NOT EXISTS recording_sessions (
    tenant_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    server_id TEXT NOT NULL,
    label TEXT NOT NULL,
    created_at TEXT NOT NULL,
    accepted INTEGER NOT NULL,
    rejected INTEGER NOT NULL,
    undone_at TEXT,
    players TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (tenant_id, session_id)
);

CREATE TABLE IF NOT EXISTS samples (
    tenant_id TEXT NOT NULL,
    sample_id TEXT NOT NULL,
    session_id TEXT NOT NULL,
    player_name TEXT NOT NULL,
    features TEXT NOT NULL,
    recorded_at TEXT NOT NULL,
    PRIMARY KEY (tenant_id, sample_id)
);
"""

#: Indexes are created *after* the migrations, never before.
#:
#: ``IF NOT EXISTS`` tests the index name, not its columns, so creating ``samples_by_player`` over
#: ``player_name`` on a table that still has ``player_hash`` fails to resolve the column and aborts
#: startup — on exactly the databases the migration exists for. Splitting the two is what keeps that
#: ordering true by construction rather than by a comment someone can edit away.
_INDEXES = """
CREATE INDEX IF NOT EXISTS samples_by_player ON samples (tenant_id, player_name);
CREATE INDEX IF NOT EXISTS samples_by_time ON samples (recorded_at);
"""


class SampleStore:
    """Labelled samples in SQLite, scoped to a tenant."""

    def __init__(self, path: str = ":memory:", retention_days: int = DEFAULT_RETENTION_DAYS) -> None:
        if retention_days < 1:
            raise ValueError("retention_days must be positive")
        self._connection = sqlite3.connect(path, check_same_thread=False)
        # Order is load-bearing: tables, then migrations, then indexes. An index over a column a
        # migration is about to create would abort the whole startup on an old database.
        self._connection.executescript(_TABLES)
        self._migrate()
        self._connection.executescript(_INDEXES)
        self._retention_days = retention_days

    def _migrate(self) -> None:
        """Bring a database written by an older release up to the current schema.

        ``CREATE TABLE IF NOT EXISTS`` leaves an existing table alone, so anything added later has to be
        applied explicitly. Three migrations so far:

        * ``recording_sessions.undone_at`` — nullable, so adding it to an existing table loses nothing.
        * ``recording_sessions.players`` — the distinct player names of a batch, so the upload history can
          still say who was in it after the samples are deleted. Existing rows default to empty.
        * ``samples.player_hash`` -> ``samples.player_name`` — the identity model changed from a salted
          hash to a plain name. A hash cannot be turned back into a name, so the old sample rows are
          dropped: keeping them under a placeholder name would make them impossible to attribute or
          delete by name, and they would silently enter the next dataset. The session rows survive, so the
          upload history still shows that those uploads happened and that their samples are gone.

        Every step is conditional on the column actually being absent, so this is a no-op on a database
        that is already current — which is what makes running it on every startup safe.

        ``RENAME COLUMN`` (SQLite 3.25+, and Python 3.11 bundles 3.37) is used rather than rebuilding the
        table: it rewrites the index definitions along with the column, keeps the primary key, and cannot
        lose the table to a crash between a ``DROP`` and a ``RENAME``.
        """
        session_columns = {
            row[1] for row in self._connection.execute("PRAGMA table_info(recording_sessions)")
        }
        sample_columns = {row[1] for row in self._connection.execute("PRAGMA table_info(samples)")}

        with self._connection:
            if "undone_at" not in session_columns:
                self._connection.execute("ALTER TABLE recording_sessions ADD COLUMN undone_at TEXT")

            if "players" not in session_columns:
                self._connection.execute(
                    "ALTER TABLE recording_sessions ADD COLUMN players TEXT NOT NULL DEFAULT ''"
                )

            if "player_hash" in sample_columns and "player_name" not in sample_columns:
                # The rows are dropped before the rename, not after: an old row's hash cannot be turned
                # into a name, and a sample nobody can attribute is one nobody can delete on request.
                self._connection.execute("DELETE FROM samples")
                self._connection.execute(
                    "ALTER TABLE samples RENAME COLUMN player_hash TO player_name"
                )

            # A migration that died part-way through the earlier rebuild-based implementation can leave
            # this behind. It holds nothing worth keeping and would otherwise shadow the real table name.
            self._connection.execute("DROP TABLE IF EXISTS samples_new")

    @property
    def retention_days(self) -> int:
        return self._retention_days

    def record(
        self,
        tenant_id: str,
        server_id: str,
        session_id: str,
        label: str,
        samples: list[dict],
        now: datetime | None = None,
    ) -> tuple[int, int]:
        """Store one uploaded batch. Returns ``(accepted, rejected)``.

        A sample whose id this tenant already has is rejected rather than overwritten: a replayed upload is
        a bug in the caller, and silently accepting it would inflate the dataset with duplicates.
        """
        stamp = (now or datetime.now(timezone.utc)).isoformat()
        accepted = 0
        rejected = 0

        with self._connection:
            for sample in samples:
                cursor = self._connection.execute(
                    "INSERT OR IGNORE INTO samples"
                    " (tenant_id, sample_id, session_id, player_name, features, recorded_at)"
                    " VALUES (?, ?, ?, ?, ?, ?)",
                    (
                        tenant_id,
                        sample["sample_id"],
                        session_id,
                        sample["player_name"],
                        json.dumps(sample["features"]),
                        stamp,
                    ),
                )
                if cursor.rowcount:
                    accepted += 1
                else:
                    rejected += 1

            # The names are recorded on the session row, not read back from the samples: undoing a batch
            # deletes its samples, and the history still has to say who was in it. A re-upload of the same
            # session id is the same batch continued, so its names join the record rather than replace it.
            names = sorted({str(sample["player_name"]) for sample in samples})
            previous = self._session_names(tenant_id, session_id)
            merged = sorted(set(previous) | set(names))

            # ON CONFLICT rather than INSERT OR REPLACE: REPLACE deletes and re-inserts the row, which
            # would silently clear undone_at and make an undone upload look live again.
            #
            # `undone_at` follows what is actually in the store. Samples that were undone and then
            # re-uploaded are live data again, and the console must be able to undo them a second time;
            # an upload that only contributed duplicates changes nothing and keeps the marker.
            self._connection.execute(
                "INSERT INTO recording_sessions"
                " (tenant_id, session_id, server_id, label, created_at, accepted, rejected, players)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
                " ON CONFLICT(tenant_id, session_id) DO UPDATE SET"
                " server_id = excluded.server_id,"
                " label = excluded.label,"
                " accepted = recording_sessions.accepted + excluded.accepted,"
                " rejected = recording_sessions.rejected + excluded.rejected,"
                " players = excluded.players,"
                " undone_at = CASE WHEN excluded.accepted > 0 THEN NULL"
                " ELSE recording_sessions.undone_at END",
                (tenant_id, session_id, server_id, label, stamp, accepted, rejected, _join_names(merged)),
            )

        return accepted, rejected

    def _session_names(self, tenant_id: str, session_id: str) -> list[str]:
        row = self._connection.execute(
            "SELECT players FROM recording_sessions WHERE tenant_id = ? AND session_id = ?",
            (tenant_id, session_id),
        ).fetchone()
        return _split_names(row[0]) if row is not None else []

    def sample_count(self, tenant_id: str | None = None) -> int:
        if tenant_id is None:
            row = self._connection.execute("SELECT COUNT(*) FROM samples").fetchone()
        else:
            row = self._connection.execute(
                "SELECT COUNT(*) FROM samples WHERE tenant_id = ?", (tenant_id,)
            ).fetchone()
        return int(row[0])

    def session_label(self, tenant_id: str, session_id: str) -> str | None:
        row = self._connection.execute(
            "SELECT label FROM recording_sessions WHERE tenant_id = ? AND session_id = ?",
            (tenant_id, session_id),
        ).fetchone()
        return None if row is None else str(row[0])

    def player_names(self, tenant_id: str) -> list[str]:
        rows = self._connection.execute(
            "SELECT DISTINCT player_name FROM samples WHERE tenant_id = ? ORDER BY player_name",
            (tenant_id,),
        ).fetchall()
        return [str(row[0]) for row in rows]

    def session_ids(self, tenant_id: str) -> list[str]:
        """Every recording session this tenant has uploaded, oldest first."""
        rows = self._connection.execute(
            "SELECT session_id FROM recording_sessions WHERE tenant_id = ? ORDER BY created_at, session_id",
            (tenant_id,),
        ).fetchall()
        return [str(row[0]) for row in rows]

    def samples_of_session(
        self, tenant_id: str, session_id: str
    ) -> list[tuple[str, str, list[float]]]:
        """The samples of one session as ``(sample_id, player_name, features)``."""
        rows = self._connection.execute(
            "SELECT sample_id, player_name, features FROM samples"
            " WHERE tenant_id = ? AND session_id = ? ORDER BY sample_id",
            (tenant_id, session_id),
        ).fetchall()
        return [(str(row[0]), str(row[1]), json.loads(row[2])) for row in rows]

    def delete_player(self, tenant_id: str, player_name: str) -> int:
        """Delete every sample belonging to one player. Returns how many rows went."""
        with self._connection:
            cursor = self._connection.execute(
                "DELETE FROM samples WHERE tenant_id = ? AND player_name = ?",
                (tenant_id, player_name),
            )
        return int(cursor.rowcount)

    # -- upload history --------------------------------------------------------------------------

    def uploads(self, tenant_id: str | None = None) -> list[dict]:
        """Every recorded upload, newest first, with what it contributed.

        One row per recording session. ``remaining`` is what is still in the store, so an upload that
        has been undone stays visible (``remaining == 0``) instead of silently vanishing from history.
        ``players`` is read from the session row, so it survives the samples being deleted.
        """
        query = (
            "SELECT s.tenant_id, s.session_id, s.server_id, s.label, s.created_at,"
            " s.accepted, s.rejected, s.undone_at, s.players,"
            " (SELECT COUNT(*) FROM samples x"
            "  WHERE x.tenant_id = s.tenant_id AND x.session_id = s.session_id) AS remaining"
            " FROM recording_sessions s"
        )
        params: tuple = ()
        if tenant_id is not None:
            query += " WHERE s.tenant_id = ?"
            params = (tenant_id,)
        query += " ORDER BY s.created_at DESC, s.session_id DESC"

        return [
            {
                "tenant_id": row[0],
                "session_id": row[1],
                "server_id": row[2],
                "label": row[3],
                "created_at": row[4],
                "accepted": int(row[5]),
                "rejected": int(row[6]),
                "undone_at": row[7],
                "players": _split_names(row[8]),
                "remaining": int(row[9]),
            }
            for row in self._connection.execute(query, params).fetchall()
        ]

    def undo_upload(self, tenant_id: str, session_id: str, now: datetime | None = None) -> int:
        """Delete one upload's samples, leaving the session row as a tombstone.

        The row stays — with ``undone_at`` set — so the history still shows that the upload happened and
        that it was undone. The samples go, which is what takes them out of the next dataset. Models
        already trained on them are deliberately not touched: rolling a model back is a separate,
        explicit decision.

        The marker is written even when there was nothing left to delete: retention can empty a batch
        before anyone gets round to undoing it, and an undo that reported zero and marked nothing would
        leave a live-looking button that never does anything.
        """
        stamp = (now or datetime.now(timezone.utc)).isoformat()
        with self._connection:
            cursor = self._connection.execute(
                "DELETE FROM samples WHERE tenant_id = ? AND session_id = ?",
                (tenant_id, session_id),
            )
            self._connection.execute(
                "UPDATE recording_sessions SET undone_at = ?"
                " WHERE tenant_id = ? AND session_id = ?",
                (stamp, tenant_id, session_id),
            )
        return int(cursor.rowcount)

    def purge_expired(self, now: datetime | None = None) -> int:
        """Drop samples past the retention window. Returns how many rows went."""
        cutoff = (now or datetime.now(timezone.utc)) - timedelta(days=self._retention_days)
        with self._connection:
            cursor = self._connection.execute(
                "DELETE FROM samples WHERE recorded_at < ?", (cutoff.isoformat(),)
            )
        return int(cursor.rowcount)

    def close(self) -> None:
        self._connection.close()
