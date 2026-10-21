package com.fundaccounting.trades;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class TradeServiceApplication {
    public static void main(String[] args) { SpringApplication.run(TradeServiceApplication.class, args); }

    @Bean
    Clock clock() { return Clock.systemUTC(); }
}
