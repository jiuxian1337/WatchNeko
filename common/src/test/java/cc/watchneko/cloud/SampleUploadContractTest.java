package cc.watchneko.cloud;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The sample upload endpoint: one recording session, one label, one batch, one receipt.
 */
class SampleUploadContractTest {

    @Test
    void uploadSendsTheLabelledBatchAndReadsBackTheReceipt() {
        FixtureTransport transport = FixtureTransport.replaying("sample_upload");
        CloudClient client = new CloudClient(transport, TestSamples.settings());

        SampleUploadResult result = client.uploadSamples(
                "rs-0001",
                Label.CHEAT,
                List.of(
                        TestSamples.movedSample("s-1001"),
                        TestSamples.sample("s-1002", TestSamples.NAME_A, TestSamples.FEATURES_STILL)));

        CloudRequest sent = transport.lastRequest();
        assertEquals("POST", sent.method());
        assertEquals("/v1/samples", sent.path());
        ContractAssertions.assertSameHeaders(
                ContractFixtures.request("sample_upload").getAsJsonObject("headers"),
                sent.headers());
        ContractAssertions.assertSameJson(
                ContractFixtures.request("sample_upload").getAsJsonObject("body"),
                CloudJson.parse(sent.body()));

        ContractAssertions.assertSameJson(
                ContractFixtures.response("sample_upload").getAsJsonObject("body"),
                CloudJson.parse(CloudJson.write(result)));

        assertEquals("rs-0001", result.recordingSessionId());
        assertEquals(2, result.accepted());
        assertEquals(0, result.rejected());
    }

    @Test
    void theLabelIsTheOnlyThingThatTellsTheCloudWhatTheseSamplesAre() {
        FixtureTransport transport = FixtureTransport.replaying("sample_upload");
        CloudClient client = new CloudClient(transport, TestSamples.settings());

        client.uploadSamples("rs-0001", Label.LEGIT, List.of(TestSamples.movedSample("s-1001")));

        assertEquals("legit",
                CloudJson.parse(transport.lastRequest().body()).get("label").getAsString());
    }
}
