package com.mango.backend.qa.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.result.Result;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class QaExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(QaInputLimitException.class)
    public ResponseEntity<Result<Void>> handleInputLimit(QaInputLimitException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(HttpStatus.PAYLOAD_TOO_LARGE, exception.getMessage()));
    }

    @ExceptionHandler(DependencyException.class)
    public ResponseEntity<Result<Void>> handleDependency(DependencyException exception) {
        var status = switch (exception.failure().code()) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case SNAPSHOT_UNAVAILABLE, INDEX_PROFILE_MISMATCH -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED, AUTHENTICATION_FAILED, UPSTREAM_UNAVAILABLE,
                 INVALID_RESPONSE, DATABASE_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
            case NOT_FOUND, INVALID_ARGUMENT, INPUT_LIMIT_EXCEEDED,
                 BUDGET_EXHAUSTED, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        String message = switch (status) {
            case GATEWAY_TIMEOUT -> "问答依赖调用超时，请稍后重试";
            case SERVICE_UNAVAILABLE -> "知识资料暂不可用，请稍后重试";
            case BAD_GATEWAY -> "问答依赖服务异常，请稍后重试";
            default -> "问答服务处理失败，请稍后重试";
        };
        logger.error("问答依赖异常，类别=" + exception.failure().code());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(error(status, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleUnexpected(Exception exception) {
        // 异常消息和堆栈可能包含上游原始响应或用户内容，不在此边界输出。
        logger.error("问答接口未处理异常，类型=" + exception.getClass().getName());
        return ResponseEntity.internalServerError()
                .contentType(MediaType.APPLICATION_JSON)
                .body(error(HttpStatus.INTERNAL_SERVER_ERROR, "问答服务处理失败，请稍后重试"));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String message;
        if (exception instanceof HttpMessageNotReadableException unreadable) {
            message = unreadableMessage(unreadable);
        } else if (exception instanceof MethodArgumentNotValidException invalid) {
            var fieldError = invalid.getBindingResult().getFieldError();
            message = fieldError == null ? "请求参数校验未通过"
                    : fieldError.getField() + " " + fieldError.getDefaultMessage();
        } else if (exception instanceof AsyncRequestTimeoutException) {
            statusCode = HttpStatus.GATEWAY_TIMEOUT;
            message = "问答接口等待超时，请稍后重试";
        } else {
            message = switch (statusCode.value()) {
                case 400 -> "请求参数或格式有误";
                case 404 -> "请求的接口不存在";
                case 405 -> "请求方法不受支持";
                case 406 -> "接口仅支持 JSON 响应";
                case 413 -> "请求内容超过允许上限";
                case 415 -> "请求体必须使用 application/json";
                default -> statusCode.is5xxServerError() ? "问答服务处理失败，请稍后重试" : "请求未能处理";
            };
        }
        var responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
        return super.handleExceptionInternal(exception, error(statusCode, message), responseHeaders, statusCode, request);
    }

    private String unreadableMessage(HttpMessageNotReadableException exception) {
        if (!(exception.getCause() instanceof JsonMappingException mapping)) {
            return "请求体必须是合法的 JSON 对象，包含非空白的 question";
        }
        var field = new StringBuilder();
        for (var reference : mapping.getPath()) {
            var name = reference.getFieldName();
            if (name != null) {
                if (!name.equals("question") && !name.equals("history")
                        && !name.equals("role") && !name.equals("content")) {
                    return (field.isEmpty() ? "请求体" : field.toString()) + " 包含未知字段";
                }
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(name);
            } else if (reference.getIndex() >= 0) {
                field.append('[').append(reference.getIndex()).append(']');
            }
        }
        return (field.isEmpty() ? "请求体" : field.toString()) + " 的字段类型或取值有误";
    }

    private static Result<Void> error(HttpStatusCode status, String message) {
        var result = new Result<Void>();
        result.setCode(status.value());
        result.setMessage(message);
        return result;
    }
}
