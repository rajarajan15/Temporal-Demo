package com.occlet.issuance.worker;

import com.occlet.issuance.activities.CardActivitiesImpl;
import com.occlet.issuance.activities.HelloActivitiesImpl;
import com.occlet.issuance.activities.ReconciliationActivitiesImpl;
import com.occlet.issuance.activities.api.CardActivities;
import com.occlet.issuance.activities.api.HelloActivities;
import com.occlet.issuance.activities.api.ReconciliationActivities;
import com.occlet.issuance.adapters.http.HttpCardProcessorAdapter;
import com.occlet.issuance.adapters.http.HttpKycAdapter;
import com.occlet.issuance.adapters.http.HttpNotificationAdapter;
import com.occlet.issuance.adapters.http.JsonHttpClient;
import com.occlet.issuance.ports.PlatformRecordsPort;
import io.temporal.client.WorkflowClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
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

    @Bean
    JsonHttpClient mockHttpClient(
            @Value("${issuance.mocks.base-url}") String baseUrl,
            @Value("${issuance.mocks.request-timeout}") Duration requestTimeout) {
        return new JsonHttpClient(baseUrl, requestTimeout);
    }

    @Bean
    CardActivities cardActivities(JsonHttpClient client) {
        return new CardActivitiesImpl(
                new HttpKycAdapter(client),
                new HttpCardProcessorAdapter(client),
                new HttpNotificationAdapter(client));
    }

    @Bean
    PlatformRecordsPort platformRecords(WorkflowClient workflowClient) {
        return new TemporalPlatformRecords(workflowClient);
    }

    @Bean
    ReconciliationActivities reconciliationActivities(JsonHttpClient client, PlatformRecordsPort platformRecords) {
        return new ReconciliationActivitiesImpl(new HttpCardProcessorAdapter(client), platformRecords);
    }
}
