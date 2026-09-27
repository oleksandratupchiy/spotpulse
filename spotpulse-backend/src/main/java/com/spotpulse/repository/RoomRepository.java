package com.spotpulse.repository;

import com.spotpulse.model.Room;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {

    Page<Room> findBySpaceId(Long spaceId, Pageable pageable);
}
