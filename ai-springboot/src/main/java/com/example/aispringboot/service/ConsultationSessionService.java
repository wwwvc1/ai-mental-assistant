package com.example.aispringboot.service;

import ch.qos.logback.core.util.StringUtil;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.aispringboot.DTO.command.ConsultationSessionCreateDTO;
import com.example.aispringboot.entity.ConsultationMessage;
import com.example.aispringboot.entity.ConsultationSession;
import com.example.aispringboot.entity.User;
import com.example.aispringboot.mapper.ConsyltationSessionMapper;
import com.example.aispringboot.mapper.ConsultationMessageMapper;
import com.example.aispringboot.mapper.UserMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConsultationSessionService {
    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ConsyltationSessionMapper consultationSessionMapper;
    @Autowired
    private ConsultationMessageMapper consultationMessageMapper;

    public ConsultationSession createSession(Long userId , ConsultationSessionCreateDTO createDTO){
        User user = userMapper.selectById(userId);
        if (user != null){
            ConsultationSession session= ConsultationSession.builder()
                    .userId(userId)
                    .sessionTitle(createDTO.getSessionTitle())
                    .startedAt(LocalDateTime.now())
                    .build();
            if (StrUtil.isBlank(createDTO.getSessionTitle())){
                session.setSessionTitle("宁渡ai助手 - "+ DateUtil.format(LocalDateTime.now(),"MM-dd HH:mm"));
            }
            consultationSessionMapper.insert(session);
            return session;
        }
        return null;
    }

    /**
     * 获取用户的会话列表（分页）
     */
    public List<Map<String, Object>> getSessionList(Long userId, Integer pageNum, Integer pageSize) {
        // 查询该用户的所有会话，按开始时间倒序
        LambdaQueryWrapper<ConsultationSession> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ConsultationSession::getUserId, userId)
                .orderByDesc(ConsultationSession::getStartedAt);

        // 手动分页
        int offset = (pageNum - 1) * pageSize;
        queryWrapper.last("LIMIT " + offset + ", " + pageSize);

        List<ConsultationSession> sessions = consultationSessionMapper.selectList(queryWrapper);

        // 转换为前端需要的格式
        return sessions.stream().map(session -> {
            Map<String, Object> map = new HashMap<>();
            map.put("id", session.getId());
            map.put("sessionTitle", session.getSessionTitle());
            map.put("startedAt", session.getStartedAt() != null
                    ? session.getStartedAt().toString().replace("T", " ")
                    : "");

            // 获取最后一条消息
            LambdaQueryWrapper<ConsultationMessage> msgQuery = new LambdaQueryWrapper<>();
            msgQuery.eq(ConsultationMessage::getSessionId, session.getId())
                    .orderByDesc(ConsultationMessage::getCreatedAt)
                    .last("limit 1");
            ConsultationMessage lastMessage = consultationMessageMapper.selectOne(msgQuery);
            map.put("lastMessageContent", lastMessage != null ? lastMessage.getContent() : "");

            // 获取消息数量
            Long messageCount = consultationMessageMapper.selectCount(
                    new LambdaQueryWrapper<ConsultationMessage>().eq(ConsultationMessage::getSessionId, session.getId())
            );
            map.put("messageCount", messageCount != null ? messageCount.intValue() : 0);

            // 计算会话时长（分钟）
            long durationMinutes = 0;
            if (session.getStartedAt() != null) {
                java.time.Duration duration = java.time.Duration.between(session.getStartedAt(), LocalDateTime.now());
                durationMinutes = duration.toMinutes();
            }
            map.put("durationMinutes", (int) durationMinutes);

            return map;
        }).toList();
    }

    /**
     * 获取会话的情绪分析结果
     */
    public Map<String, Object> getEmotionAnalysis(Long sessionId) {
        ConsultationSession session = consultationSessionMapper.selectById(sessionId);
        Map<String, Object> result = new HashMap<>();

        if (session == null) {
            result.put("primaryEmotion", "中性");
            result.put("emotionScore", 50);
            result.put("isNegative", false);
            result.put("riskLevel", 0);
            result.put("suggestion", "暂无情绪数据");
            result.put("improvementSuggestions", List.of());
            result.put("riskDescription", "");
            return result;
        }

        // 尝试从 lastEmotionAnalysis 解析 JSON
        String emotionJson = session.getLastEmotionAnalysis();
        if (StrUtil.isNotBlank(emotionJson)) {
            try {
                JSONObject emotionObj = JSONUtil.parseObj(emotionJson);
                result.put("primaryEmotion", emotionObj.getStr("primaryEmotion", "中性"));
                result.put("emotionScore", emotionObj.getInt("emotionScore", 50));
                result.put("isNegative", emotionObj.getBool("isNegative", false));
                result.put("riskLevel", emotionObj.getInt("riskLevel", 0));
                result.put("suggestion", emotionObj.getStr("suggestion", ""));
                result.put("riskDescription", emotionObj.getStr("riskDescription", ""));

                // 处理 improvementSuggestions 数组
                Object suggestions = emotionObj.get("improvementSuggestions");
                if (suggestions instanceof List) {
                    result.put("improvementSuggestions", suggestions);
                } else {
                    result.put("improvementSuggestions", List.of());
                }

                return result;
            } catch (Exception e) {
                // JSON 解析失败，使用默认值
            }
        }

        // 默认返回中性情绪
        result.put("primaryEmotion", "中性");
        result.put("emotionScore", 50);
        result.put("isNegative", false);
        result.put("riskLevel", 0);
        result.put("suggestion", "情绪状态平稳");
        result.put("improvementSuggestions", List.of());
        result.put("riskDescription", "");
        return result;
    }
}
