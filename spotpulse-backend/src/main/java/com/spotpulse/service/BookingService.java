package com.spotpulse.service;

import com.spotpulse.dto.BookingCreateDto;
import com.spotpulse.dto.BookingDto;
import com.spotpulse.mapper.EntityMapper;
import com.spotpulse.model.Booking;
import com.spotpulse.model.Room;
import com.spotpulse.model.User;
import com.spotpulse.model.enums.BookingStatus;
import com.spotpulse.repository.BookingRepository;
import com.spotpulse.repository.RoomRepository;
import com.spotpulse.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingService {

    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    public Page<BookingDto> getAll(Long userId, Long roomId, BookingStatus status, Pageable pageable) {
        if (userId != null) {
            return bookingRepository.findByUserId(userId, pageable).map(EntityMapper::toDto);
        }
        if (roomId != null) {
            return bookingRepository.findByRoomId(roomId, pageable).map(EntityMapper::toDto);
        }
        if (status != null) {
            return bookingRepository.findByStatus(status, pageable).map(EntityMapper::toDto);
        }
        return bookingRepository.findAll(pageable).map(EntityMapper::toDto);
    }

    public BookingDto getById(Long id) {
        Booking booking = findOrThrow(id);
        return EntityMapper.toDto(booking);
    }

    @Transactional
    public BookingDto create(BookingCreateDto dto) {
        Room room = roomRepository.findById(dto.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room not found"));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "User not found"));

        Booking booking = EntityMapper.toEntity(dto, room, user);
        Booking saved = bookingRepository.save(booking);
        return EntityMapper.toDto(saved);
    }

    @Transactional
    public BookingDto update(Long id, BookingCreateDto dto) {
        Booking booking = findOrThrow(id);
        Room room = roomRepository.findById(dto.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room not found"));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "User not found"));

        EntityMapper.updateEntity(booking, dto, room, user);
        return EntityMapper.toDto(bookingRepository.save(booking));
    }

    @Transactional
    public void delete(Long id) {
        if (!bookingRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
        bookingRepository.deleteById(id);
    }

    private Booking findOrThrow(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }
}
