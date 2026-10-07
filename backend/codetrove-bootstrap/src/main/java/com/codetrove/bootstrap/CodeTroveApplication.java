package com.codetrove.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(
    scanBasePackages = "com.codetrove",
    exclude = UserDetailsServiceAutoConfiguration.class
)
@EnableScheduling
public class CodeTroveApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeTroveApplication.class, args);
    }
}
