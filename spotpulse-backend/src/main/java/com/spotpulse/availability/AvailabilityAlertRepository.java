package com.spotpulse.availability;

import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.*;
import java.util.*;

public interface AvailabilityAlertRepository extends JpaRepository<AvailabilityAlert, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select a from AvailabilityAlert a where a.id = :id")
    Optional<AvailabilityAlert> lock(@Param("id") Long id);
    @Query("select a.room.id from AvailabilityAlert a where a.id = :id")
    Optional<Long> roomId(@Param("id") Long id);
    Optional<AvailabilityAlert> findByUserIdAndRoomIdAndStartTimeAndEndTimeAndActiveMarker(
            Long userId, Long roomId, LocalDateTime start, LocalDateTime end, String marker);
    // Each occurrence becomes a separate JDBC parameter. PostgreSQL needs a SQL type on
    // the null checks too, especially for LocalDateTime (the driver's unspecified timestamp OID).
    @Query("select a from AvailabilityAlert a where a.user.id = :userId and (cast(:roomId as Long) is null or a.room.id = :roomId) "
            + "and (cast(:start as LocalDateTime) is null or a.startTime = :start) "
            + "and (cast(:end as LocalDateTime) is null or a.endTime = :end) "
            + "and (:activeOnly = false or (a.activeMarker is not null and a.startTime > :now)) order by a.createdAt desc, a.id desc")
    Page<AvailabilityAlert> own(@Param("userId") Long userId, @Param("roomId") Long roomId,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end, @Param("activeOnly") boolean activeOnly,
            @Param("now") LocalDateTime now, Pageable page);
    @Modifying @Query("update AvailabilityAlert a set a.status = :expired, a.activeMarker = null, a.leaseToken = null "
            + "where a.activeMarker is not null and a.startTime <= :now")
    void expire(@Param("now") LocalDateTime now, @Param("expired") AvailabilityAlert.Status expired);
    @Query("select a.id from AvailabilityAlert a where a.activeMarker = 'ACTIVE' and a.nextAttemptAt <= :now order by a.nextAttemptAt, a.id")
    List<Long> due(@Param("now") Instant now, Pageable limit);
    @Modifying @Query("update AvailabilityAlert a set a.nextAttemptAt = :now where a.room.id = :roomId "
            + "and a.status = :status")
    void wake(@Param("roomId") Long roomId, @Param("now") Instant now, @Param("status") AvailabilityAlert.Status status);
}
