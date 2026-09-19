package com.zifang.z.schedule.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Z-Job Admin 启动类
 */
@SpringBootApplication
@EnableScheduling
public class ZScheduleAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZScheduleAdminApplication.class, args);
    }
}
