package com.spotpulse;

import com.spotpulse.controller.SeoController;
import com.spotpulse.dto.SpaceDto;
import com.spotpulse.seo.SpaceSeo;
import com.spotpulse.service.SpaceService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SpaceSeoTests {
    private SpaceDto space() {
        SpaceDto space = new SpaceDto();
        space.setId(42L);
        space.setName("Hub");
        space.setCity("Kyiv");
        space.setAddress("Street 1");
        return space;
    }

    @Test
    void unconfiguredSiteHasNoInventedCanonicalAndDoesNotAdvertiseSitemap() throws Exception {
        SpaceSeo seo = new SpaceSeo("", true, JsonMapper.builder().build());
        var metadata = seo.metadata(space());
        assertThat(metadata.canonicalUrl()).isNull();
        assertThat(metadata.robots()).isEqualTo("noindex, follow");
        assertThat(metadata.structuredData()).doesNotContain("localhost", "\"url\"");
        var mvc = MockMvcBuilders.standaloneSetup(new SeoController(seo, mock(SpaceService.class))).build();
        mvc.perform(get("/robots.txt")).andExpect(content().string("User-agent: *\nDisallow: /\n"));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isNotFound());
    }

    @Test
    void stagingCanDisableIndexingWithPublicBaseConfigured() {
        var seo = new SpaceSeo("https://spotpulse.example/", false, JsonMapper.builder().build());
        assertThat(seo.metadata(space()).robots()).isEqualTo("noindex, follow");
        assertThat(seo.metadata(space()).canonicalUrl()).isEqualTo("https://spotpulse.example/spaces/42");
    }

    @Test
    void rejectsInvalidPublicBaseUrls() {
        for (String base : new String[]{"/relative", "javascript:alert(1)", "https://example.org?q=x",
                "https://example.org#fragment", "https://user:pass@example.org"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> new SpaceSeo(base, true, JsonMapper.builder().build()));
        }
    }

    @Test
    void structuredDataEscapesScriptTerminatorsAndMetadataIsBounded() {
        SpaceDto space = space();
        space.setDescription("</script><script>alert('x')</script> " + "Real description ".repeat(50));
        var seo = new SpaceSeo("https://spotpulse.example", true, JsonMapper.builder().build());
        var metadata = seo.metadata(space);
        assertThat(metadata.structuredData()).doesNotContain("<", "</script>").contains("\\u003c");
        assertThat(metadata.description().length()).isLessThanOrEqualTo(160);
        assertThat(JsonMapper.builder().build().readTree(metadata.structuredData()).get("description").asString())
                .isEqualTo(space.getDescription());
    }

    @Test
    void unsafeImagesUsePlaceholderAndAppRelativeImagesUseConfiguredBase() {
        var seo = new SpaceSeo("https://spotpulse.example/app", true, JsonMapper.builder().build());
        SpaceDto space = space();
        for (String image : new String[]{"javascript:alert(1)", "data:image/svg+xml,unsafe", "//untrusted.example/img", "not a url"}) {
            space.setImageUrl(image);
            assertThat(seo.metadata(space).imageUrl()).isNull();
        }
        space.setImageUrl("/images/hub.jpg");
        assertThat(seo.metadata(space).imageUrl()).isEqualTo("https://spotpulse.example/app/images/hub.jpg");
    }
}
