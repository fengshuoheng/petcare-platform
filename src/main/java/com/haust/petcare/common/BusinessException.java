package com.haust.petcare.common;

/**
 * 业务异常：表示「请求本身不合法、被预期地拒绝」，而不是系统故障。
 * <p>
 * 例如时间槽被抢、订单状态不允许操作、验证码错误、权限不足等，message 可以直接给用户看；
 * 这类拒绝是可枚举的正常分支，所以 GlobalExceptionHandler 只打一行 warn、不打印堆栈
 * （打堆栈会刷屏并掩盖真正的 bug）。
 * <p>
 * 继承 RuntimeException 让 service 方法无需声明 throws；配合方法上的事务注解
 * （rollbackFor = Exception.class），抛出时事务照样回滚，例如抢槽失败时一并回滚刚插入的订单。
 */
public class BusinessException extends RuntimeException {

    public BusinessException(String message) {
        super(message);
    }
}
