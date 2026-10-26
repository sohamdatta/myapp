package com.example.loginapp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;

import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;

/** Which module an endpoint is held to: the parameter wins over the method, the method over the class. */
class SessionCtxResolverTest {

    @RequiresModule(Modules.LEAVE)
    static class Sample {
        void inherits(SessionCtx ctx) {}

        void optedOut(@RequiresModule(RequiresModule.NONE) SessionCtx ctx) {}

        @RequiresModule(Modules.PAYROLL)
        void methodLevel(SessionCtx ctx) {}

        @RequiresModule(Modules.PAYROLL)
        void parameterWins(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx) {}
    }

    static class Plain {
        void open(SessionCtx ctx) {}
    }

    private static String moduleOf(Class<?> type, String name) throws Exception {
        Method method = type.getDeclaredMethod(name, SessionCtx.class);
        return SessionCtxResolver.moduleOf(new MethodParameter(method, 0).withContainingClass(type));
    }

    @Test
    void theNearestAnnotationDecides() throws Exception {
        assertEquals(Modules.LEAVE, moduleOf(Sample.class, "inherits"));
        assertEquals(RequiresModule.NONE, moduleOf(Sample.class, "optedOut"));
        assertEquals(Modules.PAYROLL, moduleOf(Sample.class, "methodLevel"));
        assertEquals(Modules.ATTENDANCE, moduleOf(Sample.class, "parameterWins"));
        assertEquals(RequiresModule.NONE, moduleOf(Plain.class, "open"));
    }
}
