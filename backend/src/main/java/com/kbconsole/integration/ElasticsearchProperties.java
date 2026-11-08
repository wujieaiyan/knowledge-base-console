package com.kbconsole.integration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kb.elasticsearch")
public class ElasticsearchProperties {
    private boolean enabled = true;
    private String baseUrl = "http://localhost:9200";
    private String index = "kb_chunk";
    private int dimensions = 256;
    private int requestTimeoutSeconds = 10;
    private boolean refresh = true;
    private boolean verifyOnStartup = true;
    private boolean reindexOnStartup = true;
    private int bulkSize = 200;
}
