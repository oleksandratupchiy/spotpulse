package com.spotpulse.repository;

import com.spotpulse.model.Booking;
import com.spotpulse.model.enums.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BookingRepository extends JpaRepository<Booking, Long> {

    Page<Booking> findByUserId(Long userId, Pageable pageable);

    Page<Booking> findByRoomId(Long roomId, Pageable pageable);

    Page<Booking> findByStatus(BookingStatus status, Pageable pageable);
}
