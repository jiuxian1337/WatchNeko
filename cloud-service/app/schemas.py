"""The wire shapes, transcribed from ``contract/README.md``.

Every model here forbids unknown fields. That is deliberate: the service is the authority on the request
shape, so a plugin that renames a field gets a 422 instead of being silently ignored.
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field

CONTRACT_VERSION = 1
FEATURE_DIMENSIONS = 22


class VerdictLabel(str, Enum):
    """The only three conclusions the cloud may reach."""

    CHEAT_LIKE = "cheat_like"
    LEGIT_LIKE = "legit_like"
    UNCERTAIN = "uncertain"


class Label(str, Enum):
    """The ground truth of an uploaded batch, fixed when the recording session starts."""

    CHEAT = "cheat"
    LEGIT = "legit"


class Sample(BaseModel):
    """A feature vector plus the identifiers needed to route it and match it back."""

    model_config = ConfigDict(extra="forbid")

    sample_id: str
    player_name: str
    features: list[float] = Field(min_length=FEATURE_DIMENSIONS, max_length=FEATURE_DIMENSIONS)


class InferenceRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    contract_version: int
    samples: list[Sample]


class Verdict(BaseModel):
    sample_id: str
    score: float
    verdict: VerdictLabel


class InferenceResponse(BaseModel):
    contract_version: int
    model_id: str
    verdicts: list[Verdict]


class Thresholds(BaseModel):
    """Classification thresholds: score to conclusion. Published with the model they were tuned for."""

    model_config = ConfigDict(extra="forbid")

    cheat_like: float
    legit_like: float


class ModelMetadata(BaseModel):
    contract_version: int
    model_id: str
    dataset_id: str
    feature_dimensions: int
    thresholds: Thresholds


class SampleUploadRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    contract_version: int
    recording_session_id: str
    label: Label
    samples: list[Sample]


class SampleUploadResponse(BaseModel):
    contract_version: int
    recording_session_id: str
    accepted: int
    rejected: int


class ErrorResponse(BaseModel):
    contract_version: int
    error: str
