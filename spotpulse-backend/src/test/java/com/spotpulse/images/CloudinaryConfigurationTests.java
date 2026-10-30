package com.spotpulse.images;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.*;
import org.springframework.core.env.StandardEnvironment;
import org.junit.jupiter.api.extension.ExtendWith;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class CloudinaryConfigurationTests {
    @TempDir Path directory;
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withInitializer(context -> {
                    // These lightweight contexts do not run the normal test EnvironmentPostProcessor.
                    // Remove inherited settings so provider-selection tests never read real cloud credentials.
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    context.getEnvironment().getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
                }).withUserConfiguration(ImageStorageConfiguration.class, LocalImageStorage.class)
                .withPropertyValues("spotpulse.images.directory=" + directory,
                        "CLOUDINARY_CLOUD_NAME=", "CLOUDINARY_API_KEY=", "CLOUDINARY_API_SECRET=");
    }
    @Test void localIsDefaultWithoutAnyCloudCredentials() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ImageStorage.class)).isSameAs(context.getBean(LocalImageStorage.class));
        });
    }
    @Test void partialCloudCredentialsDoNotAffectExplicitLocalMode() {
        runner().withPropertyValues("spotpulse.images.storage-provider=local", "CLOUDINARY_CLOUD_NAME=demo").run(context -> assertThat(context).hasNotFailed());
    }
    @Test void unknownProviderFailsWithoutEchoingItsValue(CapturedOutput output) {
        runner().withPropertyValues("spotpulse.images.storage-provider=CANARY_UNKNOWN_PROVIDER").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("SPOTPULSE_IMAGE_STORAGE must be local or cloudinary");
        });
        assertThat(output).doesNotContain("CANARY_UNKNOWN_PROVIDER");
    }
    @Test void cloudSelectionRequiresEveryCredentialAndNeverFallsBack(CapturedOutput output) {
        for (String missing : new String[]{"CLOUDINARY_CLOUD_NAME", "CLOUDINARY_API_KEY", "CLOUDINARY_API_SECRET"}) {
            runner().withPropertyValues("spotpulse.images.storage-provider=cloudinary", "CLOUDINARY_CLOUD_NAME=demo",
                    "CLOUDINARY_API_KEY=123456789012345", "CLOUDINARY_API_SECRET=CANARY_SECRET_MUST_REMAIN_PRIVATE", missing + "=")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure().getCause().getMessage()).contains("Cloudinary storage requires valid");
                    });
        }
        assertThat(output).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE", "123456789012345");
    }
    @Test void syntacticallyInvalidSettingsFailWithRedactedErrors(CapturedOutput output) {
        for (String invalid : new String[]{"CLOUDINARY_CLOUD_NAME=https://bad.example/path", "CLOUDINARY_API_KEY=not-a-key", "CLOUDINARY_API_SECRET=bad secret"})
            runner().withPropertyValues("spotpulse.images.storage-provider=cloudinary", "CLOUDINARY_CLOUD_NAME=demo",
                    "CLOUDINARY_API_KEY=123456789012345", "CLOUDINARY_API_SECRET=CANARY_SECRET_MUST_REMAIN_PRIVATE", invalid)
                    .run(context -> assertThat(context).hasFailed());
        assertThat(output).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE", "https://bad.example/path", "123456789012345");
    }
    @Test void completeCloudSelectionKeepsLocalReaderAndConstructsOfficialSdkOffline() {
        runner().withPropertyValues("spotpulse.images.storage-provider=cloudinary", "CLOUDINARY_CLOUD_NAME=demo",
                "CLOUDINARY_API_KEY=123456789012345", "CLOUDINARY_API_SECRET=CANARY_SECRET_MUST_REMAIN_PRIVATE")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(ImageStorage.class)).isInstanceOf(CloudinaryImageStorage.class);
                    assertThat(context.getBean(LocalImageStorage.class)).isNotNull();
                });
    }
}
