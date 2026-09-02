package com.smartchef.api.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(

        @NotBlank(message = "Indica tu usuario o email")
        String username,

        @NotBlank(message = "La contrasena es obligatoria")
        String password) {
}
