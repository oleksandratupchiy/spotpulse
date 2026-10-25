package com.spotpulse.repository;

import com.spotpulse.model.Booking;
import com.spotpulse.model.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;

public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    @Query("""
            select (count(b) > 0) from Booking b
            where b.room.id = :roomId and b.status = :status
              and b.startTime < :endTime and b.endTime > :startTime
              and (:excludedId is null or b.id <> :excludedId)
            """)
    boolean hasOverlap(@Param("roomId") Long roomId,
                       @Param("status") BookingStatus status,
                       @Param("startTime") LocalDateTime startTime,
                       @Param("endTime") LocalDateTime endTime,
                       @Param("excludedId") Long excludedId);


    Page<Booking> findByUserId(Long userId, Pageable pageable);

    Page<Booking> findByRoomId(Long roomId, Pageable pageable);

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);
}
