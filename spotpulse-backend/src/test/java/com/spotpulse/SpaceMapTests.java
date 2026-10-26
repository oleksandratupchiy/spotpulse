package com.spotpulse;

import com.spotpulse.dto.SpaceCreateDto;
import com.spotpulse.repository.SpaceRepository;
import com.spotpulse.service.SpaceService;
import jakarta.persistence.EntityManager;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:maps;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false",
        "spring.h2.console.enabled=false"
})
@Transactional
class SpaceMapTests {
    @Autowired SpaceService spaces;
    @Autowired SpaceRepository repository;
    @Autowired EntityManager em;
    @Autowired Validator validator;
    @Autowired WebApplicationContext context;
    MockMvc mvc;
    Long first;

    SpaceCreateDto input(String name, String city, Double lat, Double lon) {
        return new SpaceCreateDto(name, "Description", "Street 1", city, null, null, lat, lon);
    }

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        first = spaces.create(input("River Hub", "Kyiv", 50.45, 30.52)).getId();
        spaces.create(input("Garden Hub", "Kyiv", 50.44, 30.51));
        spaces.create(input("River Lviv", "Lviv", 49.84, 24.03));
        spaces.create(input("Unlocated", "Kyiv", null, null));
    }

    @Test
    void validatesRangesPairsAndFiniteNumbers() throws Exception {
        for (double[] pair : new double[][]{{91, 30}, {-91, 30}, {50, 181}, {50, -181}, {Double.NaN, 1}, {1, Double.POSITIVE_INFINITY}}) {
            assertThat(validator.validate(input("Invalid", "Kyiv", pair[0], pair[1]))).isNotEmpty();
        }
        assertThat(validator.validate(input("Half", "Kyiv", 50.0, null))).isNotEmpty();
        assertThat(validator.validate(input("Edges", "Kyiv", -90.0, 180.0))).isEmpty();
        assertThat(validator.validate(input("Missing", "Kyiv", null, null))).isEmpty();
        for (String coordinates : new String[]{"\"latitude\":91,\"longitude\":30", "\"latitude\":50", "\"latitude\":50,\"longitude\":-181"}) {
            mvc.perform(post("/api/spaces").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Bad\",\"city\":\"Kyiv\",\"address\":\"Street\"," + coordinates + "}"))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void persistsAndSerializesCoordinates() throws Exception {
        em.flush(); em.clear();
        var entity = repository.findById(first).orElseThrow();
        assertThat(entity.getLatitude()).isEqualTo(50.45);
        assertThat(entity.getLongitude()).isEqualTo(30.52);
        mvc.perform(get("/api/spaces/{id}", first)).andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").value(50.45)).andExpect(jsonPath("$.longitude").value(30.52))
                .andExpect(jsonPath("$.rooms").isArray()).andExpect(jsonPath("$.name").value("River Hub"));
    }

    @Test
    void multipleMarkersFiltersMissingAndTotals() throws Exception {
        mvc.perform(get("/api/spaces/map")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3)).andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.missingCoordinates").value(1)).andExpect(jsonPath("$.located").value(3))
                .andExpect(jsonPath("$.items[0].id").value(first)).andExpect(jsonPath("$.items[0].latitude").value(50.45))
                .andExpect(jsonPath("$.items[0].rooms").doesNotExist()).andExpect(jsonPath("$.items[0].contactEmail").doesNotExist());
        mvc.perform(get("/api/spaces/map").param("city", "Kyiv"))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.total").value(3));
        // Both endpoints retain the established search-over-city precedence.
        mvc.perform(get("/api/spaces/map").param("city", "Kyiv").param("search", "rIvEr"))
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/spaces").param("city", "Kyiv").param("search", "rIvEr").param("skip", "0").param("limit", "1"))
                .andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/spaces/map").param("search", "Unlocated"))
                .andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.missingCoordinates").value(1))
                .andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/spaces/map").param("city", "Missing"))
                .andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/spaces/map").param("city", "   "))
                .andExpect(jsonPath("$.total").value(4));
    }

    @Test
    void boundsResultsIndependentlyOfListingPage() throws Exception {
        mvc.perform(get("/api/spaces/map").param("limit", "1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.located").value(3))
                .andExpect(jsonPath("$.limit").value(1)).andExpect(jsonPath("$.truncated").value(true));
        for (String invalid : new String[]{"0", "201", "-1", "abc"}) {
            mvc.perform(get("/api/spaces/map").param("limit", invalid)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/spaces/map").param("search", "x".repeat(256))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/spaces/map").param("city", "Kyiv\n")).andExpect(status().isBadRequest());
        for (int i = 0; i < 201; i++) spaces.create(input("Extra " + i, "Kyiv", 50.45, 30.52));
        mvc.perform(get("/api/spaces/map")).andExpect(jsonPath("$.items.length()").value(200))
                .andExpect(jsonPath("$.located").value(204)).andExpect(jsonPath("$.truncated").value(true));
    }

    @Test
    void legacyCreateAndEditRemainCompatibleAndAddressChangesInvalidateCoordinates() throws Exception {
        mvc.perform(post("/api/spaces").contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Legacy\",\"city\":\"Kyiv\",\"address\":\"Street\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.latitude").value(nullValue()));
        spaces.update(first, input("Renamed", "Kyiv", null, null));
        assertThat(spaces.getById(first).getLatitude()).isEqualTo(50.45);
        var changed = input("Renamed", "Kyiv", null, null); changed.setAddress("Different street");
        spaces.update(first, changed);
        assertThat(spaces.getById(first).getLatitude()).isNull();
        mvc.perform(get("/api/spaces").param("page", "0").param("size", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(2));
    }
}
