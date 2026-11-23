package com.occlet.issuance.worker;

import com.occlet.issuance.activities.HelloActivitiesImpl;
import com.occlet.issuance.activities.api.HelloActivities;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class WorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkerApplication.class, args);
    }

    @Bean
    HelloActivities helloActivities() {
        return new HelloActivitiesImpl();
    }
}
