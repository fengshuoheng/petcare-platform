package com.haust.petcare.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件配置：向容器注册分页插件。
 * <p>
 * 不挂载 PaginationInnerInterceptor 时 selectPage 不会真正改写 SQL 做物理分页；
 * 订单列表（pageByUser / pageByStaff / adminPage）等分页接口都依赖它。
 * 本项目只启用分页插件，并发正确性交给数据库唯一索引与条件 UPDATE，不依赖插件。
 */
@Configuration
public class MybatisPlusConfig {

    /** 构造拦截器链并挂载分页内部拦截器，交给 Spring 管理 */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        // 分页插件：拦截 selectPage，自动查 count 并拼接 LIMIT
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        return interceptor;
    }
}
