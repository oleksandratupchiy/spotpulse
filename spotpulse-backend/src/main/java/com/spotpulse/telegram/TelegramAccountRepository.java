package com.spotpulse.telegram;

import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface TelegramAccountRepository extends JpaRepository<TelegramAccount, Long> {
    Optional<TelegramAccount> findByCodeHash(String codeHash);
    Optional<TelegramAccount> findByChatId(Long chatId);
}
