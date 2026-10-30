package com.spotpulse.telegram;

import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface TelegramPollRepository extends JpaRepository<TelegramPollState, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from TelegramPollState s where s.botId = :botId")
    Optional<TelegramPollState> lock(Long botId);
}
