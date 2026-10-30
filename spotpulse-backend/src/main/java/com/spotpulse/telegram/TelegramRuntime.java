package com.spotpulse.telegram;

import org.springframework.stereotype.Component;

@Component
public class TelegramRuntime {
    public record Bot(Long id, String username) { }
    private volatile Bot bot;
    public Bot bot() { return bot; }
    void ready(Long id, String username) { bot = new Bot(id, username); }
    void unavailable() { bot = null; }
}
