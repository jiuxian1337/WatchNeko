"""Datasets: an immutable snapshot of labelled samples, identified by its own contents.

A dataset version is derived from what is *in* it, not from when it was made. Two consequences follow, and
both are the point: rebuilding from unchanged samples yields the same id, so "did the data change?" is a
string comparison; and a model can name the exact data that produced it without anyone maintaining a
separate registry of what happened when.

Only labelled data can enter a dataset. A sample whose session label is unknown is not "probably fine" — it
is excluded, because a dataset that quietly mixes in unlabelled rows trains a model nobody can reason about.
"""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass

from app.store import SampleStore

CHEAT = "cheat"
LEGIT = "legit"


@dataclass(frozen=True)
class LabelledSample:
    sample_id: str
    player_name: str
    features: tuple[float, ...]
    label: str


@dataclass(frozen=True)
class Dataset:
    """One immutable snapshot, addressable by ``dataset_id``."""

    dataset_id: str
    samples: tuple[LabelledSample, ...]

    @property
    def cheat_count(self) -> int:
        return sum(1 for sample in self.samples if sample.label == CHEAT)

    @property
    def legit_count(self) -> int:
        return sum(1 for sample in self.samples if sample.label == LEGIT)

    def is_trainable(self) -> bool:
        """Both classes must be present: one-class data cannot teach a boundary."""
        return self.cheat_count > 0 and self.legit_count > 0


def _dataset_id(samples: tuple[LabelledSample, ...]) -> str:
    """A digest of the dataset's contents, independent of insertion order."""
    digest = hashlib.sha256()
    for sample in sorted(samples, key=lambda s: s.sample_id):
        digest.update(sample.sample_id.encode("utf-8"))
        digest.update(b"\x00")
        digest.update(sample.label.encode("utf-8"))
        digest.update(b"\x00")
        digest.update(json.dumps(list(sample.features)).encode("utf-8"))
        digest.update(b"\x00")
    return "ds-" + digest.hexdigest()[:16]


def make_dataset(samples: list[LabelledSample]) -> Dataset:
    """Build a dataset from samples already in hand, with the same content-derived id."""
    ordered = tuple(sorted(samples, key=lambda s: s.sample_id))
    return Dataset(dataset_id=_dataset_id(ordered), samples=ordered)


def build_dataset(store: SampleStore, tenant_id: str) -> Dataset:
    """Snapshot every labelled sample this tenant owns.

    A recording session whose label is missing or unrecognised contributes nothing: guessing a label is
    worse than having one fewer sample.
    """
    samples: list[LabelledSample] = []

    for session_id in store.session_ids(tenant_id):
        label = store.session_label(tenant_id, session_id)
        if label not in (CHEAT, LEGIT):
            continue

        for sample_id, player_name, features in store.samples_of_session(tenant_id, session_id):
            samples.append(
                LabelledSample(
                    sample_id=sample_id,
                    player_name=player_name,
                    features=tuple(features),
                    label=label,
                )
            )

    ordered = tuple(sorted(samples, key=lambda s: s.sample_id))
    return Dataset(dataset_id=_dataset_id(ordered), samples=ordered)
