package com.haust.petcare.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * 全局异常处理器（@RestControllerAdvice）：把散落在各层的异常统一转成 Result 响应。
 * <p>
 * 这样 controller 里不用到处 try-catch，前端拿到的永远是结构一致的报文。
 * 关键取舍是区分「业务异常」与「系统异常」：前者是预期内的拒绝，只记一行 warn、
 * 把 message 原样返回；后者是未知故障，要 log.error 打完整堆栈便于排查，
 * 但对外只回「系统繁忙」并置 500，不把内部细节（SQL、类名、堆栈）泄露给调用方。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：预期内的拒绝，只 warn 记录 message、不打印堆栈，原样回给前端提示用户 */
    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常: {}", e.getMessage());
        return Result.fail(e.getMessage());
    }

    /** 参数校验失败（@Valid / 数据绑定）：把各字段的提示用「；」拼成一句返回，
     *  没有具体字段错误时兜底为「参数校验失败」 */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    public Result<Void> handleValidation(BindException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        return Result.fail(message.isEmpty() ? "参数校验失败" : message);
    }

    /** 缺少必填的请求参数（如未传 @RequestParam），回「缺少参数：xxx」 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissing(MissingServletRequestParameterException e) {
        return Result.fail("缺少参数：" + e.getParameterName());
    }

    /** 请求体读不了 / JSON 格式错误，统一回「请求体格式错误」，不暴露解析细节 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        return Result.fail("请求体格式错误");
    }

    /** 兜底：未被上面命中的未知异常。log.error 打印完整堆栈供排查，
     *  标 500 且只回「系统繁忙」，避免泄露内部实现；与业务异常形成对照 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public Result<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return Result.fail("系统繁忙，请稍后再试");
    }
}
