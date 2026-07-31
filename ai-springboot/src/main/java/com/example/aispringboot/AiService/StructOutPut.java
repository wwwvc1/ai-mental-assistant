package com.example.aispringboot.AiService;

public class StructOutPut {
    public record StreamChartSession(
            String sessionId,
            Long userHash,
            String initialMessage,
            Long startTime,
            Long expiryTime,
            Integer messageCount,
            String status
    ){

    }
}
