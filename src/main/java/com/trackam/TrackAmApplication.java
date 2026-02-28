package com.trackam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class TrackAmApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrackAmApplication.class, args);
    }
}
