package com.elevator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * ElevatorApplication — Spring Boot entry point.
 *
 * @SpringBootApplication is a convenience annotation that combines:
 *   - @Configuration       → marks this class as a source of bean definitions
 *   - @EnableAutoConfiguration → Spring Boot auto-wires dependencies
 *   - @ComponentScan       → scans sub-packages for @Component, @Service, etc.
 *
 * @EnableScheduling activates the @Scheduled annotation support,
 * which we use to run our simulation tick loop every second.
 *
 * Real-world relevance:
 *   Elevator controllers run embedded firmware tick loops. We replicate
 *   that with Spring's scheduler — a clean abstraction over Java threads.
 */
@SpringBootApplication
@EnableScheduling
public class ElevatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ElevatorApplication.class, args);
    }
}
