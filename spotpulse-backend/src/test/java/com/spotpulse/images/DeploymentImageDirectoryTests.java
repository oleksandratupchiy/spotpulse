package com.spotpulse.images;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class DeploymentImageDirectoryTests {
    @TempDir Path temporary;
    @Test void packagedRuntimeWithoutGitAcceptsExternalLazyImageDirectory() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("runtime")); Path images = temporary.resolve("images");
        var env = new MockEnvironment().withProperty("user.dir", work.toString()).withProperty("spotpulse.images.directory", images.toString());
        assertThatCode(() -> new LocalImageStorage(env)).doesNotThrowAnyException();
        assertThat(Files.exists(images)).isFalse();
    }
    @Test void packagedRuntimeStillRejectsImagesInsideItsWorkingDirectory() throws Exception {
        Path work = Files.createDirectory(temporary.resolve("runtime"));
        var env = new MockEnvironment().withProperty("user.dir", work.toString()).withProperty("spotpulse.images.directory", work.resolve("images").toString());
        assertThatThrownBy(() -> new LocalImageStorage(env)).isInstanceOf(IllegalStateException.class).hasMessageContaining("outside the checkout");
    }
}
