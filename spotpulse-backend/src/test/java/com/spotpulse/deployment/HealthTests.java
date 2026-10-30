package com.spotpulse.deployment;

import com.spotpulse.controller.HealthController;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.SQLException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthTests {
    @Test void databaseFailureReturnsOnlyGenericDownStatusAndNoCache() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("CANARY_DATABASE_PASSWORD jdbc:private-db"));
        var result = new HealthController(dataSource).health();
        assertThat(result.getStatusCode().value()).isEqualTo(503);
        assertThat(result.getBody()).containsExactlyEntriesOf(java.util.Map.of("status", "DOWN"));
        assertThat(result.getHeaders().getCacheControl()).isEqualTo("no-store");
    }
}
