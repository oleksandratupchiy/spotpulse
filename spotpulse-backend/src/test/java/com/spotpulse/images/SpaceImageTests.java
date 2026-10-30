package com.spotpulse.images;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.model.User;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.*;
import com.spotpulse.security.*;
import com.spotpulse.service.SpaceService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:space-images;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false"})
class SpaceImageTests {
    @TempDir static Path directory;
    @DynamicPropertySource static void storage(DynamicPropertyRegistry registry) {
        registry.add("spotpulse.images.directory", () -> directory.toString());
    }
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired SpaceRepository repository;
    @Autowired SpaceService spaces;
    @Autowired SpaceImageService images;
    @Autowired ImageStorage storage;
    @Autowired PlatformTransactionManager transactions;
    AppOidcUser owner, other, admin, member;
    Long ownSpace, otherSpace;
    MockMvc mvc;
    @BeforeEach void setup() {
        owner = actor(Role.OWNER); other = actor(Role.OWNER); admin = actor(Role.ADMIN); member = actor(Role.USER);
        as(owner); ownSpace = spaces.create(new SpaceCreateDto("Own Hub", null, "Street", "Kyiv", "https://example.test/old.jpg")).getId();
        as(other); otherSpace = spaces.create(new SpaceCreateDto("Other Hub", null, "Street", "Kyiv", null)).getId();
        SecurityContextHolder.clearContext();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }
    AppOidcUser actor(Role role) {
        String name = UUID.randomUUID().toString();
        User user = new User(); user.setName(name); user.setEmail(name + "@image.test"); user.setRole(role);
        user.setOauthProvider("google"); user.setOauthSubject(name); user = users.saveAndFlush(user);
        var identity = new DefaultOidcUser(List.of(new SimpleGrantedAuthority("OIDC_USER")),
                new OidcIdToken("synthetic", Instant.now(), Instant.now().plusSeconds(600),
                        Map.of("sub", name, "email", user.getEmail(), "email_verified", true)));
        return new AppOidcUser(user, identity);
    }
    OAuth2AuthenticationToken token(AppOidcUser user) { return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"); }
    void as(AppOidcUser user) { SecurityContextHolder.getContext().setAuthentication(token(user)); }
    static byte[] picture(String format) throws Exception {
        var output = new ByteArrayOutputStream();
        var image = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
        assertThat(ImageIO.write(image, format, output)).isTrue(); return output.toByteArray();
    }
    MockMultipartFile file() throws Exception { return new MockMultipartFile("file", "photo.png", "image/png", picture("png")); }
    String upload(AppOidcUser actor, Long id, MockMultipartFile file) throws Exception {
        mvc.perform(multipart("/api/spaces/{id}/image", id).file(file).with(authentication(token(actor))).with(csrf()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        return repository.findById(id).orElseThrow().getImageUrl();
    }

    @Test void ownerUploadIsPublicNormalizedPersistentAndInvalidatesCachedDetails() throws Exception {
        assertThat(spaces.getById(ownSpace).getImageUrl()).isEqualTo("https://example.test/old.jpg");
        byte[] original = picture("png");
        var appended = new ByteArrayOutputStream(); appended.write(original); appended.write("<script>untrusted</script>".getBytes());
        String reference = upload(owner, ownSpace, new MockMultipartFile("file", "photo.png", "image/png", appended.toByteArray()));
        assertThat(reference).matches("/api/spaces/images/[a-f0-9-]{36}\\.png");
        assertThat(spaces.getById(ownSpace).getImageUrl()).isEqualTo(reference);
        mvc.perform(get(reference)).andExpect(status().isOk()).andExpect(content().contentType("image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")))
                .andExpect(content().bytes(original));
        mvc.perform(head(reference)).andExpect(status().isOk());
        mvc.perform(get("/api/spaces/{id}", ownSpace)).andExpect(jsonPath("$.imageUrl").value(reference));
        // A fresh adapter (simulating restart) resolves the persisted reference to the same bytes.
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("spotpulse.images.directory", directory.toString())
                .withProperty("user.dir", System.getProperty("user.dir"));
        assertThat(new LocalImageStorage(environment).read(reference.substring(LocalImageStorage.PUBLIC_PREFIX.length())).bytes()).isEqualTo(original);
    }

    @Test void jpegAndWebpAreDecodedAndStoredAsSafeStillImages() throws Exception {
        String jpeg = upload(owner, ownSpace, new MockMultipartFile("file", "photo.jpg", "image/jpeg", picture("jpg")));
        mvc.perform(get(jpeg)).andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"));
        // Minimal lossless 1x1 WebP fixture, no network or external image directory.
        byte[] webp = Base64.getDecoder().decode("UklGRh4AAABXRUJQVlA4TBEAAAAvAAAAAAfQ//73v/+BiOh/AAA=");
        String png = upload(owner, ownSpace, new MockMultipartFile("file", "photo.webp", "image/webp", webp));
        mvc.perform(get(png)).andExpect(status().isOk()).andExpect(content().contentType("image/png"));
    }

    @Test void authenticationRolesOwnershipAndCsrfAreEnforced() throws Exception {
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(authentication(token(member))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/spaces/{id}/image", otherSpace).file(file()).with(authentication(token(owner))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(authentication(token(owner)))).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(file()).with(authentication(token(owner))).with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        assertThat(repository.findById(ownSpace).orElseThrow().getImageUrl()).isEqualTo("https://example.test/old.jpg");
        upload(admin, otherSpace, file());
    }

    @Test void invalidMimeContentUnsupportedEmptyOversizedAndPathFilenamesAreRejected() throws Exception {
        var files = List.of(new MockMultipartFile("file", "image.png", "image/jpeg", picture("png")),
                new MockMultipartFile("file", "fake.png", "image/png", "<html>not an image</html>".getBytes()),
                new MockMultipartFile("file", "image.svg", "image/svg+xml", "<svg/>".getBytes()),
                new MockMultipartFile("file", "image.gif", "image/gif", "GIF89a".getBytes()),
                new MockMultipartFile("file", "empty.png", "image/png", new byte[0]),
                new MockMultipartFile("file", "../../outside.png", "image/png", picture("png")),
                new MockMultipartFile("file", "..\\outside.png", "image/png", picture("png")));
        for (var invalid : files) mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(invalid)
                .with(authentication(token(owner))).with(csrf())).andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/spaces/{id}/image", ownSpace)
                .file(new MockMultipartFile("file", "large.png", "image/png", new byte[ImageValidation.MAX_BYTES + 1]))
                .with(authentication(token(owner))).with(csrf())).andExpect(status().isPayloadTooLarge());
        assertThat(repository.findById(ownSpace).orElseThrow().getImageUrl()).isEqualTo("https://example.test/old.jpg");
    }

    @Test void excessiveDimensionsAndCorruptTruncatedContentAreRejectedBeforeStorage() throws Exception {
        byte[] huge = picture("png"); java.nio.ByteBuffer.wrap(huge).putInt(16, 100000).putInt(20, 100000);
        for (byte[] invalid : List.of(huge, Arrays.copyOf(picture("png"), 24)))
            mvc.perform(multipart("/api/spaces/{id}/image", ownSpace).file(new MockMultipartFile("file", "photo.png", "image/png", invalid))
                    .with(authentication(token(owner))).with(csrf())).andExpect(status().isBadRequest());
    }

    @Test void replacementRetainsOldObjectsAndRollbackCleansOnlyNewObject() throws Exception {
        String original = upload(owner, ownSpace, file());
        String replacement = upload(owner, ownSpace, file());
        assertThat(replacement).isNotEqualTo(original);
        mvc.perform(get(original)).andExpect(status().isOk());
        as(owner);
        String rolledBack = new TransactionTemplate(transactions).execute(tx -> {
            String ref = images.attach(ownSpace, uncheckedFile()).getImageUrl();
            tx.setRollbackOnly(); return ref;
        });
        assertThat(repository.findById(ownSpace).orElseThrow().getImageUrl()).isEqualTo(replacement);
        mvc.perform(get(rolledBack)).andExpect(status().isNotFound());
        mvc.perform(get(original)).andExpect(status().isOk());
        mvc.perform(get(replacement)).andExpect(status().isOk());
    }
    MockMultipartFile uncheckedFile() { try { return file(); } catch (Exception failure) { throw new AssertionError(failure); } }

    @Test void publicReadCannotTraverseOrExposeArbitraryFiles() throws Exception {
        for (String key : List.of("../secret", "..\\secret", "C:\\secret", "README.md", "not-a-generated-name.png"))
            assertThatThrownBy(() -> storage.read(key)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        mvc.perform(get("/api/spaces/images/00000000-0000-0000-0000-000000000000.png")).andExpect(status().isNotFound());
        mvc.perform(post("/api/spaces/images/test").with(csrf())).andExpect(status().isUnauthorized());
    }
}
