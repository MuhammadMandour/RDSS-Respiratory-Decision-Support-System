package com.rdss.users;

import java.util.List;

import jakarta.validation.Valid;

import com.rdss.users.UserDtos.ClinicianDirectoryEntry;
import com.rdss.users.UserDtos.DepartmentRequest;
import com.rdss.users.UserDtos.DepartmentResponse;

import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Directory")
public class DirectoryController {

    private final DirectoryService service;

    public DirectoryController(DirectoryService service) {
        this.service = service;
    }

    @GetMapping("/clinicians")
    public List<ClinicianDirectoryEntry> clinicians() {
        return service.clinicians();
    }

    @GetMapping("/departments")
    public List<DepartmentResponse> departments() {
        return service.departments();
    }

    @PostMapping("/departments")
    @ResponseStatus(HttpStatus.CREATED)
    public DepartmentResponse create(@Valid @RequestBody DepartmentRequest request) {
        return service.createDepartment(request);
    }

    @PutMapping("/departments/{id}")
    public DepartmentResponse update(@PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return service.updateDepartment(id, request);
    }
}
