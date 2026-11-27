package com.haust.petcare.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;

/**
 * Knife4j / Swagger 接口文档配置，基于 springfox（对应 Spring Boot 2.x 的 javax.servlet 体系）。
 * <p>
 * 只扫描 com.haust.petcare.controller 包生成在线接口文档，启动后访问 /doc.html，
 * 便于前后端联调与演示；与业务代码解耦，改这里不影响任何接口逻辑。
 */
@Configuration
public class Knife4jConfig {

    /** 构建 Docket：Swagger 2 规范，只暴露 controller 包下的全部路径 */
    @Bean
    public Docket docket() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("com.haust.petcare.controller"))
                .paths(PathSelectors.any())
                .build();
    }

    /** 文档首页的标题、简介与版本号 */
    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("宠物上门服务预约交易平台 API")
                .description("预约、支付、派单、履约、售后与 AI 养护助手")
                .version("1.0.0")
                .build();
    }
}
