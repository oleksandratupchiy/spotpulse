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
import org.springframework.security.access.prepost.PreAuthorize;
import com.spotpulse.security.AccessPolicy;
import com.spotpulse.cache.ReadDataChanged;
import com.spotpulse.telegram.BookingNotification;
import com.spotpulse.availability.AvailabilityChanged;
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
    private final AccessPolicy access;

    private final ApplicationEventPublisher events;


    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;

    @PreAuthorize("isAuthenticated()")
    public Page<BookingDto> getAll(Long userId, Long roomId, BookingStatus status, Pageable pageable) {
        if (!access.admin()) {
            if (userId != null && !userId.equals(access.userId())) throw new org.springframework.security.access.AccessDeniedException("Only your own bookings can be requested");
            return bookingRepository.ownBookings(access.userId(), roomId, status, pageable).map(EntityMapper::toDto);
        }
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

    @PreAuthorize("@access.canReadBooking(#id)")
    public BookingDto getById(Long id) {
        Booking booking = findOrThrow(id);
        return EntityMapper.toDto(booking);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("isAuthenticated()")
    public BookingDto create(@Valid BookingCreateDto dto) {
        if (!access.admin()) {
            if (dto.getUserId() != null && !dto.getUserId().equals(access.userId()))
                throw new org.springframework.security.access.AccessDeniedException("Cannot book on another user's behalf");
            if (dto.getStatus() != null && dto.getStatus() != BookingStatus.CONFIRMED)
                throw new org.springframework.security.access.AccessDeniedException("Use the cancellation operation to cancel a booking");
            dto.setUserId(access.userId());
        } else if (dto.getUserId() == null) dto.setUserId(access.userId());
        Room room = roomRepository.findByIdForUpdate(dto.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        checkAvailability(dto, dto.getStatus() == null ? BookingStatus.CONFIRMED : dto.getStatus(), null);
        Booking booking = EntityMapper.toEntity(dto, room, user);
        Booking saved = bookingRepository.save(booking);
        if (saved.getStatus() == BookingStatus.CONFIRMED) events.publishEvent(BookingNotification.of(saved));
        if (saved.getStatus() == BookingStatus.CONFIRMED) events.publishEvent(new AvailabilityChanged(room.getId()));
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.BOOKINGS));
        return EntityMapper.toDto(saved);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("hasRole('ADMIN')")
    public BookingDto update(Long id, @Valid BookingCreateDto dto) {
        // Serialize updates to the same booking, including concurrent moves to different rooms.
        Booking booking = bookingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        if (dto.getUserId() == null) dto.setUserId(booking.getUser().getId());
        Room room = roomRepository.findByIdForUpdate(dto.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));
        User user = userRepository.findById(dto.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));

        BookingStatus previousStatus = booking.getStatus();
        Long previousRoomId = booking.getRoom().getId();
        Long previousUserId = booking.getUser().getId();
        checkAvailability(dto, dto.getStatus() == null ? booking.getStatus() : dto.getStatus(), id);
        EntityMapper.updateEntity(booking, dto, room, user);
        if (previousStatus == BookingStatus.CONFIRMED) events.publishEvent(new AvailabilityChanged(previousRoomId));
        if (booking.getStatus() == BookingStatus.CONFIRMED && (!previousRoomId.equals(room.getId()) || previousStatus != BookingStatus.CONFIRMED))
            events.publishEvent(new AvailabilityChanged(room.getId()));
        if (booking.getStatus() != previousStatus
                || (booking.getStatus() == BookingStatus.CONFIRMED && !user.getId().equals(previousUserId)))
            events.publishEvent(BookingNotification.of(booking));
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.BOOKINGS));
        return EntityMapper.toDto(bookingRepository.save(booking));
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public void delete(Long id) {
        Booking booking = bookingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        roomRepository.findByIdForUpdate(booking.getRoom().getId()).orElseThrow();
        if (booking.getStatus() == BookingStatus.CONFIRMED) events.publishEvent(new AvailabilityChanged(booking.getRoom().getId()));
        bookingRepository.delete(booking);
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

    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public Page<BookingDto> managed(Pageable pageable) {
        return (access.admin() ? bookingRepository.findAll(pageable)
                : bookingRepository.findByRoomSpaceOwnerId(access.userId(), pageable)).map(EntityMapper::toDto);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @PreAuthorize("@access.canReadBooking(#id)")
    public BookingDto cancel(Long id) {
        Booking booking = bookingRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
        roomRepository.findByIdForUpdate(booking.getRoom().getId()).orElseThrow();
        // An administrator may have reassigned the booking while this request waited for locks.
        if (!access.canReadBooking(id)) throw new org.springframework.security.access.AccessDeniedException("Booking access changed");
        if (booking.getStatus() == BookingStatus.CANCELLED) return EntityMapper.toDto(booking);
        if (!booking.getStartTime().isAfter(LocalDateTime.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Started bookings cannot be cancelled");
        booking.setStatus(BookingStatus.CANCELLED);
        events.publishEvent(new AvailabilityChanged(booking.getRoom().getId()));
        events.publishEvent(BookingNotification.of(booking));
        events.publishEvent(new ReadDataChanged(ReadDataChanged.Kind.BOOKINGS));
        return EntityMapper.toDto(bookingRepository.save(booking));
    }

    private Booking findOrThrow(Long id) {
        return bookingRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }
}
