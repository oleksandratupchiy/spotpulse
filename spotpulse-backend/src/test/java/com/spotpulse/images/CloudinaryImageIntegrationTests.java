package com.spotpulse.images;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.model.User;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.*;
import com.spotpulse.security.*;
import com.spotpulse.service.SpaceService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:c19-cloud-images;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
@Import(CloudinaryImageIntegrationTests.MockProvider.class)
@ExtendWith(OutputCaptureExtension.class)
class CloudinaryImageIntegrationTests {
    @TestConfiguration(proxyBeanMethods = false)
    static class MockProvider { @Bean CloudinaryOperations cloudinaryOperations() { return mock(CloudinaryOperations.class); } }
    @TempDir static Path directory;
    @DynamicPropertySource static void cloud(DynamicPropertyRegistry registry) {
        registry.add("spotpulse.images.storage-provider", () -> "cloudinary");
        registry.add("spotpulse.images.directory", () -> directory.toString());
        registry.add("CLOUDINARY_CLOUD_NAME", () -> "test-cloud");
        registry.add("CLOUDINARY_API_KEY", () -> "123456789012345");
        registry.add("CLOUDINARY_API_SECRET", () -> "CANARY_SECRET_MUST_REMAIN_PRIVATE");
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired SpaceRepository repository;
    @Autowired SpaceService spaces;
    @Autowired SpaceImageService images;
    @Autowired ImageStorage selected;
    @Autowired LocalImageStorage local;
    @Autowired CloudinaryOperations operations;
    @Autowired PlatformTransactionManager transactions;
    MockMvc mvc;
    AppOidcUser owner, other, admin, member;
    Long ownSpace, otherSpace;
    @BeforeEach void seed() throws Exception {
        assertThat(selected).isInstanceOf(CloudinaryImageStorage.class);
        reset(operations);
        when(operations.upload(any(), anyString(), anyString())).thenAnswer(invocation -> {
            var response = CloudinaryStorageTests.response(invocation.getArgument(1), invocation.getArgument(2), ((byte[]) invocation.getArgument(0)).length);
            response.put("signature", "CANARY_SECRET_MUST_REMAIN_PRIVATE"); response.put("api_key", "123456789012345");
            return response;
        });
        owner = actor(Role.OWNER); other = actor(Role.OWNER); admin = actor(Role.ADMIN); member = actor(Role.USER);
        as(owner); ownSpace = spaces.create(dto("Cloud Hub", "https://example.test/old.jpg")).getId();
        as(other); otherSpace = spaces.create(dto("Other Hub", null)).getId();
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    AppOidcUser actor(Role role) {
        String name = UUID.randomUUID().toString(); var user = new User();
        user.setName("Image manager"); user.setEmail(name + "@images.test"); user.setRole(role);
        user.setOauthProvider("google"); user.setOauthSubject(name); user = users.saveAndFlush(user);
        var oidc = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("test-only", Instant.now(), Instant.now().plusSeconds(3600), Map.of("sub", name)));
        return new AppOidcUser(user, oidc);
    }
    OAuth2AuthenticationToken token(AppOidcUser user) { return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"); }
    void as(AppOidcUser user) { SecurityContextHolder.getContext().setAuthentication(token(user)); }
    SpaceCreateDto dto(String name, String url) { return new SpaceCreateDto(name, "", "Street", "Kyiv", url); }
    MockMultipartFile file() throws Exception { return new MockMultipartFile("file", "photo.png", "image/png", SpaceImageTests.picture("png")); }
    MvcResult upload(AppOidcUser actor, Long id) throws Exception {
        return mvc.perform(multipart("/api/spaces/{id}/image", id).file(file()).with(authentication(token(actor))).with(csrf()))
                .andExpect(status().isOk()).andReturn();
    }
    @Test void ownedUploadPersistsServerMetadataUsesCdnAndInvalidatesCachedDetails(CapturedOutput output) throws Exception {
        assertThat(spaces.getById(ownSpace).getImageUrl()).isEqualTo("https://example.test/old.jpg");
        var result = upload(owner, ownSpace);
        var stored = repository.findById(ownSpace).orElseThrow();
        assertThat(stored.getImageStorageProvider()).isEqualTo("cloudinary");
        assertThat(stored.getImageStorageKey()).matches("spotpulse/spaces/[a-f0-9-]{36}");
        assertThat(stored.getImageAssetId()).isEqualTo("0123456789abcdef0123456789abcdef");
        assertThat(stored.getImageUrl()).startsWith("https://res.cloudinary.com/test-cloud/image/upload/v1770000000/spotpulse/spaces/");
        assertThat(spaces.getById(ownSpace).getImageUrl()).isEqualTo(stored.getImageUrl());
        mvc.perform(get("/api/spaces/{id}", ownSpace)).andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(stored.getImageUrl())).andExpect(jsonPath("$.imageStorageKey").doesNotExist());
        assertThat(result.getResponse().getContentAsString()).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE", "123456789012345", "signature", "imageAssetId");
        assertThat(output).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE", "123456789012345");
        verify(operations, never()).destroy(anyString());
    }
    @Test void cloudProviderPreservesAllRoleOwnershipAndCsrfChecks() throws Exception {
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(authentication(token(member))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/spaces/{id}/image", otherSpace).file(file()).with(authentication(token(owner))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(authentication(token(owner)))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(authentication(token(owner))).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        verifyNoInteractions(operations);
        upload(admin, otherSpace); verify(operations).upload(any(), anyString(), eq("png"));
    }
    @Test void unsafeFilesAreRejectedBeforeCloudUpload() throws Exception {
        for (var invalid : List.of(new MockMultipartFile("file", "image.png", "image/jpeg", SpaceImageTests.picture("png")),
                new MockMultipartFile("file", "image.svg", "image/svg+xml", "<svg/>".getBytes()),
                new MockMultipartFile("file", "fake.png", "image/png", "<html/>".getBytes()),
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]),
                new MockMultipartFile("file", "../outside.png", "image/png", SpaceImageTests.picture("png"))))
            mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(invalid).with(authentication(token(owner))).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace)
                .file(new MockMultipartFile("file", "big.png", "image/png", new byte[ImageValidation.MAX_BYTES + 1]))
                .with(authentication(token(owner))).with(csrf())).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(operations);
    }
    @Test void failedReplacementPreservesUrlMetadataAndOldAssetsWithoutSecretLeak(CapturedOutput output) throws Exception {
        upload(owner, ownSpace); var original = repository.findById(ownSpace).orElseThrow();
        String url = original.getImageUrl(), key = original.getImageStorageKey();
        doThrow(new IOException("CANARY_SECRET_MUST_REMAIN_PRIVATE")).when(operations).upload(any(), anyString(), anyString());
        var result = mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(authentication(token(owner))).with(csrf()))
                .andExpect(status().isBadGateway()).andReturn();
        var after = repository.findById(ownSpace).orElseThrow();
        assertThat(after.getImageUrl()).isEqualTo(url); assertThat(after.getImageStorageKey()).isEqualTo(key);
        assertThat(spaces.getById(ownSpace).getImageUrl()).isEqualTo(url);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE", "123456789012345");
        assertThat(output).doesNotContain("CANARY_SECRET_MUST_REMAIN_PRIVATE", "123456789012345");
        verify(operations, never()).destroy(anyString());
    }
    @Test void rollbackCleansOnlyNewCloudUploadWhileCommittedReplacementRetainsOldAssets() throws Exception {
        upload(owner, ownSpace); String oldKey = repository.findById(ownSpace).orElseThrow().getImageStorageKey();
        upload(owner, ownSpace); String replacementKey = repository.findById(ownSpace).orElseThrow().getImageStorageKey();
        assertThat(replacementKey).isNotEqualTo(oldKey); verify(operations, never()).destroy(anyString());
        as(owner);
        String rolledBackUrl = new TransactionTemplate(transactions).execute(tx -> {
            try { var saved = images.attach(ownSpace, file()); tx.setRollbackOnly(); return saved.getImageUrl(); }
            catch (Exception failure) { throw new AssertionError(failure); }
        });
        String rollbackKey = rolledBackUrl.substring(rolledBackUrl.indexOf("spotpulse/spaces/"), rolledBackUrl.length() - 4);
        verify(operations).destroy(rollbackKey); verify(operations, never()).destroy(oldKey); verify(operations, never()).destroy(replacementKey);
        assertThat(repository.findById(ownSpace).orElseThrow().getImageStorageKey()).isEqualTo(replacementKey);
    }
    @Test void mixedLocalExternalAndCloudinaryReferencesStayReadable() throws Exception {
        var localImage = local.store(new ImageValidation().validate(file()));
        as(owner);
        Long legacyLocal = spaces.create(dto("Legacy local", localImage.reference())).getId();
        Long external = spaces.create(dto("Legacy external", "https://images.example.test/legacy.jpg")).getId();
        upload(owner, ownSpace);
        mvc.perform(get(localImage.reference())).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
        mvc.perform(get("/api/spaces/{id}", legacyLocal)).andExpect(jsonPath("$.imageUrl").value(localImage.reference()));
        mvc.perform(get("/api/spaces/{id}", external)).andExpect(jsonPath("$.imageUrl").value("https://images.example.test/legacy.jpg"));
        assertThat(repository.findById(legacyLocal).orElseThrow().getImageStorageKey()).isNull();
        verify(operations, never()).destroy(anyString());
    }
    @Test void browserUrlEditingCannotForgeCloudOwnershipOrDeleteAssets() throws Exception {
        upload(owner, ownSpace);
        mvc.perform(put("/api/spaces/{id}", ownSpace).with(authentication(token(owner))).with(csrf()).contentType("application/json")
                .content("{\"name\":\"Edited\",\"address\":\"Street\",\"city\":\"Kyiv\",\"imageUrl\":\"https://example.test/other.jpg\",\"imageStorageKey\":\"unrelated/asset\",\"imageStorageProvider\":\"cloudinary\"}"))
                .andExpect(status().isOk());
        var after = repository.findById(ownSpace).orElseThrow();
        assertThat(after.getImageUrl()).isEqualTo("https://example.test/other.jpg");
        assertThat(after.getImageStorageProvider()).isNull(); assertThat(after.getImageStorageKey()).isNull(); assertThat(after.getImageAssetId()).isNull();
        verify(operations, never()).destroy(anyString());
    }
}
