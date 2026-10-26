package com.example.loginapp.web;

import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;

/**
 * Supplies a {@link SessionCtx} controller argument from the Authorization header, applying
 * {@link RequiresModule}. Declare it as the first parameter so the caller is identified before a
 * request body is read, which is the order the explicit {@code Requests.session} calls had.
 */
@Component
public class SessionCtxResolver implements HandlerMethodArgumentResolver {

    private final AuthService auth;

    public SessionCtxResolver(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return SessionCtx.class.equals(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request,
                                  WebDataBinderFactory binders) {
        String authorization = request.getHeader("Authorization");
        String module = moduleOf(parameter);
        return module.isEmpty() ? Requests.session(auth, authorization) : Requests.session(auth, authorization, module);
    }

    static String moduleOf(MethodParameter parameter) {
        RequiresModule found = parameter.getParameterAnnotation(RequiresModule.class);
        if (found == null) {
            found = parameter.getMethodAnnotation(RequiresModule.class);
        }
        if (found == null) {
            found = parameter.getContainingClass().getAnnotation(RequiresModule.class);
        }
        return found == null ? RequiresModule.NONE : found.value();
    }
}
