package com.rdss.system;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
@Tag(name = "System")
public class SystemController {

    private final SystemStatusService statusService;

    public SystemController(SystemStatusService statusService) {
        this.statusService = statusService;
    }

    @GetMapping("/status")
    @SecurityRequirements
    @Operation(summary = "Public status summary of the application and its dependencies")
    public SystemStatusResponse status() {
        return statusService.status();
    }
}
