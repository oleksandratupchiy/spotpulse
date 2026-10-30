package com.spotpulse.telegram;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class TelegramPollingStore {
    private final TelegramPollRepository states;
    private final TelegramLinkService links;
    public record Lease(boolean acquired, long offset) { }
    public record Reply(Long chatId, String text) { }

    @Transactional(timeout = 5) public Lease acquire(Long botId, String owner) {
        var state = states.lock(botId).orElse(null);
        if (state == null) {
            state = new TelegramPollState(); state.setBotId(botId);
            state = states.saveAndFlush(state); // A racing first insert is retried by the worker.
        }
        if (state.getLeaseUntil() != null && state.getLeaseUntil().isAfter(Instant.now())
                && !owner.equals(state.getLeaseOwner())) return new Lease(false, 0);
        state.setLeaseOwner(owner); state.setLeaseUntil(Instant.now().plusSeconds(90));
        return new Lease(true, state.getNextOffset());
    }

    @Transactional(timeout = 5) public List<Reply> process(Long botId, String owner, JsonNode updates) {
        var state = states.lock(botId).orElseThrow();
        if (!owner.equals(state.getLeaseOwner()) || !state.getLeaseUntil().isAfter(Instant.now())) return List.of();
        var replies = new ArrayList<Reply>();
        if (!updates.isArray()) return replies;
        for (var update : updates) {
            if (!update.path("update_id").isIntegralNumber()) continue;
            long id = update.path("update_id").asLong();
            if (id < state.getNextOffset()) continue;
            var message = update.path("message");
            var chat = message.path("chat");
            var from = message.path("from");
            // Never link groups, channels, bot senders or messages on behalf of other chats.
            if ("private".equals(chat.path("type").asText()) && chat.path("id").isIntegralNumber()
                    && from.path("id").isIntegralNumber() && chat.path("id").asLong() > 0
                    && chat.path("id").asLong() == from.path("id").asLong()
                    && !from.path("is_bot").asBoolean(true)) {
                String text = message.path("text").asText("");
                Long chatId = chat.path("id").asLong();
                if (text.matches("/start(?:@[A-Za-z0-9_]+)?(?: .*)?")) {
                    String code = text.contains(" ") ? text.substring(text.indexOf(' ') + 1).trim() : null;
                    replies.add(new Reply(chatId, links.accept(botId, chatId, code)));
                } else if (text.equals("/help")) {
                    replies.add(new Reply(chatId, "Connect from your SpotPulse profile. Use that profile to view or disconnect Telegram."));
                }
            }
            // The link mutation and offset commit together, including ignored/invalid updates.
            state.setNextOffset(id + 1);
        }
        return replies;
    }

    @Transactional(timeout = 5) public void release(Long botId, String owner) {
        states.lock(botId).ifPresent(state -> {
            if (owner.equals(state.getLeaseOwner())) { state.setLeaseOwner(null); state.setLeaseUntil(null); }
        });
    }
}
