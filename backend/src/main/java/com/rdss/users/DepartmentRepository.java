package com.rdss.users;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

    @Query("select count(d) > 0 from Department d where lower(d.name) = lower(:name) and (:excludeId is null or d.id <> :excludeId)")
    boolean nameTaken(@Param("name") String name, @Param("excludeId") Long excludeId);
}
