package com.rdss.system;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.rdss.system.SettingsService.SettingResponse;

import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/settings")
@Tag(name = "System settings")
public class SettingsController {

    public record UpdateSettingRequest(@NotNull @Size(max = 1000) String value) {
    }

    private final SettingsService service;

    public SettingsController(SettingsService service) {
        this.service = service;
    }

    @GetMapping
    public List<SettingResponse> list() {
        return service.list();
    }

    @PutMapping("/{key}")
    public SettingResponse update(@PathVariable String key, @Valid @RequestBody UpdateSettingRequest request) {
        return service.update(key, request.value());
    }
}
