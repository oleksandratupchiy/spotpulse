package com.spotpulse.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import static org.assertj.core.api.Assertions.*;

class E2eIsolationTests {
    @Test void bootstrapDoesNotAcceptPublicUrlsOrOrdinaryInvocation() {
        assertThatThrownBy(() -> E2eApplication.main(new String[0])).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> E2eApplication.main(new String[]{"https://spotpulse.onrender.com", "18089"})).isInstanceOf(Exception.class);
    }
    @Test void seamIsAbsentInProductionEvenWithE2eProfile() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("e2e", "prod");
            context.register(E2eApplication.Harness.class, E2eApplication.FixtureController.class);
            context.refresh();
            assertThat(context.getBeansOfType(E2eApplication.FixtureController.class)).isEmpty();
            assertThat(context.containsBean("fixtureSecurity")).isFalse();
        }
    }
    @Test void seamIsAbsentInOrdinaryLocalDevelopment() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().setActiveProfiles("local");
            context.register(E2eApplication.Harness.class, E2eApplication.FixtureController.class);
            context.refresh();
            assertThat(context.getBeansOfType(E2eApplication.FixtureController.class)).isEmpty();
            assertThat(context.containsBean("fixtureSecurity")).isFalse();
        }
    }
}
