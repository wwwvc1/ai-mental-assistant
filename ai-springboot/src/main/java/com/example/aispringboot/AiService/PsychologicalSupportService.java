package com.example.aispringboot.AiService;

import com.example.aispringboot.DTO.command.ConsultationSessionCreateDTO;
import com.example.aispringboot.DTO.response.ConsultationMessageResponseDTO;
import com.example.aispringboot.entity.ConsultationMessage;
import com.example.aispringboot.entity.ConsultationSession;
import com.example.aispringboot.service.ConsultationMessageService;
import com.example.aispringboot.service.ConsultationSessionService;
import com.networknt.schema.SchemaLocation;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

@Service
public class PsychologicalSupportService {

    @Autowired
    @Qualifier("open-ai")
    private ChatClient chatClient;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private ConsultationSessionService consultationSessionService;
    @Autowired
    private ConsultationMessageService consultationMessageService;
    public StructOutPut.StreamChartSession startSession(Long userId , ConsultationSessionCreateDTO createDTO){
        ConsultationSession consultationSession = consultationSessionService.createSession(userId,createDTO);
        consultationMessageService.saveUserMessage(consultationSession.getId(),createDTO.getInitialMessage(),null);

        String  sessionId = "sessionId_"+consultationSession.getId();
        return new StructOutPut.StreamChartSession(
                sessionId,
                userId,
                createDTO.getInitialMessage(),
                System.currentTimeMillis(),
                System.currentTimeMillis()+864000000L,
                1,
                "active"

        );
    }
    public Flux<String> streamPsychologicalChat(String sessionId , String userMessage){
        return Flux.create(sink ->{
            Long dbsessionId = extractSessionId(sessionId);
            if (dbsessionId == null){
                sink.error(new RuntimeException("会话ID格式错误"));
                return;
            }
            boolean isInitialMessage = false;
            Integer messageCount = consultationMessageService.getMessageCountBySessionId(dbsessionId);
            if (messageCount >= 1){
                ConsultationMessageResponseDTO lastMessage = consultationMessageService.getLastMessageBySessionId(dbsessionId);
                if (lastMessage != null && lastMessage.getSenderType() == 1 && userMessage.equals(lastMessage.getContent())){
                    isInitialMessage = true;
                }
        }
            if (!isInitialMessage){
                consultationMessageService.saveUserMessage(dbsessionId,userMessage,null);
            }

//          生成对话记忆管理
            String conversationId = "conversation_"+ sessionId;

            List<Message> userMessages = new ArrayList<>();
            userMessages.add(new UserMessage(userMessage));
            chatMemory.add(conversationId,userMessages);
            Prompt prompt = new Prompt(List.of(
                    new SystemMessage(PromptMessage.PSYCHOLOGICAL_SUPPORT_SYSTEM_PROMPT)
            ));
//            用于存储AI完成的响应
            StringBuilder fullResponse = new StringBuilder();
//            使用ChatClient进行对话
            chatClient.prompt(prompt)
                    .user(userMessage)
                    .advisors(advisorSpec -> advisorSpec.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .stream()
                    .content()
                    .doOnNext(Fragment -> {
                        fullResponse.append(Fragment);
                        sink.next(Fragment);
                    })
                    .doOnComplete(() -> {
                        String completeRes= fullResponse.toString();
                        consultationMessageService.saveAimessage(dbsessionId,completeRes,"openai");
//                        添加AI回复到chatMemory
                        List<Message> Messages = new ArrayList<>();
                        Messages.add(new AssistantMessage(completeRes));
                        chatMemory.add(conversationId,Messages);
                        sink.complete();
                    })
                    .doOnError(error ->{sink.error(error);
                    })
                    .subscribe();
        });



    }

    public Long extractSessionId(String sessionId){
        if (sessionId != null && sessionId.startsWith("sessionId_")){
            String idStr = sessionId.substring("sessionId_".length());
            return Long.parseLong(idStr);
        }
        return null;
    }
}