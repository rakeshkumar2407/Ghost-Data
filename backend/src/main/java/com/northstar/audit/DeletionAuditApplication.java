package com.northstar.audit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DeletionAuditApplication {
    public static void main(String[] args) {
        SpringApplication.run(DeletionAuditApplication.class, args);
    }
}
