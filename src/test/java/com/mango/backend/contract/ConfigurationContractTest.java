package com.mango.backend.contract;

import com.mango.backend.contract.config.AgentProperties;
import com.mango.backend.contract.config.DatabaseProperties;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.net.URI;
import java.util.stream.Stream;

import static com.mango.backend.contract.config.AgentProperties.*;
import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationContractTest {
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void openValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @Test
    void validatesNestedPropertiesAndRequiresRealModeCredentials() {
        // 合成值仅供校验测试使用；不创建真实客户端或数据源。
        var deepseek = new DeepSeek("synthetic-deepseek-key", URI.create("https://example.test"),
                "test-model", 32768, 2048);
        var siliconflow = new SiliconFlow("synthetic-siliconflow-key", URI.create("https://example.test/v1"),
                "test-embedding", "test-rerank");
        var retrieval = new Retrieval(20, 20, 6, 12, 60, 300);
        var limits = new Limits(2000, 20, 4000, 12000, 512, 24000, 20000, 24000,
                32, 4000, 64000, 6, 12);
        var timeouts = new Timeouts(120000, 60000, 30000, 15000, 10000, 5000, 2000);
        var index = new Index("source-preserving-v1", "jieba-analysis-1.0.2", "builtin-v1", "none-v1", "", "");

        assertThat(validator.validate(new AgentProperties(deepseek, siliconflow, retrieval, limits, timeouts, index)))
                .isEmpty();

        var missingKeys = new AgentProperties(
                new DeepSeek("", deepseek.baseUrl(), deepseek.model(), 32768, 2048),
                new SiliconFlow(" ", siliconflow.baseUrl(), siliconflow.embeddingModel(), siliconflow.rerankModel()),
                retrieval, limits, timeouts, index);
        assertThat(validator.validate(missingKeys)).extracting(v -> v.getPropertyPath().toString())
                .contains("deepseek.apiKey", "siliconflow.apiKey");
        assertThat(deepseek.toString()).doesNotContain(deepseek.apiKey());
        assertThat(siliconflow.toString()).doesNotContain(siliconflow.apiKey());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ftp://example.test", "https://user:secret@example.test", "https://example.test?q=1",
            "https://example.test#fragment", "http:///missing-host"})
    void rejectsUnsafeModelEndpointsForBothProviders(String endpoint) {
        var uri = URI.create(endpoint);
        assertThat(validator.validate(new DeepSeek("synthetic", uri, "test-model", 32768, 2048)))
                .extracting(v -> v.getPropertyPath().toString()).contains("endpointValid");
        assertThat(validator.validate(new SiliconFlow("synthetic", uri, "test-embedding", "test-rerank")))
                .extracting(v -> v.getPropertyPath().toString()).contains("endpointValid");
    }

    @Test
    void rejectsOutputTokensThatConsumeTheWholeContextWindow() {
        assertThat(validator.validate(new DeepSeek("synthetic", URI.create("https://example.test"),
                "test-model", 2048, 2048))).extracting(v -> v.getPropertyPath().toString()).contains("windowValid");
    }

    @ParameterizedTest
    @MethodSource("invalidPools")
    void rejectsInconsistentCandidatePools(Retrieval retrieval) {
        assertThat(validator.validate(retrieval)).extracting(v -> v.getPropertyPath().toString()).contains("poolValid");
    }

    static Stream<Retrieval> invalidPools() {
        return Stream.of(new Retrieval(20, 20, 21, 12, 60, 300), new Retrieval(20, 41, 6, 12, 60, 300));
    }

    @Test
    void rejectsInputBudgetsThatCannotFitHistoryOrTheQuestion() {
        var historyTooLarge = new Limits(2000, 20, 12001, 12000, 512, 24000, 20000, 24000,
                32, 4000, 64000, 6, 12);
        var contextTooSmall = new Limits(2000, 20, 4000, 12000, 512, 13999, 20000, 24000,
                32, 4000, 64000, 6, 12);
        assertThat(validator.validate(historyTooLarge)).extracting(v -> v.getPropertyPath().toString()).contains("inputSizeValid");
        assertThat(validator.validate(contextTooSmall)).extracting(v -> v.getPropertyPath().toString()).contains("inputSizeValid");
    }

    @ParameterizedTest
    @MethodSource("timeoutsExceedingRequest")
    void rejectsAnySingleOperationTimeoutAboveTheRequestDeadline(Timeouts timeouts) {
        assertThat(validator.validate(timeouts)).extracting(v -> v.getPropertyPath().toString()).contains("deadlineValid");
    }

    static Stream<Timeouts> timeoutsExceedingRequest() {
        return Stream.of(
                new Timeouts(1000, 1001, 1000, 1000, 1000, 1000, 250),
                new Timeouts(1000, 1000, 1001, 1000, 1000, 1000, 250),
                new Timeouts(1000, 1000, 1000, 1001, 1000, 1000, 250),
                new Timeouts(1000, 1000, 1000, 1000, 1001, 1000, 250),
                new Timeouts(1000, 1000, 1000, 1000, 1000, 1001, 250),
                new Timeouts(1000, 1000, 1000, 1000, 1000, 1000, 1001));
    }

    @Test
    void enforcesConnectionPoolMinimumTimeout() {
        assertThat(validator.validate(new Timeouts(1000, 1000, 1000, 1000, 1000, 1000, 249)))
                .extracting(v -> v.getPropertyPath().toString()).contains("connectionMs");
    }

    @Test
    void rejectsOtherDatabaseProtocolsAndMissingCredentials() {
        assertThat(validator.validate(new DatabaseProperties("jdbc:h2:mem:test", "synthetic-user", "synthetic-password")))
                .extracting(v -> v.getPropertyPath().toString()).contains("url");
        assertThat(validator.validate(new DatabaseProperties("jdbc:postgresql://example.test/test", "", " ")))
                .extracting(v -> v.getPropertyPath().toString()).contains("username", "password");
        var properties = new DatabaseProperties("jdbc:postgresql://example.test/test", "synthetic-user", "synthetic-password");
        assertThat(validator.validate(properties)).isEmpty();
        assertThat(properties.toString()).doesNotContain(properties.url(), properties.username(), properties.password());
    }

    @Test
    void bindsOnlyExplicitDatabaseConfigurationWithoutStartingADataSource() {
        new ApplicationContextRunner().withUserConfiguration(DatabaseBinding.class)
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://example.test/test",
                        "spring.datasource.username=synthetic-user", "spring.datasource.password=synthetic-password")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(DatabaseProperties.class).doesNotHaveBean(DataSource.class);
                    var properties = context.getBean(DatabaseProperties.class);
                    assertThat(properties.url()).isEqualTo("jdbc:postgresql://example.test/test");
                    assertThat(properties.username()).isEqualTo("synthetic-user");
                    assertThat(properties.password()).isEqualTo("synthetic-password");
                });
    }

    @Test
    void failsIsolatedBindingWhenDatabasePasswordIsMissing() {
        new ApplicationContextRunner().withUserConfiguration(DatabaseBinding.class)
                .withPropertyValues("spring.datasource.url=jdbc:postgresql://example.test/test",
                        "spring.datasource.username=synthetic-user", "spring.datasource.password=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DatabaseProperties.class)
    static class DatabaseBinding {}
}
