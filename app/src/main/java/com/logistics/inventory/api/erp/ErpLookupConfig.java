package com.logistics.inventory.api.erp;

import com.logistics.inventory.domain.erp.model.ErpLookupPolicy;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ErpLookupConfig {

    @Bean
    public ErpLookupPolicy erpLookupPolicy(@Value("${inventory.erp.lookup-backoff}") final List<Duration> delays) {
        return new ErpLookupPolicy(delays);
    }
}
