package com.spotpulse;

import com.spotpulse.model.User;
import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.UserRepository;
import com.spotpulse.security.AdminBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:bootstrap-test;DB_CLOSE_DELAY=-1", "spring.jpa.hibernate.ddl-auto=create-drop", "spring.jpa.show-sql=false", "spring.h2.console.enabled=false", "SPOTPULSE_BOOTSTRAP_ADMIN_ID="})
@Transactional
class AdminBootstrapTests {
    @Autowired UserRepository users;
    @Autowired PlatformTransactionManager transactions;

    @Test void bootstrapRequiresExplicitExistingGoogleIdentityAndRefusesAfterFirstAdminExists() {
        User legacy = new User(); legacy.setName("Legacy"); legacy.setEmail("legacy@example.test"); users.saveAndFlush(legacy);
        assertThatThrownBy(() -> new AdminBootstrap(legacy.getId().toString(), users, transactions).run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
        User linked = new User(); linked.setName("Reviewed member"); linked.setEmail("linked@example.test");
        linked.setOauthProvider("google"); linked.setOauthSubject("verified-test-subject"); users.saveAndFlush(linked);
        var bootstrap = new AdminBootstrap(linked.getId().toString(), users, transactions);
        bootstrap.run(new DefaultApplicationArguments());
        assertThat(users.findById(linked.getId()).orElseThrow().getRole()).isEqualTo(Role.ADMIN);
        assertThatThrownBy(() -> bootstrap.run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class);
    }
}
