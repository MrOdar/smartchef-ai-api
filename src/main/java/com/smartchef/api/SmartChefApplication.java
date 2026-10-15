package com.smartchef.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class SmartChefApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartChefApplication.class, args);
    }
}
