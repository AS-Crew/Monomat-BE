package io.github.ascrew.monomatbe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MonomatBeApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonomatBeApplication.class, args);
    }

}
