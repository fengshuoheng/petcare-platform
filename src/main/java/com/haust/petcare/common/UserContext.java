package com.haust.petcare.common;

/**
 * 当前登录用户上下文：用 ThreadLocal 在一次请求内透传 LoginUser。
 * <p>
 * AuthInterceptor.preHandle 校验 Redis token 后 set()，controller / service 通过
 * get() / userId() / requireRole() 取用，无需层层传参。
 * <p>
 * 必须在拦截器的 afterCompletion 里调用 clear()：Servlet 容器（Tomcat）用线程池复用工作线程，
 * ThreadLocal 绑在线程上不会随请求结束自动消失；若不清理，上一个请求残留的 LoginUser
 * 会被复用同一线程的下一个请求读到，造成「串号」——看到别人的数据甚至越权。
 * afterCompletion 在请求正常结束和抛异常时都会执行，能保证一定清理；
 * clear 用 remove() 而非 set(null)，连条目一起删掉，也避免 ThreadLocal 长期驻留的内存泄漏。
 */
public final class UserContext {

    /** 每个工作线程各自持有一份 LoginUser，线程间互不干扰 */
    private static final ThreadLocal<LoginUser> HOLDER = new ThreadLocal<>();

    /** 工具类，禁止实例化 */
    private UserContext() {
    }

    /** 由 AuthInterceptor 在 preHandle 校验 token 通过后写入 */
    public static void set(LoginUser user) {
        HOLDER.set(user);
    }

    /** 取当前请求的登录用户；未登录 / 未经过拦截器时为 null */
    public static LoginUser get() {
        return HOLDER.get();
    }

    /** 快捷取用户 id，取不到返回 null，供查询与归属判断使用 */
    public static Long userId() {
        LoginUser user = HOLDER.get();
        return user == null ? null : user.getId();
    }

    /** 角色校验：数值 >= 入参 role 才放行（0 用户 1 服务者 2 管理员），
     *  未登录或角色不足抛 BusinessException「没有权限执行该操作」 */
    public static void requireRole(int role) {
        LoginUser user = HOLDER.get();
        if (user == null || user.getRole() == null || user.getRole() < role) {
            throw new BusinessException("没有权限执行该操作");
        }
    }

    /** 请求结束时移除，务必在 afterCompletion 调用，防止线程池复用导致串号 */
    public static void clear() {
        HOLDER.remove();
    }
}
