package com.haust.petcare.common;

import lombok.Data;

/**
 * 全局统一响应结构 {success, errorMsg, data}，所有 controller 的返回值都包装成它。
 * <p>
 * 前端只需看 success 决定走成功还是错误分支，不必依赖 HTTP 状态码或解析多种报文；
 * 成功时数据在 data、errorMsg 为 null，失败时反之。
 * 提供 ok / fail 静态工厂，避免各处手动 new 再逐字段赋值导致 success 与 errorMsg 不一致。
 */
@Data
public class Result<T> {

    /** 业务是否成功：true 成功、false 失败，前端先据此分支 */
    private boolean success;

    /** 失败时的错误提示，成功时为 null；业务异常会把它原样透传给用户 */
    private String errorMsg;

    /** 成功时返回的业务数据，泛型支持任意类型（订单、分页 Page、详情 Map 等） */
    private T data;

    /** 成功响应：success=true 并带上业务数据 */
    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.success = true;
        r.data = data;
        return r;
    }

    /** 成功但不返回数据（新增/删除/确认类接口），等价于 ok(null) */
    public static <T> Result<T> ok() {
        return ok(null);
    }

    /** 失败响应：success=false 并写入错误提示，供 GlobalExceptionHandler 组装错误结果 */
    public static <T> Result<T> fail(String message) {
        Result<T> r = new Result<>();
        r.success = false;
        r.errorMsg = message;
        return r;
    }
}
