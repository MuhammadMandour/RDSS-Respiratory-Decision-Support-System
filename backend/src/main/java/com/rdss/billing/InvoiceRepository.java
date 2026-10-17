package com.rdss.billing;

import java.time.LocalDate;
import java.util.Collection;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @Query(value = "select nextval('invoice_number_seq')", nativeQuery = true)
    long nextInvoiceNumber();

    @Query("""
            select i from Invoice i
            where (:patientId is null or i.patientId = :patientId)
              and i.status in :statuses
              and (cast(:from as date) is null or i.issueDate >= :from)
              and (cast(:to as date) is null or i.issueDate <= :to)
              and (:q is null or lower(i.invoiceNumber) like :q)
            """)
    Page<Invoice> search(@Param("patientId") Long patientId, @Param("statuses") Collection<Invoice.Status> statuses,
                         @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("q") String q,
                         Pageable pageable);

    /** Clinician view: invoices for patients on their care team. */
    @Query("""
            select i from Invoice i
            where exists (select 1 from PatientCareTeamMember m where m.id.patientId = i.patientId and m.id.clinicianUserId = :me)
              and (:patientId is null or i.patientId = :patientId)
              and i.status in :statuses
              and (cast(:from as date) is null or i.issueDate >= :from)
              and (cast(:to as date) is null or i.issueDate <= :to)
              and (:q is null or lower(i.invoiceNumber) like :q)
            """)
    Page<Invoice> searchForClinician(@Param("me") Long me, @Param("patientId") Long patientId,
                                     @Param("statuses") Collection<Invoice.Status> statuses,
                                     @Param("from") LocalDate from, @Param("to") LocalDate to, @Param("q") String q,
                                     Pageable pageable);
}
