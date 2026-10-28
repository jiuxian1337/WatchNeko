"""Proves the anti-drift guard is load-bearing on the service side too.

The Java suite carries the mirror image of these tests against the same corpus. Together they are what makes
a field rename fail on both sides instead of one.
"""

from __future__ import annotations

import copy

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.schemas import Label, VerdictLabel
from contract_assertions import assert_same_json
from contract_fixtures import case_names, request, response


def test_both_sides_see_exactly_the_same_cases() -> None:
    """The Java suite asserts this same list; adding a fixture forces both sides to implement it."""
    assert case_names() == [
        "inference_batch",
        "inference_single",
        "model_metadata",
        "model_metadata_absent",
        "sample_upload",
    ]


def test_a_renamed_request_field_is_rejected() -> None:
    case = "inference_single"
    client = TestClient(create_app())
    pinned_request = request(case)

    assert (
        client.post(pinned_request["path"], json=pinned_request["body"], headers=pinned_request["headers"])
        .status_code
        == 200
    ), "the pinned request must be accepted"

    drifted = copy.deepcopy(pinned_request["body"])
    for sample in drifted["samples"]:
        sample["feature_vector"] = sample.pop("features")

    rejected = client.post(pinned_request["path"], json=drifted, headers=pinned_request["headers"])

    assert rejected.status_code == 422, "renaming features to feature_vector must be rejected"


def test_a_missing_request_field_is_rejected_rather_than_defaulted() -> None:
    case = "inference_single"
    client = TestClient(create_app())
    pinned_request = request(case)

    drifted = copy.deepcopy(pinned_request["body"])
    del drifted["samples"][0]["player_name"]

    rejected = client.post(pinned_request["path"], json=drifted, headers=pinned_request["headers"])

    assert rejected.status_code == 422, "a sample without a player name must be rejected"
    assert rejected.json() == {
        "contract_version": 1,
        "error": "invalid_request",
    }, "a rejection still has to carry the contract's error envelope"


def test_an_oversized_feature_vector_is_rejected() -> None:
    case = "inference_single"
    client = TestClient(create_app())
    pinned_request = request(case)

    drifted = copy.deepcopy(pinned_request["body"])
    drifted["samples"][0]["features"].append(0.5)

    rejected = client.post(pinned_request["path"], json=drifted, headers=pinned_request["headers"])

    assert rejected.status_code == 422, "a 23-dimensional vector must be rejected"


def test_a_renamed_response_field_is_rejected() -> None:
    case = "inference_single"
    client = TestClient(create_app())
    pinned_request = request(case)
    actual = client.post(
        pinned_request["path"], json=pinned_request["body"], headers=pinned_request["headers"]
    ).json()

    pinned_response = response(case)["body"]
    assert_same_json(pinned_response, actual)

    drifted = copy.deepcopy(pinned_response)
    for verdict in drifted["verdicts"]:
        verdict["label"] = verdict.pop("verdict")

    with pytest.raises(AssertionError):
        assert_same_json(drifted, actual)


def test_the_verdict_vocabulary_is_pinned_exactly() -> None:
    """The Java suite pins the same three spellings, so the two sides cannot drift apart."""
    assert [label.value for label in VerdictLabel] == ["cheat_like", "legit_like", "uncertain"]
    assert [label.value for label in Label] == ["cheat", "legit"]
