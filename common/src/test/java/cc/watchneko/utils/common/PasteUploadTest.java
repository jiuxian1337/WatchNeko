package cc.watchneko.utils.common;

import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upload body and the reply parser are the two places a working path can silently become a broken one:
 * a character-counted body fails for every Chinese log, and a half-parse could claim success with no URL.
 */
class PasteUploadTest {

    @Test
    void theBodyLengthIsMeasuredInUtf8Bytes() {
        final String log = "玩家: 作弊检测失败";

        final byte[] body = PasteUpload.formBody(log);

        assertEquals(body.length, new String(body, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8).length);
        assertTrue(body.length > log.length(), "a Chinese log is longer as UTF-8 than as chars");
    }

    @Test
    void theBodyRoundTripsTheOriginalLog() {
        final String log = "line one\n玩家 二\n{\"json\": true}";

        final String body = new String(PasteUpload.formBody(log), StandardCharsets.UTF_8);

        assertTrue(body.startsWith("content="));
        assertEquals(log, URLDecoder.decode(body.substring("content=".length()), StandardCharsets.UTF_8));
    }

    @Test
    void aSuccessfulReplyYieldsItsUrl() {
        final Optional<String> url = PasteUpload.parseReply(200, "{\"success\":true,\"url\":\"https://mclo.gs/abc\"}");

        assertEquals(Optional.of("https://mclo.gs/abc"), url);
    }

    @Test
    void aFailedReplyYieldsNothing() {
        assertEquals(Optional.empty(), PasteUpload.parseReply(403, "Forbidden"));
        assertEquals(Optional.empty(), PasteUpload.parseReply(200, "{\"success\":false,\"error\":\"no\"}"));
        assertEquals(Optional.empty(), PasteUpload.parseReply(200, "{\"success\":true}"));
        assertEquals(Optional.empty(), PasteUpload.parseReply(200, "not json"));
        assertEquals(Optional.empty(), PasteUpload.parseReply(200, null));
    }
}
