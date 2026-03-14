package org.tanzu.broker.cf;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "broker.cf-api")
public record CfApiProperties(
    String baseUrl
) {}
