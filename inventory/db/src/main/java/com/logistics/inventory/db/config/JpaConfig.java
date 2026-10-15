package com.logistics.inventory.db.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan("com.logistics.inventory.domain")
@EnableJpaRepositories("com.logistics.inventory.db")
class JpaConfig {
}
