package com.spotpulse.dto;

import com.spotpulse.model.enums.Role;
import lombok.*;
import jakarta.validation.constraints.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserCreateDto {
    @NotBlank
    @Size(max = 255)
    private String name;
    @NotBlank
    @Email
    @Size(max = 255)
    private String email;
    private Role role;
}
