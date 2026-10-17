package com.noteu.noteu;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import java.util.Arrays;

// Load only test configuration, even when a developer has a local application.yml with real DB settings.
@SpringBootTest(properties = "spring.config.location=classpath:application-test.yml")
class NoteuApplicationTests {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    public void contextLoads() throws Exception {
        if (applicationContext != null) {
            Arrays.stream(applicationContext.getBeanDefinitionNames())
                    .toList()
                    .forEach(bean -> System.out.println("bean : " + bean));
        }
    }
}
