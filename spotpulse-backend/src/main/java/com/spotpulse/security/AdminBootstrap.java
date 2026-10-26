package com.spotpulse.security;

import com.spotpulse.model.enums.Role;
import com.spotpulse.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One-time operator action on a persistent database; never exposed over HTTP. */
@Component
public class AdminBootstrap implements ApplicationRunner {
    private final String configuredId;
    private final UserRepository users;
    private final PlatformTransactionManager transactions;
    public AdminBootstrap(@Value("${SPOTPULSE_BOOTSTRAP_ADMIN_ID:}") String id, UserRepository users, PlatformTransactionManager transactions) {
        this.configuredId = id; this.users = users; this.transactions = transactions;
    }
    public void run(ApplicationArguments args) {
        if (configuredId.isBlank()) return;
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            if (users.countByRole(Role.ADMIN) != 0) throw new IllegalStateException("Administrator already exists; remove bootstrap setting");
            var user = users.findById(Long.valueOf(configuredId)).orElseThrow(() -> new IllegalStateException("Bootstrap user does not exist"));
            if (!"google".equals(user.getOauthProvider()) || user.getOauthSubject() == null)
                throw new IllegalStateException("Bootstrap requires an existing Google-authenticated account");
            user.setRole(Role.ADMIN); users.saveAndFlush(user);
        });
    }
}
