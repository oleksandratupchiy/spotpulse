package com.spotpulse.dto;

import com.spotpulse.model.enums.Role;
import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateDto {
    private String name;
    private String email;
    private Role role;
}
