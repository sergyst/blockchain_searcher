package com.blockchainsearcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class BlockchainSearcherApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlockchainSearcherApplication.class, args);
    }
}
