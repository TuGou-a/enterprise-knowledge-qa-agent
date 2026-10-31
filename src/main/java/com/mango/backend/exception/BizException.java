package com.mango.backend.exception;

import lombok.Getter;

/**
 * 业务异常（携带业务状态码）
 */
@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
