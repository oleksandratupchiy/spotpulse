package com.spotpulse.availability;

import com.spotpulse.model.Room;
import com.spotpulse.model.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import java.time.*;

/** Persistent subscription and single-message delivery outbox. No Telegram credentials or chat IDs. */
@Entity @Getter @Setter @NoArgsConstructor
@Table(name = "availability_alerts", uniqueConstraints = @UniqueConstraint(name = "uq_availability_alert_active",
        columnNames = {"user_id", "room_id", "start_time", "end_time", "active_marker"}), indexes = {
        @Index(name = "ix_availability_alert_due", columnList = "status,next_attempt_at"),
        @Index(name = "ix_availability_alert_owner", columnList = "user_id,created_at"),
        @Index(name = "ix_availability_alert_room", columnList = "room_id,status")})
public class AvailabilityAlert {
    public enum Status { ACTIVE, READY, SENT, CANCELLED, EXPIRED }
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private User user;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "room_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE) private Room room;
    @Column(nullable = false) private LocalDateTime startTime;
    @Column(nullable = false) private LocalDateTime endTime;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private Status status = Status.ACTIVE;
    // NULL for terminal rows: both H2 and PostgreSQL allow multiple NULLs in a UNIQUE constraint.
    @Column(length = 8) private String activeMarker = "ACTIVE";
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant nextAttemptAt = Instant.now();
    private String leaseToken;
    private Instant notifiedAt;
    @Column(nullable = false) private int attempts;
    @Column(length = 40) private String deliveryReason;
    public boolean active() { return status == Status.ACTIVE || status == Status.READY; }
    public void finish(Status terminal) { status = terminal; activeMarker = null; leaseToken = null; }
}
