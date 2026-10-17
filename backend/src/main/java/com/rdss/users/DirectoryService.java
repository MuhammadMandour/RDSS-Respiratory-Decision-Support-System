package com.rdss.users;

import java.util.List;

import com.rdss.audit.AuditService;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.users.UserDtos.ClinicianDirectoryEntry;
import com.rdss.users.UserDtos.DepartmentRequest;
import com.rdss.users.UserDtos.DepartmentResponse;

import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Departments and the clinician directory used for scheduling and assignment. */
@Service
public class DirectoryService {

    private final DepartmentRepository departments;
    private final ClinicianProfileRepository profiles;
    private final AuditService audit;

    public DirectoryService(DepartmentRepository departments, ClinicianProfileRepository profiles,
                            AuditService audit) {
        this.departments = departments;
        this.profiles = profiles;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ClinicianDirectoryEntry> clinicians() {
        return profiles.findAllActive().stream()
                .filter(p -> p.getUser().getRole() == com.rdss.common.security.Role.CLINICIAN)
                .map(p -> new ClinicianDirectoryEntry(p.getUser().getId(), p.getUser().getFullName(), p.getTitle(),
                        p.getSpecialty(), p.getDepartment() == null ? null : p.getDepartment().getId(),
                        p.getDepartment() == null ? null : p.getDepartment().getName(),
                        p.isAcceptingAppointments()))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DepartmentResponse> departments() {
        return departments.findAll(Sort.by("name")).stream().map(DepartmentResponse::from).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DepartmentResponse createDepartment(DepartmentRequest req) {
        if (departments.nameTaken(req.name().trim(), null)) {
            throw new ConflictException("A department with this name already exists");
        }
        Department d = new Department(req.name().trim(), req.description());
        if (req.active() != null) {
            d.setActive(req.active());
        }
        d = departments.save(d);
        audit.success("CREATE", "Department", d.getId(), null);
        return DepartmentResponse.from(d);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public DepartmentResponse updateDepartment(Long id, DepartmentRequest req) {
        Department d = departments.findById(id).orElseThrow(() -> NotFoundException.of("Department", id));
        if (departments.nameTaken(req.name().trim(), id)) {
            throw new ConflictException("A department with this name already exists");
        }
        d.setName(req.name().trim());
        d.setDescription(req.description());
        if (req.active() != null) {
            d.setActive(req.active());
        }
        audit.success("UPDATE", "Department", id, null);
        return DepartmentResponse.from(d);
    }
}
