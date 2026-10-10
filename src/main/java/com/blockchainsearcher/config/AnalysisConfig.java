package com.blockchainsearcher.config;

import com.blockchainsearcher.abi.EventDefinition;
import com.blockchainsearcher.abi.EventRegistry;
import com.blockchainsearcher.analysis.InvolvementAnalyzer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration(proxyBeanMethods = false)
public class AnalysisConfig {

    @Bean
    EventRegistry eventRegistry(BlockchainProperties properties) {
        EventRegistry registry = EventRegistry.withBuiltIns();
        for (String signature : properties.extraEvents()) {
            EventDefinition definition = registry.register(EventRegistry.CUSTOM, signature);
            log.info("Registered extra event {} (topic0 {})", definition.canonicalSignature(), definition.topic0());
        }
        return registry;
    }

    @Bean
    InvolvementAnalyzer involvementAnalyzer(EventRegistry eventRegistry) {
        return new InvolvementAnalyzer(eventRegistry);
    }
}
