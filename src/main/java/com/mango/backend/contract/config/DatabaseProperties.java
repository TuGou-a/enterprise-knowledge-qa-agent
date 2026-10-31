package com.mango.backend.contract.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("spring.datasource")
public record DatabaseProperties(
        @NotBlank @Pattern(regexp = "jdbc:postgresql:.*", message = "必须使用 PostgreSQL JDBC 地址") String url,
        @NotBlank String username,
        @NotBlank String password) {
    @Override
    public String toString() {
        return "DatabaseProperties[credentials=<redacted>]";
    }
}
