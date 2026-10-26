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
import com.spotpulse.cache.ReadDataChanged;
import org.springframework.context.ApplicationEventPublisher;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.LocalDateTime;
import org.springframework.web.server.ResponseStatusException;

@Service
@Validated
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BookingService {

    private final ApplicationEventPublisher events;


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

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingDto create(@Valid BookingCreateDto dto) {
        Room room = roomRepository.findByIdForUpdate(dto.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        checkAvailability(dto, dto.getStatus() == null ? BookingStatus.CONFIRMED : dto.getStatus(), null);
        Booking booking = EntityMapper.toEntity(dto, room, user);
        Booking saved = bookingRepository.save(booking);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.BOOKINGS));
        return EntityMapper.toDto(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public BookingDto update(Long id, @Valid BookingCreateDto dto) {
        // Serialize updates to the same booking, including concurrent moves to different rooms.
        Booking booking = bookingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        Room room = roomRepository.findByIdForUpdate(dto.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        checkAvailability(dto, dto.getStatus() == null ? booking.getStatus() : dto.getStatus(), id);
        EntityMapper.updateEntity(booking, dto, room, user);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.BOOKINGS));
        return EntityMapper.toDto(bookingRepository.save(booking));
    }

    @Transactional
    public void delete(Long id) {
        if (!bookingRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
        bookingRepository.deleteById(id);
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.BOOKINGS));
    }

    private void checkAvailability(BookingCreateDto dto, BookingStatus status, Long excludedId) {
        // The room row is already locked until commit. Locking existing bookings alone
        // would not protect an empty room from two concurrent inserts.
        if (!dto.getStartTime().isBefore(dto.getEndTime()) || dto.getStartTime().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Booking must start in the future and end after it starts");
        }
        if (status == BookingStatus.CONFIRMED && bookingRepository.hasOverlap(
                dto.getRoomId(), BookingStatus.CONFIRMED, dto.getStartTime(), dto.getEndTime(), excludedId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Room is already booked for this time interval");
        }
    }

    private Booking findOrThrow(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }
}
