package com.example.demo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;

/**
 * Spring Boot's open-session-in-view, except for live streams. It keeps a database connection
 * for the whole request, and a stream is a request that lasts as long as the tab is open: ten
 * open tabs took all ten connections of the pool, and every other request waited 30 s and
 * failed. Streams look up all they need before they start. Defining the interceptor here makes
 * Boot leave out its own, which covers every path.
 */
@Configuration
public class OpenSessionInViewConfig {

    static final String[] LIVE_STREAMS = {"/boards/*/events", "/share/*/events"};

    @Bean
    OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
        return new OpenEntityManagerInViewInterceptor();
    }

    @Bean
    WebMvcConfigurer openSessionInViewExceptLiveStreams(OpenEntityManagerInViewInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(new WebRequestHandlerInterceptorAdapter(interceptor))
                        .excludePathPatterns(LIVE_STREAMS);
            }
        };
    }
}
