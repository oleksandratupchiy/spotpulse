package com.spotpulse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:context-test;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "SPOTPULSE_BOOTSTRAP_ADMIN_ID="})
class SpotpulseApplicationTests {

    @org.springframework.beans.factory.annotation.Autowired javax.sql.DataSource dataSource;

	@Test
	void contextLoads() throws Exception {
		try (var connection = dataSource.getConnection()) {
			org.assertj.core.api.Assertions.assertThat(connection.getMetaData().getURL()).startsWith("jdbc:h2:mem:context-test");
		}
	}

}
