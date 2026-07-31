package com.example.aispringboot.exception;

import lombok.Getter;

@Getter
public class BusionessException extends RuntimeException {
    private final String code ;
    private final String msg;
    private final Object data;

    public BusionessException(String message) {
        super(message);
        this.code = "BUSINESS_ERROR";
        this.msg = message;
        this.data = null;
    }
}
