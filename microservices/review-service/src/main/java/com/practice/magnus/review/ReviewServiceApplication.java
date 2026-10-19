package com.practice.magnus.review;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.Executors;

@SpringBootApplication(scanBasePackages = "com.practice.magnus")
public class ReviewServiceApplication {

    private static final Logger LOG = LoggerFactory.getLogger(ReviewServiceApplication.class);

    private final Integer connectionPoolSize;

    public ReviewServiceApplication(
            @Value("${spring.datasource.hikari.maximum-pool-size:10}") Integer connectionPoolSize) {
        this.connectionPoolSize = connectionPoolSize;
    }

    @Bean
    public Scheduler jdbcScheduler() {
        LOG.info("Creates a jdbcScheduler with connectionPoolSize = {}", connectionPoolSize);
        return Schedulers.fromExecutor(Executors.newFixedThreadPool(connectionPoolSize));
    }


    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(ReviewServiceApplication.class, args);

        String mysqlUri = ctx.getEnvironment().getProperty("spring.datasource.url");
        LOG.info("Connected to MySQL: {}", mysqlUri);

    }
}
