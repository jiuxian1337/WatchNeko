package cc.watchneko.cloud;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The plugin's side of the wire contract: builds the request, hands it to a {@link CloudTransport}, and
 * reads the answer back into plain records.
 *
 * <p>This class knows the contract's shapes and nothing else. It holds no policy: no retries, no
 * fail-open, no batching, no thresholds, no model state. Those are the caller's (tickets #11–#16), which
 * is what keeps this class stable while the behaviour around it grows.
 */
public final class CloudClient {

    private final CloudTransport transport;
    private final CloudSettings settings;

    public CloudClient(CloudTransport transport, CloudSettings settings) {
        this.transport = transport;
        this.settings = settings;
    }

    /**
     * Fetches the metadata of the model currently in effect.
     *
     * <p>An empty answer means the cloud has no published model yet: the cold start, not a failure. The
     * plugin treats it as "there is nothing to enable" rather than as an error to report, which is why it
     * is modelled as absence rather than as an exception.
     */
    public Optional<ModelMetadata> fetchModelMetadata() {
        CloudResponse response = transport.exchange(
                new CloudRequest("GET", CloudContract.MODEL_PATH, headers(false), null));
        if (response.status() == 404) {
            return Optional.empty();
        }
        requireOk(response, "model metadata");
        return Optional.of(CloudJson.read(response.body(), ModelMetadata.class));
    }

    /**
     * Asks the cloud for a verdict on each sample.
     *
     * <p>Samples are sent as one batch; the answer carries one verdict per sample.
     */
    public InferenceResult infer(List<FeatureSample> samples) {
        JsonObject body = new JsonObject();
        body.addProperty("contract_version", CloudContract.CONTRACT_VERSION);
        body.add("samples", samplesToJson(samples));

        CloudResponse response = transport.exchange(
                new CloudRequest("POST", CloudContract.INFERENCE_PATH, headers(true), CloudJson.write(body)));
        requireOk(response, "inference");
        return CloudJson.read(response.body(), InferenceResult.class);
    }

    /**
     * Uploads one recording session's batch of labelled samples.
     *
     * <p>The label covers the whole batch: a session is recorded as either cheat or legit from the start,
     * so there is nothing to sort afterwards.
     */
    public SampleUploadResult uploadSamples(
            String recordingSessionId, Label label, List<FeatureSample> samples) {
        JsonObject body = new JsonObject();
        body.addProperty("contract_version", CloudContract.CONTRACT_VERSION);
        body.addProperty("recording_session_id", recordingSessionId);
        body.add("label", CloudJson.toTree(label));
        body.add("samples", samplesToJson(samples));

        CloudResponse response = transport.exchange(
                new CloudRequest("POST", CloudContract.SAMPLES_PATH, headers(true), CloudJson.write(body)));
        requireOk(response, "sample upload");
        return CloudJson.read(response.body(), SampleUploadResult.class);
    }

    private Map<String, String> headers(boolean carriesJsonBody) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(CloudContract.HEADER_AUTHORIZATION,
                CloudContract.AUTHORIZATION_SCHEME + " " + settings.apiKey());
        headers.put(CloudContract.HEADER_SERVER_ID, settings.serverId());
        if (carriesJsonBody) {
            headers.put(CloudContract.HEADER_CONTENT_TYPE, CloudContract.CONTENT_TYPE_JSON);
        }
        return headers;
    }

    private static JsonArray samplesToJson(List<FeatureSample> samples) {
        JsonArray array = new JsonArray();
        for (FeatureSample sample : samples) {
            array.add(sampleToJson(sample));
        }
        return array;
    }

    private static JsonObject sampleToJson(FeatureSample sample) {
        JsonObject json = new JsonObject();
        json.addProperty("sample_id", sample.sampleId());
        json.addProperty("player_name", sample.playerName());

        JsonArray features = new JsonArray();
        for (double value : sample.features()) {
            features.add(value);
        }
        json.add("features", features);
        return json;
    }

    private static void requireOk(CloudResponse response, String operation) {
        if (response.status() != 200) {
            throw new CloudContractException(
                    response.status(), operation + " answered " + response.status() + ": " + response.body());
        }
    }
}
