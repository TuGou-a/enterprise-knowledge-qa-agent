package com.mango.backend.qa.api;

/** 仅由 HTTP 输入长度校验产生，消息中只包含字段名与服务端配置上限。 */
final class QaInputLimitException extends RuntimeException {
    QaInputLimitException(String message) {
        super(message);
    }
}
