package com.spotpulse;

import com.jayway.jsonpath.JsonPath;
import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.service.SpaceService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:autocomplete;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.h2.console.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true"
})
@Transactional
class SpaceAutocompleteTests {
    @Autowired SpaceService spaces;
    @Autowired WebApplicationContext context;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    MockMvc mvc;
    Long hubId;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        hubId = create("Creative Hub", "Kyiv", "Main Street 22");
        create("Quiet Corner", "Lviv", "Hub Avenue");
        entityManager.flush();
        entityManager.clear();
    }

    Long create(String name, String city, String address) {
        return spaces.create(new SpaceCreateDto(name, "Private description", address, city,
                null, "owner@example.org")).getId();
    }

    List<Map<String, Object>> query(String q) throws Exception {
        String body = mvc.perform(get("/api/spaces/autocomplete").param("q", q))
                .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("application/json"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "h", "hu", " h u ", "___", "%%%", "a.!", "\u2003hu\u2003"})
    void fewerThanThreeMeaningfulCharactersDoNotQueryDatabase(String q) throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        assertThat(query(q)).isEmpty();
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @Test
    void missingQueryIsEmptyWithoutDatabaseWork() throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        mvc.perform(get("/api/spaces/autocomplete")).andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(statistics.getPrepareStatementCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Hub", "hUB", "Creative", "creATIVE", "Kyiv", "kYI", "  Hub  ", "\tHub\n", "\u2003Hub\u2003"})
    void findsNameOrCityIgnoringCaseAndSurroundingWhitespace(String q) throws Exception {
        var results = query(q);
        assertThat(results).hasSize(1);
        assertThat(((Number) results.getFirst().get("id")).longValue()).isEqualTo(hubId);
    }

    @Test
    void noMatchesAndAddressOnlyMatchesAreEmpty() throws Exception {
        assertThat(query("Unmatched")).isEmpty();
        assertThat(query("Avenue")).isEmpty();
    }

    @Test
    void responseContainsExactlyPublicSuggestionFields() throws Exception {
        assertThat(query("Hub").getFirst()).containsOnlyKeys("id", "name", "city", "address")
                .containsEntry("name", "Creative Hub").containsEntry("city", "Kyiv")
                .containsEntry("address", "Main Street 22");
    }

    @Test
    void databaseProjectionUsesOneQueryAndLimitsToEightWithStableNameCityIdOrder() throws Exception {
        create("Sort Zebra", "Kyiv", "Street");
        Long secondCity = create("Sort Alpha", "Lviv", "Street");
        Long first = create("Sort Alpha", "Kyiv", "Street");
        Long tie = create("sort alpha", "kyiv", "Street");
        for (int i = 9; i >= 0; i--) create("Sort Middle " + i, "Kyiv", "Street");
        entityManager.flush();
        entityManager.clear();
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var results = query("sort");
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(results).hasSize(8);
        assertThat(results.subList(0, 3).stream().map(item -> ((Number) item.get("id")).longValue()).toList())
                .containsExactly(first, tie, secondCity);
        assertThat(results.subList(3, 8).stream().map(item -> item.get("name")).toList())
                .containsExactly("Sort Middle 0", "Sort Middle 1", "Sort Middle 2", "Sort Middle 3", "Sort Middle 4");
        assertThat(query("SORT")).isEqualTo(results);
        assertThat(query("sort")).isEqualTo(results);
    }

    @Test
    void invalidAndExcessivelyLongQueriesReturnSafeBadRequestsWithoutSql() throws Exception {
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        for (String q : List.of("x".repeat(101), " ".repeat(101), "Hub\u0000", "Hu\nb", "Hub\u007f")) {
            mvc.perform(get("/api/spaces/autocomplete").param("q", q))
                    .andExpect(status().isBadRequest())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.status").value(400));
        }
        assertThat(statistics.getPrepareStatementCount()).isZero();
        assertThat(query("x".repeat(100))).isEmpty();
    }

    @Test
    void wildcardAndInjectionLikeInputIsTreatedLiterally() throws Exception {
        create("Hub%_! Literal", "Kyiv", "Street");
        assertThat(query("Hub%_!")).hasSize(1);
        assertThat(query("Hub%")).hasSize(1);
        assertThat(query("Hub_")).isEmpty();
        assertThat(query("' OR 1=1 --")).isEmpty();
    }

    @Test
    void unicodeLettersAreMeaningfulAndUserTextRemainsJsonData() throws Exception {
        create("Київ Хаб <img src=x onerror=alert(1)>", "Київ", "Street");
        assertThat(query("киї")).hasSize(1);
        assertThat(query("<img").getFirst().get("name")).isEqualTo("Київ Хаб <img src=x onerror=alert(1)>");
    }

    @Test
    void listingIncludesAccessibleAutocompleteAndPreservesExistingLinks() throws Exception {
        mvc.perform(get("/spaces")).andExpect(status().isOk())
                .andExpect(content().string(containsString("role=\"combobox\"")))
                .andExpect(content().string(containsString("aria-controls=\"space-search-results\"")))
                .andExpect(content().string(containsString("role=\"listbox\"")))
                .andExpect(content().string(containsString("jquery-3.7.1.min.js")))
                .andExpect(content().string(containsString("/js/space-autocomplete.js")))
                .andExpect(content().string(containsString("href=\"/spaces/" + hubId + "\"")))
                .andExpect(content().string(containsString("href=\"/spaces/" + hubId + "/edit\"")))
                .andExpect(content().string(containsString("action=\"/spaces/" + hubId + "/delete\"")));
        mvc.perform(get("/js/space-autocomplete.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("$.ajax(")));
    }

    @Test
    void autocompleteDoesNotChangeLegacyPageOrB1OffsetContracts() throws Exception {
        query("Hub");
        mvc.perform(get("/api/spaces").param("page", "0").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2));
        String body = mvc.perform(get("/api/spaces").param("skip", "0").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.skip").value(0))
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.nextLink").value("/api/spaces?skip=1&limit=1"))
                .andReturn().getResponse().getContentAsString();
        String nextLink = JsonPath.read(body, "$.nextLink");
        mvc.perform(get(URI.create(nextLink))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.skip").value(1))
                .andExpect(jsonPath("$.nextLink").isEmpty());
        mvc.perform(get("/api/spaces/{id}", hubId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Creative Hub"));
    }
}
