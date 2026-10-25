package com.spotpulse;

import com.jayway.jsonpath.JsonPath;
import com.spotpulse.dto.RoomCreateDto;
import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.service.RoomService;
import com.spotpulse.service.SpaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:landing;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.h2.console.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true",
        "spotpulse.seo.public-base-url=https://spotpulse.example/app",
        "spotpulse.seo.indexable=true"
})
@Transactional
class SpaceLandingTests {
    @Autowired SpaceService spaces;
    @Autowired RoomService rooms;
    @Autowired WebApplicationContext context;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    MockMvc mvc;
    Long spaceId;
    Long emptyId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        spaceId = spaces.create(new SpaceCreateDto("Creative Hub", "Світлий простір у центрі міста.",
                "Main Street 22", "Kyiv", "https://images.example/hub.jpg", "owner@example.org")).getId();
        emptyId = spaces.create(new SpaceCreateDto("Quiet Corner", null, "Park Street 5", "Lviv", null)).getId();
        rooms.create(new RoomCreateDto("Meeting Studio", 8, new BigDecimal("250.50"), spaceId));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void rendersActualSpaceRoomsAndPersistentContactActions() throws Exception {
        mvc.perform(get("/spaces/{id}", spaceId))
                .andExpect(status().isOk()).andExpect(view().name("spaces/detail"))
                .andExpect(content().string(containsString("Creative Hub")))
                .andExpect(content().string(containsString("Світлий простір у центрі міста.")))
                .andExpect(content().string(containsString("Main Street 22, Kyiv")))
                .andExpect(content().string(containsString("Meeting Studio")))
                .andExpect(content().string(containsString(">8</span> осіб")))
                .andExpect(content().string(containsString("250.50")))
                .andExpect(content().string(containsString("mailto:owner@example.org")))
                .andExpect(content().string(containsString("mobile-contact d-lg-none")))
                .andExpect(content().string(containsString("contact-column")))
                .andExpect(content().string(containsString("href=\"#rooms\"")))
                .andExpect(content().string(containsString("alt=\"Коворкінг Creative Hub, Kyiv\"")))
                .andExpect(content().string(not(containsString("Quiet Corner"))));
    }

    @Test
    void unknownSpaceReturns404() throws Exception {
        mvc.perform(get("/spaces/{id}", Long.MAX_VALUE)).andExpect(status().isNotFound());
    }

    @Test
    void missingRoomImageDescriptionAndContactHaveHonestEmptyStates() throws Exception {
        mvc.perform(get("/spaces/{id}", emptyId)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Кімнати ще не додано")))
                .andExpect(content().string(containsString("Зображення простору ще не додано")))
                .andExpect(content().string(containsString("Власник ще не додав опис")))
                .andExpect(content().string(containsString("Зв'язок із простором поки недоступний")))
                .andExpect(content().string(not(containsString("property=\"og:image\""))))
                .andExpect(content().string(not(containsString("mailto:"))));
    }

    @Test
    void dynamicMetadataUsesConfiguredDomainAndIgnoresQueryAndHost() throws Exception {
        String html = mvc.perform(get("/spaces/{id}", spaceId).queryParam("utm_source", "test")
                        .header("Host", "untrusted.example").header("X-Forwarded-Host", "untrusted.example"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("<title>Creative Hub — Kyiv | SpotPulse</title>",
                "name=\"description\" content=\"Creative Hub, Kyiv. Main Street 22. Світлий простір у центрі міста.\"",
                "rel=\"canonical\" href=\"https://spotpulse.example/app/spaces/" + spaceId + "\"",
                "property=\"og:url\" content=\"https://spotpulse.example/app/spaces/" + spaceId + "\"",
                "property=\"og:title\" content=\"Creative Hub — Kyiv | SpotPulse\"",
                "property=\"og:description\"", "property=\"og:image\" content=\"https://images.example/hub.jpg\"",
                "name=\"robots\" content=\"index, follow\"");
        assertThat(html).doesNotContain("untrusted.example", "utm_source");
        assertThat(Pattern.compile("<h1(?:\\s[^>]*)?>").matcher(html).results().count()).isEqualTo(1);
        var matcher = Pattern.compile("<script type=\"application/ld\\+json\">(.*?)</script>", Pattern.DOTALL).matcher(html);
        assertThat(matcher.find()).isTrue();
        var data = JsonPath.parse(matcher.group(1));
        assertThat(data.<String>read("$['@type']")).isEqualTo("Place");
        assertThat(data.<String>read("$.address.streetAddress")).isEqualTo("Main Street 22");
        assertThat(data.<String>read("$.address.addressLocality")).isEqualTo("Kyiv");
        assertThat(matcher.group(1)).doesNotContain("rating", "review", "priceCurrency", "telephone");
        mvc.perform(get("/spaces/{id}", emptyId)).andExpect(status().isOk())
                .andExpect(content().string(containsString("<title>Quiet Corner — Lviv | SpotPulse</title>")));
    }

    @Test
    void listingLinksToDetailsAndKeepsManagementOperations() throws Exception {
        mvc.perform(get("/spaces")).andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"/spaces/" + spaceId + "\"")))
                .andExpect(content().string(containsString("href=\"/spaces/" + spaceId + "/edit\"")))
                .andExpect(content().string(containsString("action=\"/spaces/" + spaceId + "/delete\"")))
                .andExpect(content().string(containsString("href=\"/spaces/new\"")));
    }

    @Test
    void roomRenderingDoesNotIssueQueriesPerRoom() throws Exception {
        for (int i = 0; i < 10; i++) rooms.create(new RoomCreateDto("Studio " + i, 4, BigDecimal.TEN, spaceId));
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get("/spaces/{id}", spaceId)).andExpect(status().isOk())
                .andExpect(content().string(containsString("Studio 9")));
        // One space query and one rooms query; the view receives only DTOs.
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    void emailPersistsThroughValidatedCreateAndEditForms() throws Exception {
        mvc.perform(get("/spaces/{id}/edit", spaceId)).andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"owner@example.org\"")));
        mvc.perform(post("/spaces/{id}", spaceId).param("name", "Creative Hub").param("city", "Kyiv")
                        .param("address", "Main Street 22").param("contactEmail", "invalid"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("space", "contactEmail"));
        mvc.perform(post("/spaces/{id}", spaceId).param("name", "Creative Hub").param("city", "Kyiv")
                        .param("address", "Main Street 22").param("contactEmail", "updated@example.org"))
                .andExpect(status().is3xxRedirection());
        entityManager.flush();
        entityManager.clear();
        assertThat(spaces.getById(spaceId).getContactEmail()).isEqualTo("updated@example.org");
        mvc.perform(post("/spaces").param("name", "New Space").param("city", "Kyiv")
                        .param("address", "Street").param("contactEmail", "not-an-email"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("space", "contactEmail"));
        mvc.perform(post("/api/spaces").contentType("application/json")
                        .content("{\"name\":\"Space\",\"address\":\"Street\",\"city\":\"Kyiv\",\"contactEmail\":\"bad\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void robotsAndPaginatedSitemapsContainOnlyPublicPages() throws Exception {
        mvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Sitemap: https://spotpulse.example/app/sitemap.xml")))
                .andExpect(content().string(containsString("Disallow: /app/api/")));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andExpect(xpath("/*[local-name()='sitemapindex']/*[local-name()='sitemap']/*[local-name()='loc']")
                        .string("https://spotpulse.example/app/sitemaps/spaces-0.xml"));
        mvc.perform(get("/sitemaps/spaces-0.xml")).andExpect(status().isOk())
                .andExpect(content().string(containsString("<loc>https://spotpulse.example/app/spaces/" + spaceId + "</loc>")))
                .andExpect(content().string(not(containsString("/edit"))))
                .andExpect(xpath("count(/*[local-name()='urlset']/*[local-name()='url'])").number(3.0));
        mvc.perform(get("/sitemaps/spaces-1.xml")).andExpect(status().isNotFound());
        mvc.perform(get("/sitemaps/spaces--1.xml")).andExpect(status().isNotFound());
    }
}
