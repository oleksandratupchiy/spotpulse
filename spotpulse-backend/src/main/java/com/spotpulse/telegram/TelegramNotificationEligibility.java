package com.spotpulse.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class TelegramNotificationEligibility {
    private final TelegramAccountRepository accounts;
    private final TelegramClient client;
    private final TelegramRuntime runtime;

    public record Recipient(Long chatId, String skipReason) { }

    // AFTER_COMMIT still has the old transaction's resources bound. Read fresh committed
    // preferences in a separate transaction, then release it before the network call.
    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Recipient recipient(Long userId) {
        var account = accounts.findById(userId).orElse(null);
        if (account == null || account.getChatId() == null) return new Recipient(null, "not_linked");
        if (!account.bookingNotificationsEnabled()) return new Recipient(null, "preference_disabled");
        if (!client.configured()) return new Recipient(null, "not_configured");
        var bot = runtime.bot();
        if (bot == null) return new Recipient(null, "bot_unavailable");
        if (!bot.id().equals(account.getBotId())) return new Recipient(null, "bot_mismatch");
        return new Recipient(account.getChatId(), null);
    }
}
