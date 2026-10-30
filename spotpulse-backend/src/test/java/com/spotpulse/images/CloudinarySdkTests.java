package com.spotpulse.images;

import com.cloudinary.*;
import com.cloudinary.http5.UploaderStrategy;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CloudinarySdkTests {
    @Test void actualSdkHasFiniteConnectionLeaseConnectAndResponseTimeouts() {
        var configuration = CloudinarySdkOperations.configuration(CloudinaryStorageTests.settings());
        assertThat(configuration.get("secure")).isEqualTo(true);
        assertThat(configuration.get("upload_prefix")).isEqualTo("https://api.cloudinary.com");
        var sdk = new Cloudinary(configuration);
        var strategy = new UploaderStrategy(); strategy.init(sdk.uploader());
        var request = strategy.buildRequestConfig();
        assertThat(request.getConnectionRequestTimeout().toSeconds()).isEqualTo(15);
        assertThat(request.getConnectTimeout().toSeconds()).isEqualTo(15);
        assertThat(request.getResponseTimeout().toSeconds()).isEqualTo(15);
        assertThat(CloudinaryStorageTests.settings().toString()).isEqualTo("CloudinarySettings[redacted]");
    }
    @Test @SuppressWarnings({"rawtypes", "unchecked"})
    void sdkUploadsBytesWithGeneratedIdAndNeverAllowsOverwriteOrBrowserFilenames() throws Exception {
        var sdk = mock(Cloudinary.class); var uploader = mock(Uploader.class); when(sdk.uploader()).thenReturn(uploader);
        byte[] bytes = {1,2,3};
        when(uploader.upload(same(bytes), anyMap())).thenReturn(Map.of("ok", true));
        var operations = new CloudinarySdkOperations(sdk);
        operations.upload(bytes, "spotpulse/spaces/server-generated", "png");
        ArgumentCaptor<Map> options = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(same(bytes), options.capture());
        assertThat(options.getValue()).containsEntry("overwrite", false).containsEntry("resource_type", "image")
                .containsEntry("type", "upload").containsEntry("use_filename", false).containsEntry("unique_filename", false)
                .containsEntry("public_id", "spotpulse/spaces/server-generated").containsEntry("filename", "image.png");
        assertThat(options.getValue()).doesNotContainKeys("unsigned", "upload_preset", "transformation", "context", "metadata");
    }
    @Test void cleanupUsesOnlyImageUploadNamespaceAndChecksItsResult() throws Exception {
        var sdk = mock(Cloudinary.class); var uploader = mock(Uploader.class); when(sdk.uploader()).thenReturn(uploader);
        when(uploader.destroy(eq("spotpulse/spaces/generated"), anyMap())).thenReturn(Map.of("result", "ok"));
        var operations = new CloudinarySdkOperations(sdk); operations.destroy("spotpulse/spaces/generated");
        verify(uploader).destroy("spotpulse/spaces/generated", Map.of("resource_type", "image", "type", "upload", "invalidate", true));
        when(uploader.destroy(eq("spotpulse/spaces/generated"), anyMap())).thenReturn(Map.of("result", "unexpected"));
        assertThatThrownBy(() -> operations.destroy("spotpulse/spaces/generated")).isInstanceOf(java.io.IOException.class);
    }
}
