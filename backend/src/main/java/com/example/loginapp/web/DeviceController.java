package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Modules;
import com.example.loginapp.core.DeviceService;

/** Registering biometric devices and seeing what they sent. */
@RestController
@RequestMapping("/api/org/attendance/devices")
public class DeviceController {

    private final AuthService auth;
    private final DeviceService devices;

    public DeviceController(AuthService auth, DeviceService devices) {
        this.auth = auth;
        this.devices = devices;
    }

    @GetMapping
    public List<Map<String, Object>> devices(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx) {
        return devices.devices(ctx);
    }

    @PostMapping
    public Map<String, Object> register(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                        @RequestBody DeviceService.DeviceInput request) {
        return devices.save(ctx, null, request);
    }

    @PutMapping("/{id}")
    public Map<String, Object> change(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                      @PathVariable("id") String id, @RequestBody DeviceService.DeviceInput request) {
        return devices.save(ctx, Requests.id(id), request);
    }

    @GetMapping("/{id}/log")
    public List<Map<String, Object>> log(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                         @PathVariable("id") String id) {
        return devices.log(ctx, Requests.id(id));
    }
}
