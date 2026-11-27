package io.hska2a;

import io.hska2a.config.HskProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(HskProperties.class)
public class HskA2aApplication {
    public static void main(String[] args) {
        SpringApplication.run(HskA2aApplication.class, args);
    }
}
