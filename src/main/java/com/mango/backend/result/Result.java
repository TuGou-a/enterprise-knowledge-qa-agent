package com.mango.backend.result;

import lombok.Data;

import java.io.Serializable;

/**
 * 后端统一返回结果封装类
 *
 * <p>响应格式统一为：</p>
 * <pre>
 * {
 *   "code": 200,
 *   "message": "success",
 *   "data": {...}
 * }
 * </pre>
 *
 * <p>
 * 约定：
 * <ul>
 *   <li>code = 200 表示成功</li>
 *   <li>code ≠ 200 表示失败</li>
 * </ul>
 * </p>
 *
 * @param <T> 泛型参数，表示返回数据的具体类型
 */
@Data
public class Result<T> implements Serializable {

    /**
     * 业务状态码：200 表示成功，非 200 表示失败
     */
    private Integer code;

    /**
     * 提示信息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 返回成功结果（无数据）
     *
     * @param <T> 泛型参数
     * @return 成功的 Result 对象，code=200，message=\"success\"
     */
    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = 200;
        result.message = "success";
        return result;
    }

    /**
     * 返回成功结果（带数据，默认 message）
     *
     * @param <T>    泛型参数，数据的具体类型
     * @param object 要返回的数据对象
     * @return 成功的 Result 对象，code=200，message=\"success\"，data=传入对象
     */
    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<>();
        result.data = object;
        result.code = 200;
        result.message = "success";
        return result;
    }

    /**
     * 返回成功结果（带数据和自定义 message）
     *
     * @param <T>    泛型参数，数据的具体类型
     * @param object 要返回的数据对象
     * @param message 成功消息描述
     * @return 成功的 Result 对象，code=200，data=传入对象，message=成功消息
     */
    public static <T> Result<T> success(T object, String message) {
        Result<T> result = new Result<>();
        result.data = object;
        result.message = message;
        result.code = 200;
        return result;
    }

    /**
     * 返回失败结果
     *
     * @param <T> 泛型参数
     * @param message 错误信息描述
     * @return 失败的 Result 对象，code=500，message=错误信息
     */
    public static <T> Result<T> error(String message) {
        Result<T> result = new Result<>();
        result.message = message;
        result.code = 500;
        return result;
    }

}
