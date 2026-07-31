package com.example.aispringboot.common;

import lombok.Data;

@Data
public class Result< T> {
    private String code;
    private String msg;
    private T data;


    public static <T> Result<T> ok(){
        Result<T> result = new Result<>();
        result.setCode(ResultCode.SUCCESS.getcode());
        result.setMsg(ResultCode.SUCCESS.getmsg());
        return result;
    }
    public static <T> Result<T> ok(T data){
        Result<T> result = ok();
        result.setData(data);
        return result;
    }
    public static <T> Result<T> error(){
        Result<T> result = new Result<>();
        result.setCode(ResultCode.ERROR.getcode());
        result.setMsg(ResultCode.ERROR.getmsg());
        return result;
    }
    public static <T>Result<T> error(String code,String msg,T data){
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        result.setData(data);
        return result;
    }
    public static <T> Result<T> error(String code,String msg){
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
}
