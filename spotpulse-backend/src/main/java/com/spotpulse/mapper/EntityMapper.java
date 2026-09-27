package com.spotpulse.mapper;

import com.spotpulse.dto.*;
import com.spotpulse.model.*;

import java.util.Collections;
import java.util.List;

/**
 * Ручний маппер Entity ↔ DTO.
 * Статичний утиліт-клас, щоб уникнути залежності від MapStruct.
 */
public final class EntityMapper {

    private EntityMapper() {
    }

    // ───────────────────── Space ─────────────────────

    public static SpaceDto toDto(Space space) {
        SpaceDto dto = new SpaceDto();
        dto.setId(space.getId());
        dto.setName(space.getName());
        dto.setDescription(space.getDescription());
        dto.setAddress(space.getAddress());
        dto.setCity(space.getCity());
        dto.setImageUrl(space.getImageUrl());

        List<RoomDto> roomDtos = space.getRooms() != null
                ? space.getRooms().stream().map(EntityMapper::toDto).toList()
                : Collections.emptyList();
        dto.setRooms(roomDtos);

        return dto;
    }

    public static Space toEntity(SpaceCreateDto dto) {
        Space space = new Space();
        space.setName(dto.getName());
        space.setDescription(dto.getDescription());
        space.setAddress(dto.getAddress());
        space.setCity(dto.getCity());
        space.setImageUrl(dto.getImageUrl());
        return space;
    }

    public static void updateEntity(Space space, SpaceCreateDto dto) {
        space.setName(dto.getName());
        space.setDescription(dto.getDescription());
        space.setAddress(dto.getAddress());
        space.setCity(dto.getCity());
        space.setImageUrl(dto.getImageUrl());
    }

    // ───────────────────── Room ──────────────────────

    public static RoomDto toDto(Room room) {
        RoomDto dto = new RoomDto();
        dto.setId(room.getId());
        dto.setName(room.getName());
        dto.setCapacity(room.getCapacity());
        dto.setHourlyRate(room.getHourlyRate());
        dto.setSpaceId(room.getSpace().getId());
        dto.setSpaceName(room.getSpace().getName());
        return dto;
    }

    public static Room toEntity(RoomCreateDto dto, Space space) {
        Room room = new Room();
        room.setName(dto.getName());
        room.setCapacity(dto.getCapacity());
        room.setHourlyRate(dto.getHourlyRate());
        room.setSpace(space);
        return room;
    }

    public static void updateEntity(Room room, RoomCreateDto dto, Space space) {
        room.setName(dto.getName());
        room.setCapacity(dto.getCapacity());
        room.setHourlyRate(dto.getHourlyRate());
        room.setSpace(space);
    }

    // ───────────────────── User ──────────────────────

    public static UserDto toDto(User user) {
        UserDto dto = new UserDto();
        dto.setId(user.getId());
        dto.setName(user.getName());
        dto.setEmail(user.getEmail());
        dto.setRole(user.getRole());
        return dto;
    }

    public static User toEntity(UserCreateDto dto) {
        User user = new User();
        user.setName(dto.getName());
        user.setEmail(dto.getEmail());
        user.setRole(dto.getRole());
        return user;
    }

    public static void updateEntity(User user, UserCreateDto dto) {
        user.setName(dto.getName());
        user.setEmail(dto.getEmail());
        user.setRole(dto.getRole());
    }

    // ──────────────────── Booking ────────────────────

    public static BookingDto toDto(Booking booking) {
        BookingDto dto = new BookingDto();
        dto.setId(booking.getId());
        dto.setRoomId(booking.getRoom().getId());
        dto.setRoomName(booking.getRoom().getName());
        dto.setUserId(booking.getUser().getId());
        dto.setUserName(booking.getUser().getName());
        dto.setStartTime(booking.getStartTime());
        dto.setEndTime(booking.getEndTime());
        dto.setStatus(booking.getStatus());
        return dto;
    }

    public static Booking toEntity(BookingCreateDto dto, Room room, User user) {
        Booking booking = new Booking();
        booking.setRoom(room);
        booking.setUser(user);
        booking.setStartTime(dto.getStartTime());
        booking.setEndTime(dto.getEndTime());
        booking.setStatus(dto.getStatus() != null ? dto.getStatus() : com.spotpulse.model.enums.BookingStatus.CONFIRMED);
        return booking;
    }

    public static void updateEntity(Booking booking, BookingCreateDto dto, Room room, User user) {
        booking.setRoom(room);
        booking.setUser(user);
        booking.setStartTime(dto.getStartTime());
        booking.setEndTime(dto.getEndTime());
        if (dto.getStatus() != null) {
            booking.setStatus(dto.getStatus());
        }
    }
}
