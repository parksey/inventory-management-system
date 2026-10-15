package com.logistics.inventory.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.logistics.inventory")
public class InventoryApplication {

    public static void main(final String[] args) {
        SpringApplication.run(InventoryApplication.class, args);
    }
}
