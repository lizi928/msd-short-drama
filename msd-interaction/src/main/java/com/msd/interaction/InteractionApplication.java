package com.msd.interaction;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 互动服务启动类。
 */
@SpringBootApplication
@MapperScan("com.msd.interaction.mapper")
@EnableFeignClients  //打通微服务间的通信
@EnableScheduling    //驱动应用内部的定时任务
public class InteractionApplication {

    /**
     * 启动服务。
     */
    public static void main(String[] args) {
        SpringApplication.run(InteractionApplication.class, args);
    }
}
