package com.example.loginapp.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Names the module an endpoint belongs to. A {@link com.example.loginapp.core.SessionCtx} argument is
 * refused with {@code module.off} when the caller's Organization does not have it. The nearest
 * annotation wins (parameter, then method, then class); {@link #NONE} opts an endpoint out of a
 * class-level default.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.PARAMETER})
public @interface RequiresModule {

    String NONE = "";

    String value();
}
