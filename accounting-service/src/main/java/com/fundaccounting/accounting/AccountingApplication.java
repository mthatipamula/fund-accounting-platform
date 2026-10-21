package com.fundaccounting.accounting;

import com.fundaccounting.grpc.marketdata.v1.MarketDataServiceGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
public class AccountingApplication {
    public static void main(String[] args) { SpringApplication.run(AccountingApplication.class, args); }

    @Bean
    Clock clock() { return Clock.systemUTC(); }

    /** gRPC channel to reference-data-service (target from spring.grpc.client.channel.refdata). */
    @Bean
    MarketDataServiceGrpc.MarketDataServiceBlockingStub marketData(GrpcChannelFactory channels) {
        return MarketDataServiceGrpc.newBlockingStub(channels.createChannel("refdata"));
    }
}
