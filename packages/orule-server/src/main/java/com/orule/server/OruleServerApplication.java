package com.orule.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * orule-server 管控服务入口。
 *
 * <p>负责元数据、规则、制品、执行等核心域的 REST API 与后台编排。
 */
@SpringBootApplication
@ComponentScan(basePackages = {"com.orule.server", "com.orule.common"})
@EntityScan(basePackages = "com.orule.common.entity")
@EnableJpaRepositories(basePackages = "com.orule.server.repository")
public class OruleServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(OruleServerApplication.class, args);
    }
}
