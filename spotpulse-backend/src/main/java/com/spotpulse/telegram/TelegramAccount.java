package com.spotpulse.telegram;

import com.spotpulse.model.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.Instant;

@Entity
@Table(name = "telegram_accounts")
@Getter @Setter
public class TelegramAccount {
    @Id
    private Long userId;
    @MapsId @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    @org.hibernate.annotations.OnDelete(action = org.hibernate.annotations.OnDeleteAction.CASCADE)
    private User user;
    @Column(unique = true)
    private Long chatId;
    private Long botId;
    @Column(unique = true, length = 64)
    private String codeHash;
    private Instant expiresAt;
    private Instant linkedAt;

    // Nullable for existing persistent accounts. Only an explicit false opts out.
    private Boolean bookingNotificationsEnabled;

    public boolean bookingNotificationsEnabled() {
        return !Boolean.FALSE.equals(bookingNotificationsEnabled);
    }

    void revokeCode() { codeHash = null; expiresAt = null; }
}
