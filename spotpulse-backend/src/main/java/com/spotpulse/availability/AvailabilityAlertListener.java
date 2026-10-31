package com.spotpulse.availability;

import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

@Component @RequiredArgsConstructor
public class AvailabilityAlertListener {
    private final AvailabilityAlertStore store;
    @TransactionalEventListener
    public void changed(AvailabilityChanged event) {
        // AFTER_COMMIT only schedules DB work; delivery never blocks the booking request.
        try { store.wake(event.roomId()); }
        catch (Exception ignored) { LoggerFactory.getLogger(getClass()).warn("Availability alert wake deferred; periodic recovery enabled"); }
    }
}
