package com.spotpulse.repository;

import com.spotpulse.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select u from User u where u.role = com.spotpulse.model.enums.Role.ADMIN order by u.id")
    java.util.List<User> lockAdministrators();
    Optional<User> findByOauthProviderAndOauthSubject(String provider, String subject);
    Optional<User> findByEmailIgnoreCase(String email);
    long countByRole(com.spotpulse.model.enums.Role role);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    boolean existsByEmailAndIdNot(String email, Long id);
}
