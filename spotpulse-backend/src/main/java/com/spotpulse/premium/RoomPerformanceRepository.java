package com.spotpulse.premium;

import com.spotpulse.model.Room;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;
import java.util.List;

public interface RoomPerformanceRepository extends Repository<Room, Long> {
    interface Counts {
        Long getRoomId();
        String getRoomName();
        long getConfirmed();
        long getCancelled();
    }
    @Query("""
        select r.id as roomId, r.name as roomName,
            sum(case when b.status = com.spotpulse.model.enums.BookingStatus.CONFIRMED then 1 else 0 end) as confirmed,
            sum(case when b.status = com.spotpulse.model.enums.BookingStatus.CANCELLED then 1 else 0 end) as cancelled
        from Room r left join r.bookings b on b.startTime >= :from and b.startTime < :until
        where r.space.id = :spaceId
        group by r.id, r.name order by r.id
        """)
    List<Counts> compare(@Param("spaceId") Long spaceId, @Param("from") LocalDateTime from, @Param("until") LocalDateTime until);
}
