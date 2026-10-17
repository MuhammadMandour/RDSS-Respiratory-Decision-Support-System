package com.rdss.diagnoses;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface Icd10CodeRepository extends JpaRepository<Icd10Code, String> {

    @Query("""
            select c from Icd10Code c
            where (:includeInactive = true or c.active = true)
              and (:billableOnly = false or c.billable = true)
              and (:q is null or upper(c.code) like :codePrefix or lower(c.description) like :text)
            order by c.code
            """)
    Page<Icd10Code> search(@Param("q") String q, @Param("codePrefix") String codePrefix, @Param("text") String text,
                           @Param("billableOnly") boolean billableOnly,
                           @Param("includeInactive") boolean includeInactive, Pageable pageable);
}
