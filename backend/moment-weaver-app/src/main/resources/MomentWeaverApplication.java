package com.momentweaver;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Moment Weaver 主启动类。
 * 包含 BFF 入口；其余业务模块通过 componentScan 自动发现。
 */
@SpringBootApplication(scanBasePackages = "com.momentweaver")
@EnableScheduling
@MapperScan("com.momentweaver.**.mapper")
public class MomentWeaverApplication {

    public static void main(String[] args) {
        SpringApplication.run(MomentWeaverApplication.class, args);
    }
}
