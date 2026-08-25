package site.yuqi.admin.repo;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import site.yuqi.admin.domain.AdminUser;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.domain.AdminUserStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AdminUserRepository extends JpaRepository<AdminUser, UUID> {

    Optional<AdminUser> findByEmailIgnoreCase(String email);

    List<AdminUser> findAllByOrderByCreatedAtDesc(Pageable pageable);

    List<AdminUser> findByStatusOrderByCreatedAtDesc(AdminUserStatus status, Pageable pageable);

    List<AdminUser> findByRoleOrderByCreatedAtDesc(AdminUserRole role, Pageable pageable);

    List<AdminUser> findByStatusAndRoleOrderByCreatedAtDesc(
            AdminUserStatus status, AdminUserRole role, Pageable pageable);
}
