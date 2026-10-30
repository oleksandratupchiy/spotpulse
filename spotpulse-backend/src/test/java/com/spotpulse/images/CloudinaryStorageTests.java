package com.spotpulse.images;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.test.system.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.net.SocketTimeoutException;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class CloudinaryStorageTests {
    CloudinaryOperations operations;
    CloudinaryImageStorage storage;
    Consumer<Map<String, Object>> change = response -> { };
    final ImageValidation.Validated image = new ImageValidation.Validated(new byte[]{1,2,3}, "png");
    static CloudinarySettings settings() {
        return CloudinarySettings.read(new MockEnvironment().withProperty("CLOUDINARY_CLOUD_NAME", "test-cloud")
                .withProperty("CLOUDINARY_API_KEY", "123456789012345").withProperty("CLOUDINARY_API_SECRET", "CANARY_SECRET_MUST_REMAIN_PRIVATE"));
    }
    static Map<String, Object> response(String publicId, String format, int bytes) {
        var result = new HashMap<String, Object>();
        result.put("public_id", publicId); result.put("asset_id", "0123456789abcdef0123456789abcdef");
        result.put("resource_type", "image"); result.put("type", "upload"); result.put("format", format);
        result.put("version", 1770000000L); result.put("width", 3); result.put("height", 2); result.put("bytes", bytes);
        result.put("secure_url", "https://res.cloudinary.com/test-cloud/image/upload/v1770000000/" + publicId + "." + format);
        return result;
    }
    @BeforeEach void setup() throws Exception {
        operations = mock(CloudinaryOperations.class);
        when(operations.upload(any(), anyString(), anyString())).thenAnswer(invocation -> {
            var result = response(invocation.getArgument(1), invocation.getArgument(2), ((byte[]) invocation.getArgument(0)).length);
            change.accept(result); return result;
        });
        storage = new CloudinaryImageStorage(settings(), operations);
    }
    @Test void successReturnsStableHttpsUrlAndServerGeneratedIdentity() throws Exception {
        var result = storage.store(image);
        assertThat(result.provider()).isEqualTo("cloudinary");
        assertThat(result.key()).matches("spotpulse/spaces/[a-f0-9-]{36}");
        assertThat(result.assetId()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(result.reference()).isEqualTo("https://res.cloudinary.com/test-cloud/image/upload/v1770000000/" + result.key() + ".png");
        verify(operations).upload(same(image.bytes()), eq(result.key()), eq("png"));
        assertThatThrownBy(() -> storage.read(result.key())).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
        storage.committed(result); storage.discard(result); verify(operations, never()).destroy(anyString());
    }
    @Test void rollbackDeletionRequiresTheExactPendingServerReceiptAndIsIdempotent() throws Exception {
        var receipt = storage.store(image);
        storage.discard(new ImageStorage.Stored(receipt.reference(), receipt.key(), receipt.provider(), receipt.assetId()));
        storage.discard(new ImageStorage.Stored("https://evil.example/image.png", "unrelated/asset", "cloudinary", receipt.assetId()));
        verify(operations, never()).destroy(anyString());
        storage.discard(receipt); storage.discard(receipt);
        verify(operations, times(1)).destroy(receipt.key());
    }
    @Test void pendingUploadsAreBoundedAndCommitReleasesCapacity() throws Exception {
        var receipts = new ArrayList<ImageStorage.Stored>();
        for (int i = 0; i < 8; i++) receipts.add(storage.store(image));
        assertThatThrownBy(() -> storage.store(image)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        storage.committed(receipts.getFirst());
        var next = storage.store(image); storage.committed(next); receipts.forEach(storage::committed);
        verify(operations, times(9)).upload(any(), anyString(), anyString());
    }
    @Test void emptyOversizedAndUnsupportedNormalizedObjectsNeverReachProvider() {
        for (var invalid : List.of(new ImageValidation.Validated(new byte[0], "png"),
                new ImageValidation.Validated(new byte[ImageValidation.MAX_BYTES + 1], "png"),
                new ImageValidation.Validated(new byte[]{1}, "svg")))
            assertThatThrownBy(() -> storage.store(invalid)).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(operations);
    }
    @Test void uploadTimeoutAndErrorsAreSanitized(CapturedOutput output) throws Exception {
        for (Exception error : List.of(new SocketTimeoutException("CANARY_SECRET_MUST_REMAIN_PRIVATE"), new IOException("CANARY_SECRET_MUST_REMAIN_PRIVATE"),
                new IllegalArgumentException("CANARY_SECRET_MUST_REMAIN_PRIVATE"))) {
            doThrow(error).when(operations).upload(any(), anyString(), anyString());
            assertThatThrownBy(() -> storage.store(image)).isInstanceOfSatisfying(ResponseStatusException.class, failure -> {
                assertThat(failure.getStatusCode()).isEqualTo(error instanceof SocketTimeoutException ? HttpStatus.GATEWAY_TIMEOUT : HttpStatus.BAD_GATEWAY);
                assertThat(failure).hasNoCause();
                assertThat(failure.getMessage()).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE");
            });
        }
        assertThat(output).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE");
        verify(operations, never()).destroy(anyString());
    }
    @Test void cleanupFailureIsBestEffortAndNeverLogsProviderException(CapturedOutput output) throws Exception {
        var receipt = storage.store(image);
        doThrow(new IOException("CANARY_SECRET_MUST_REMAIN_PRIVATE")).when(operations).destroy(receipt.key());
        assertThatCode(() -> storage.discard(receipt)).doesNotThrowAnyException();
        assertThat(output).contains("rollback cleanup failed").doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE", "123456789012345");
        storage.discard(receipt); verify(operations, times(1)).destroy(receipt.key());
    }
    @Test void missingResponseIsRejectedWithoutDeletingAnything() throws Exception {
        doReturn(null).when(operations).upload(any(), anyString(), anyString());
        assertThatThrownBy(() -> storage.store(image)).isInstanceOf(ResponseStatusException.class);
        verify(operations, never()).destroy(anyString());
    }
    static Arguments invalid(String name, Consumer<Map<String, Object>> change) { return Arguments.of(name, change); }
    static Stream<Arguments> invalidResponses() {
        return Stream.of(
                invalid("HTTP delivery", r -> r.put("secure_url", r.get("secure_url").toString().replace("https:", "http:"))),
                invalid("foreign host", r -> r.put("secure_url", r.get("secure_url").toString().replace("res.cloudinary.com", "evil.example"))),
                invalid("host suffix", r -> r.put("secure_url", r.get("secure_url").toString().replace("res.cloudinary.com", "res.cloudinary.com.evil.example"))),
                invalid("userinfo", r -> r.put("secure_url", r.get("secure_url").toString().replace("https://", "https://user:password@"))),
                invalid("query", r -> r.put("secure_url", r.get("secure_url") + "?api_key=CANARY_SECRET_MUST_REMAIN_PRIVATE")),
                invalid("fragment", r -> r.put("secure_url", r.get("secure_url") + "#fragment")),
                invalid("wrong account", r -> r.put("secure_url", r.get("secure_url").toString().replace("test-cloud/", "other-cloud/"))),
                invalid("wrong public ID", r -> r.put("public_id", "spotpulse/spaces/unrelated")),
                invalid("traversal ID", r -> r.put("public_id", "spotpulse/spaces/../unrelated")),
                invalid("invalid asset ID", r -> r.put("asset_id", "../../unrelated")),
                invalid("wrong resource", r -> r.put("resource_type", "video")),
                invalid("private resource", r -> r.put("type", "private")),
                invalid("unexpected format", r -> r.put("format", "svg")),
                invalid("invalid version", r -> r.put("version", 0)),
                invalid("fractional version", r -> r.put("version", 1.5)),
                invalid("missing URL", r -> r.remove("secure_url")),
                invalid("oversized response", r -> r.put("bytes", ImageValidation.MAX_BYTES + 1)),
                invalid("wide image", r -> r.put("width", 8193)),
                invalid("pixel count", r -> { r.put("width", 4000); r.put("height", 4000); }),
                invalid("negative dimensions", r -> r.put("height", -1)),
                invalid("existing asset", r -> r.put("existing", true)),
                invalid("provider error", r -> r.put("error", "CANARY_SECRET_MUST_REMAIN_PRIVATE")));
    }
    @ParameterizedTest(name = "rejects {0}") @MethodSource("invalidResponses")
    void rejectsUnsafeResponsesWithoutAcceptingDeletionTargets(String name, Consumer<Map<String, Object>> mutation, CapturedOutput output) throws Exception {
        change = mutation;
        assertThatThrownBy(() -> storage.store(image)).isInstanceOfSatisfying(ResponseStatusException.class,
                failure -> assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY));
        verify(operations, never()).destroy(anyString());
        assertThat(output).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE");
    }
}
