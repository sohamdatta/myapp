package com.example.loginapp.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebMvcConfig implements WebMvcConfigurer {

    private final SessionCtxResolver sessionResolver;

    WebMvcConfig(SessionCtxResolver sessionResolver) {
        this.sessionResolver = sessionResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(sessionResolver);
    }
}
