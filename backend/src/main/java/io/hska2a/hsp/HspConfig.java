package io.hska2a.hsp;

import io.hska2a.config.HskProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HspConfig {
    @Bean
    public HspGateway hspGateway(HskProperties props) {
        if (props.getHsp().live()) return new McpHspGateway(props.getHsp().getMcpUrl());
        return new MockHspGateway(props, 2000);
    }

    @Bean
    public HspMerchantApiClient hspMerchantApiClient(HskProperties props) {
        return new HspMerchantApiClient(props.getHsp());
    }
}
