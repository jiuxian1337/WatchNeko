package cc.watchneko.utils.common;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * The wire format for uploading a debug log and reading back its public URL.
 *
 * <p>Grim's old paste host ({@code paste.grim.ac}) now answers 403 to every programmatic upload, which broke
 * {@code /watchneko log} outright. The default moved to mclo.gs, whose API is a single form-encoded POST and a
 * JSON reply. {@code -Dwatchneko.paste-url=...} still overrides the endpoint for anyone self-hosting a
 * compatible service.
 *
 * <p>The form body must be measured in bytes, not characters: a Chinese log is several times longer as UTF-8
 * than as Java chars, and streaming it with a character-counting Content-Length fails the upload. That is
 * exactly the kind of bug that only shows up for the users this plugin is most likely to have.
 */
public final class PasteUpload {

    /** mclo.gs's upload endpoint; override with {@code -Dwatchneko.paste-url=}. */
    public static final String DEFAULT_ENDPOINT = "https://api.mclo.gs/1/log";

    private static final String ENDPOINT_PROPERTY = "watchneko.paste-url";

    private PasteUpload() {
    }

    public static String endpoint() {
        return System.getProperty(ENDPOINT_PROPERTY, DEFAULT_ENDPOINT);
    }

    /** The form-encoded request body, as bytes so its length matches what is written to the socket. */
    public static byte[] formBody(final String log) {
        return ("content=" + URLEncoder.encode(log, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Extracts the public URL from a paste-service reply.
     *
     * <p>Anything that is not a 2xx with {@code {"success": true, "url": "..."}} yields empty: a paste service
     * that half-succeeds must not produce a success message pointing at nothing.
     */
    public static Optional<String> parseReply(final int status, final String body) {
        if (status < 200 || status >= 300 || body == null) {
            return Optional.empty();
        }
        try {
            final JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (!json.has("success") || json.get("success").isJsonNull() || !json.get("success").getAsBoolean()) {
                return Optional.empty();
            }
            if (!json.has("url") || json.get("url").isJsonNull()) {
                return Optional.empty();
            }
            final String url = json.get("url").getAsString();
            return url.isBlank() ? Optional.empty() : Optional.of(url);
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }
}
