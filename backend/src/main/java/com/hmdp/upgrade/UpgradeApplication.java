package com.hmdp.upgrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class UpgradeApplication {
    public static void main(String[] args) { SpringApplication.run(UpgradeApplication.class, args); }
}
