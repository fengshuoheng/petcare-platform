package com.haust.petcare;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot 启动类（放在根包 com.haust.petcare，保证各子包组件都能被扫描到）。
 * <p>
 * 三个注解的分工：
 * - @SpringBootApplication：开启自动配置与组件扫描；
 * - @EnableScheduling：启用定时任务，ScheduledJobs 里的「关闭超时未支付订单、释放过期锁槽、
 *   24h 自动确认、T+7 结算转可提现、每日生成时间槽」都靠它驱动；
 * - @MapperScan("com.haust.petcare.mapper")：批量把 mapper 接口注册成 Bean，
 *   省去给每个 Mapper 单独加 @Mapper。
 */
@SpringBootApplication
@EnableScheduling
@MapperScan("com.haust.petcare.mapper")
public class PetcareApplication {

    /** 入口：启动内嵌 Servlet 容器与 Spring 应用上下文 */
    public static void main(String[] args) {
        SpringApplication.run(PetcareApplication.class, args);
    }
}
