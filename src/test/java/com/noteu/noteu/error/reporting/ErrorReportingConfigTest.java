package com.noteu.noteu.error.reporting;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorReportingConfigTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ErrorReportingConfig.class);

    @Test
    void collectionIsOffByDefault() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ErrorReportingInterceptor.class);
            assertThat(context).doesNotHaveBean(ErrorCaptureResolver.class);
            assertThat(context).doesNotHaveBean(ErrorEventReporter.class);
        });
    }

    @Test
    void enabledConfigurationRegistersAllCollectionComponents() {
        runner.withPropertyValues("noteu.error-reporting.enabled=true",
                        "noteu.error-reporting.endpoint=http://127.0.0.1:9999/events",
                        "noteu.error-reporting.deploy-sha=test-sha")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ErrorReportingInterceptor.class);
                    assertThat(context).hasSingleBean(ErrorCaptureResolver.class);
                    assertThat(context).hasSingleBean(ErrorEventReporter.class);
                    assertThat(context.getBean(ErrorReportingProperties.class).getDeploySha())
                            .isEqualTo("test-sha");
                });
    }

    @Test
    void invalidEnabledConfigurationFailsEarly() {
        runner.withPropertyValues("noteu.error-reporting.enabled=true",
                        "noteu.error-reporting.queue-capacity=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void observerIsPrependedWithoutRemovingExistingResolvers() {
        ErrorReportingConfig configuration = new ErrorReportingConfig(new ErrorReportingProperties());
        HandlerExceptionResolver existing = (request, response, handler, exception) -> null;
        List<HandlerExceptionResolver> resolvers = new ArrayList<>(List.of(existing));
        configuration.extendHandlerExceptionResolvers(resolvers);
        assertThat(resolvers).hasSize(2);
        assertThat(resolvers.get(0)).isInstanceOf(ErrorCaptureResolver.class);
        assertThat(resolvers.get(1)).isSameAs(existing);
    }

    @Test
    void dockerStyleEnvironmentVariablesEnableAndBindCollector() {
        runner.withInitializer(context -> {
                    context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "NOTEU_ERRORREPORTING_ENABLED", "true",
                                "NOTEU_ERRORREPORTING_DEPLOYSHA", "docker-commit",
                                "NOTEU_ERRORREPORTING_QUEUECAPACITY", "8",
                                "NOTEU_ERRORREPORTING_REQUESTTIMEOUT", "1s")));
                    // SpringApplication installs this adapter before evaluating configuration conditions.
                    ConfigurationPropertySources.attach(context.getEnvironment());
                })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ErrorEventReporter.class);
                    ErrorReportingProperties properties = context.getBean(ErrorReportingProperties.class);
                    assertThat(properties.getDeploySha()).isEqualTo("docker-commit");
                    assertThat(properties.getQueueCapacity()).isEqualTo(8);
                    assertThat(properties.getRequestTimeout()).isEqualTo(java.time.Duration.ofSeconds(1));
                });
    }
}
