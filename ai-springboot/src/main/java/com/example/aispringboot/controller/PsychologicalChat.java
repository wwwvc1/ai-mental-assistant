package com.example.aispringboot.controller;

import cn.hutool.json.JSONUtil;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.example.aispringboot.AiService.PsychologicalSupportService;
import com.example.aispringboot.AiService.StructOutPut;
import com.example.aispringboot.DTO.command.ConsultationSessionCreateDTO;
import com.example.aispringboot.DTO.command.ConsultationStreamDTO;
import com.example.aispringboot.common.Result;
import com.example.aispringboot.common.ResultCode;
import com.example.aispringboot.entity.ConsultationMessage;
import com.example.aispringboot.entity.ConsultationSession;
import com.example.aispringboot.service.ConsultationMessageService;
import com.example.aispringboot.service.ConsultationSessionService;
import com.example.aispringboot.util.JwtTokenUtil;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/psychological-chat")
public class PsychologicalChat {
    @Autowired
    private PsychologicalSupportService psychologicalSupportService;

    @Resource
    private ConsultationSessionService consultationSessionService;

    @Resource
    private ConsultationMessageService consultationMessageService;

    @PostMapping("/session/start")
    public Result<StructOutPut.StreamChartSession> startSession(@Valid @RequestBody ConsultationSessionCreateDTO createDTO){
//         获取当前用户id
        String token =  JwtTokenUtil.getCurrentToken() ;
        DecodedJWT jwt = JwtTokenUtil.veryfyToken( token);
        Long userId = jwt.getClaim("userId").asLong();
        StructOutPut.StreamChartSession session = psychologicalSupportService.startSession(userId,createDTO);
        return Result.ok(session);
    }

    @PostMapping(value = "/stream",produces = "text/event-stream")
    public Flux<ServerSentEvent<String>> SteamChart(@Valid @RequestBody ConsultationStreamDTO streamDTO){
        String token =  JwtTokenUtil.getCurrentToken() ;
        DecodedJWT jwt = JwtTokenUtil.veryfyToken( token);
        Long userId = jwt.getClaim("userId").asLong();

        if (userId == null){
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("error")
                    .data(JSONUtil.toJsonStr(Result.error(ResultCode.UNAUTHORIZED.getcode(),ResultCode.UNAUTHORIZED.getmsg(),"用户未登录")))
                    .build());
        }
        return psychologicalSupportService.streamPsychologicalChat(streamDTO.getSessionId(),streamDTO.getUserMessage())
                .map(Fragment ->{
                    return ServerSentEvent.<String>builder()
                        .event("message")
                        .data(JSONUtil.toJsonStr(Result.ok(Map.of("message",Fragment,"type","normal"))))
                        .build();
                })
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("done")
                        .data("{}")
                        .build()))
                .delayElements(Duration.ofMillis(50));

    }

    /**
     * 获取当前用户的会话列表（分页）
     */
    @GetMapping("/sessions")
    public Result<List<Map<String, Object>>> getSessionList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        String token = JwtTokenUtil.getCurrentToken();
        DecodedJWT jwt = JwtTokenUtil.veryfyToken(token);
        Long userId = jwt.getClaim("userId").asLong();

        List<Map<String, Object>> sessions = consultationSessionService.getSessionList(userId, pageNum, pageSize);
        return Result.ok(sessions);
    }

    /**
     * 获取会话的消息列表
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<ConsultationMessage>> getSessionMessages(@PathVariable Long sessionId) {
        String token = JwtTokenUtil.getCurrentToken();
        JwtTokenUtil.veryfyToken(token);

        List<ConsultationMessage> messages = consultationMessageService.getMessagesBySessionId(sessionId);
        return Result.ok(messages);
    }

    /**
     * 获取会话的情绪分析结果
     */
    @GetMapping("/session/{sessionId}/emotion")
    public Result<Map<String, Object>> getSessionEmotion(@PathVariable String sessionId) {
        String token = JwtTokenUtil.getCurrentToken();
        JwtTokenUtil.veryfyToken(token);

        // 从 sessionId 中提取数字 ID
        Long dbSessionId = null;
        if (sessionId != null && sessionId.startsWith("sessionId_")) {
            String idStr = sessionId.substring("sessionId_".length());
            try {
                dbSessionId = Long.parseLong(idStr);
            } catch (NumberFormatException e) {
                dbSessionId = null;
            }
        } else {
            try {
                dbSessionId = Long.parseLong(sessionId);
            } catch (NumberFormatException e) {
                dbSessionId = null;
            }
        }

        if (dbSessionId == null) {
            return Result.error("400", "无效的会话ID");
        }

        Map<String, Object> emotionData = consultationSessionService.getEmotionAnalysis(dbSessionId);
        return Result.ok(emotionData);
    }
}
