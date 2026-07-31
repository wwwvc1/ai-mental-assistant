package com.example.aispringboot.common;

import com.example.aispringboot.exception.BusionessException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobarExceptionHandler {
//    处理校验异常
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<String> handleMethodArgumentNotValidException(MethodArgumentNotValidException e){
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        return Result.error(ResultCode.PARAM_ERROR.getcode(), message, ResultCode.PARAM_ERROR.getmsg());
    }
    @ExceptionHandler(BusionessException.class)
    public Result<?> handleBusionessException(BusionessException e){
        if (e.getData() != null){
            return Result.error(e.getCode(),e.getMsg(),e.getData());
        }
        return Result.error(e.getCode(),e.getMsg(), null);
    }
}
