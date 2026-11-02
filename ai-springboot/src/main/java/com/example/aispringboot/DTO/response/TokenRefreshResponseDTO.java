package com.example.aispringboot.DTO.response;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenRefreshResponseDTO {
    private String token;
    private String refreshToken;
}
