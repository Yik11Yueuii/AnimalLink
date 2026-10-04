package org.animallink.adoption;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AdoptionServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(AdoptionServiceApplication.class, args);
    }
}
