package com.entwc.marketing.common;

/**
 * 业务异常，携带错误码与用户可读信息。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(4000, message);
    }

    public int getCode() {
        return code;
    }
}
