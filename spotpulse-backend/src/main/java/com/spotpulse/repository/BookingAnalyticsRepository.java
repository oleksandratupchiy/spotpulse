package com.spotpulse.repository;

import com.spotpulse.model.Booking;
import com.spotpulse.model.enums.BookingStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Two bounded scalar aggregations: never materializes Booking/User entities. */
public interface BookingAnalyticsRepository extends Repository<Booking, Long> {
    interface DailyCount {
        LocalDate getBookingDate();
        BookingStatus getStatus();
        long getCount();
    }

    interface PopularRoomCount {
        Long getRoomId();
        String getRoomName();
        Long getSpaceId();
        String getSpaceName();
        long getCount();
    }

    @Query("""
            select cast(b.startTime as LocalDate) as bookingDate, b.status as status, count(b) as count
            from Booking b
            where b.startTime >= :from and b.startTime < :until
            group by cast(b.startTime as LocalDate), b.status
            order by cast(b.startTime as LocalDate), b.status
            """)
    List<DailyCount> daily(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until);

    @Query("""
            select r.id as roomId, r.name as roomName, s.id as spaceId, s.name as spaceName, count(b) as count
            from Booking b join b.room r join r.space s
            where b.startTime >= :from and b.startTime < :until and b.status = :status
            group by r.id, r.name, s.id, s.name
            order by count(b) desc, r.id asc
            """)
    List<PopularRoomCount> popularRooms(@Param("from") LocalDateTime from, @Param("until") LocalDateTime until,
                                       @Param("status") BookingStatus status, Pageable pageable);
}
