package com.example.aispringboot.util;

import cn.hutool.json.JSONUtil;
import com.example.aispringboot.common.Result;
import com.example.aispringboot.common.ResultCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public class ResponseUtil {
    public static void WriteError(HttpServletResponse response, ResultCode resultCode) {
        // 过滤器可能被执行两次或响应已由别处写出，避免重复写响应
        if (response.isCommitted()) {
            return;
        }
        int statusCode = switch (resultCode) {
            case UNAUTHORIZED, ACCESS_UNAUTHORIZED, TOKEN_INVALID, TOKEN_EXPIRED, TOKEN_BLOCKED ->
                    HttpStatus.UNAUTHORIZED.value();
            case TOKEN_ACCESS_FORBIDDEN -> HttpStatus.FORBIDDEN.value();
            default -> HttpStatus.BAD_REQUEST.value();
        };
        response.setStatus(statusCode);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        try(PrintWriter writer = response.getWriter()){
            String josnResponse = JSONUtil.toJsonStr(Result.error(resultCode.getcode(),resultCode.getmsg(),null));
            writer.print(josnResponse);
            writer.flush();
        }catch (IOException e){
            System.out.println("写入响应失败：" + e.getMessage());
        }
    }
}
