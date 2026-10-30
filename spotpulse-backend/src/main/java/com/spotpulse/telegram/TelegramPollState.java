package com.spotpulse.telegram;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity @Table(name = "telegram_poll_state") @Getter @Setter
public class TelegramPollState {
    @Id private Long botId;
    private long nextOffset;
    private String leaseOwner;
    private Instant leaseUntil;
}
