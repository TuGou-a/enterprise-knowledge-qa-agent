package com.mango.backend.qa.api;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mango.backend.contract.error.DependencyException;
import com.mango.backend.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class QaExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(QaExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handle(Exception exception, HttpServletRequest request) throws Exception {
        // 媒体类型和方法错误可能发生在匹配 Controller 之前，因此按端点路径限定处理范围。
        if (!request.getRequestURI().equals(request.getContextPath() + "/api/qa")) {
            throw exception;
        }

        HttpStatusCode status;
        var headers = new HttpHeaders();
        String message;
        if (exception instanceof QaInputLimitException inputLimit) {
            status = HttpStatus.PAYLOAD_TOO_LARGE;
            message = inputLimit.getMessage();
        } else if (exception instanceof DependencyException dependency) {
            status = switch (dependency.failure().code()) {
                case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
                case SNAPSHOT_UNAVAILABLE, INDEX_PROFILE_MISMATCH -> HttpStatus.SERVICE_UNAVAILABLE;
                case RATE_LIMITED, AUTHENTICATION_FAILED, UPSTREAM_UNAVAILABLE,
                     INVALID_RESPONSE, DATABASE_UNAVAILABLE -> HttpStatus.BAD_GATEWAY;
                case NOT_FOUND, INVALID_ARGUMENT, INPUT_LIMIT_EXCEEDED,
                     BUDGET_EXHAUSTED, INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
            };
            message = switch (status.value()) {
                case 504 -> "问答依赖调用超时，请稍后重试";
                case 503 -> "知识资料暂不可用，请稍后重试";
                case 502 -> "问答依赖服务异常，请稍后重试";
                default -> "问答服务处理失败，请稍后重试";
            };
            log.error("问答依赖异常，类别={}", dependency.failure().code());
        } else if (exception instanceof HttpMessageNotReadableException unreadable) {
            status = HttpStatus.BAD_REQUEST;
            message = unreadableMessage(unreadable);
        } else if (exception instanceof MethodArgumentNotValidException invalid) {
            status = HttpStatus.BAD_REQUEST;
            var fieldError = invalid.getBindingResult().getFieldError();
            message = fieldError == null ? "请求参数校验未通过" : fieldError.getField() + " 不能为空或空白";
        } else if (exception instanceof AsyncRequestTimeoutException) {
            status = HttpStatus.GATEWAY_TIMEOUT;
            message = "问答接口等待超时，请稍后重试";
        } else if (exception instanceof ErrorResponse mvcError) {
            status = mvcError.getStatusCode();
            headers.putAll(mvcError.getHeaders());
            message = switch (status.value()) {
                case 400 -> "请求参数或格式有误";
                case 404 -> "请求的接口不存在";
                case 405 -> "请求方法不受支持";
                case 406 -> "接口仅支持 JSON 响应";
                case 413 -> "请求内容超过允许上限";
                case 415 -> "请求体必须使用 application/json";
                default -> status.is5xxServerError() ? "问答服务处理失败，请稍后重试" : "请求未能处理";
            };
        } else {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
            message = "问答服务处理失败，请稍后重试";
            // 异常消息和堆栈可能包含上游原始响应或用户内容，不在此边界输出。
            log.error("问答接口未处理异常，类型={}", exception.getClass().getName());
        }

        var result = new Result<Void>();
        result.setCode(status.value());
        result.setMessage(message);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(result, headers, status);
    }

    private String unreadableMessage(HttpMessageNotReadableException exception) {
        if (!(exception.getCause() instanceof JsonMappingException mapping)) {
            return "请求体必须是合法的 JSON 对象，包含非空白的 question";
        }
        boolean unknownField = mapping instanceof UnrecognizedPropertyException;
        var path = mapping.getPath();
        var field = new StringBuilder();
        for (int index = 0; index < path.size(); index++) {
            if (unknownField && index == path.size() - 1) {
                break;
            }
            var reference = path.get(index);
            var name = reference.getFieldName();
            if (name != null) {
                if (!name.equals("question") && !name.equals("history")
                        && !name.equals("role") && !name.equals("content")) {
                    return "请求体包含未知字段";
                }
                if (!field.isEmpty()) {
                    field.append('.');
                }
                field.append(name);
            } else if (reference.getIndex() >= 0) {
                field.append('[').append(reference.getIndex()).append(']');
            }
        }
        String location = field.isEmpty() ? "请求体" : field.toString();
        return location + (unknownField ? " 包含未知字段" : " 的字段类型或取值有误");
    }
}
