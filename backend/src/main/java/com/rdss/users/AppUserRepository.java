package com.rdss.users;

import java.util.Optional;

import com.rdss.common.security.Role;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    @Query("select u from AppUser u where lower(u.username) = lower(:username)")
    Optional<AppUser> findByUsernameIgnoreCase(@Param("username") String username);

    @Query("select count(u) > 0 from AppUser u where lower(u.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);

    @Query("select count(u) > 0 from AppUser u where lower(u.email) = lower(:email)")
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Query("select count(u) > 0 from AppUser u where lower(u.email) = lower(:email) and u.id <> :excludeId")
    boolean existsByEmailIgnoreCaseAndIdNot(@Param("email") String email, @Param("excludeId") Long excludeId);

    boolean existsByRole(Role role);

    long countByRoleAndEnabledTrue(Role role);

    @Query("""
            select u from AppUser u
            where (:role is null or u.role = :role)
              and (:q is null or lower(u.username) like :q or lower(u.fullName) like :q or lower(u.email) like :q)
            """)
    Page<AppUser> search(@Param("role") Role role, @Param("q") String likePattern, Pageable pageable);
}
