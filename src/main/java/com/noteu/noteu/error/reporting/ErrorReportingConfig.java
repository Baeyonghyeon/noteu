package com.noteu.noteu.error.reporting;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@ConditionalOnProperty(prefix = "noteu.error-reporting", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(ErrorReportingProperties.class)
public class ErrorReportingConfig implements WebMvcConfigurer {
    private final ErrorReportingProperties properties;

    public ErrorReportingConfig(ErrorReportingProperties properties) {
        this.properties = properties;
    }

    @Bean(destroyMethod = "close")
    public ErrorEventReporter errorEventReporter() {
        return new ErrorEventReporter(properties);
    }

    @Bean
    public ErrorReportingInterceptor errorReportingInterceptor() {
        return new ErrorReportingInterceptor(properties, errorEventReporter());
    }

    @Bean
    public ErrorCaptureResolver errorCaptureResolver() {
        return new ErrorCaptureResolver();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(errorReportingInterceptor()).order(Integer.MIN_VALUE);
    }

    @Override
    public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
        resolvers.add(0, errorCaptureResolver());
    }
}
