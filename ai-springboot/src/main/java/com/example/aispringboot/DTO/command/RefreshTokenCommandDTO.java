package com.example.aispringboot.DTO.command;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class RefreshTokenCommandDTO {
    @NotBlank(message = "refreshToken不能为空")
    private String refreshToken;
}
