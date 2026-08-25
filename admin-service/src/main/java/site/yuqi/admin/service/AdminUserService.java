package site.yuqi.admin.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import site.yuqi.admin.domain.AdminUser;
import site.yuqi.admin.domain.AdminUserRole;
import site.yuqi.admin.domain.AdminUserStatus;
import site.yuqi.admin.repo.AdminUserRepository;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class AdminUserService {

    private final AdminUserRepository repository;
    private final List<String> ownerEmails;

    public AdminUserService(
            AdminUserRepository repository,
            @Value("${portfolio.admin.owner-emails:}") String ownerEmailsCsv
    ) {
        this.repository = repository;
        this.ownerEmails = ownerEmailsCsv == null || ownerEmailsCsv.isBlank()
                ? List.of()
                : java.util.Arrays.stream(ownerEmailsCsv.split(","))
                    .map(AdminUser::normalizeEmail)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
    }

    @Transactional
    public Authorization authorize(String email, List<String> fallbackAllowedEmails) {
        String normalized = AdminUser.normalizeEmail(email);
        if (normalized.isBlank()) {
            return Authorization.denied();
        }

        // Ownership is deployment policy, not mutable application data. This
        // guarantees that an owner cannot accidentally suspend or demote the
        // last account capable of repairing admin access.
        if (isOwner(normalized)) {
            return Authorization.allowed(AdminUserRole.ADMIN, "owner_policy", true);
        }

        try {
            Optional<AdminUser> user = repository.findByEmailIgnoreCase(normalized);
            if (user.isPresent()) {
                AdminUser row = user.get();
                if (row.getStatus() != AdminUserStatus.ACTIVE) {
                    return Authorization.denied();
                }
                row.setLastLoginAt(Instant.now());
                return Authorization.allowed(row.getRole(), "admin_users", false);
            }
        } catch (Exception error) {
            log.warn("Admin user lookup failed for {}: {}", normalized, error.toString());
            // Authorization is fail-closed. The configured owner policy above
            // remains the recovery path when the registry is unavailable.
            return Authorization.denied();
        }

        boolean fallbackAllowed = fallbackAllowedEmails != null && fallbackAllowedEmails.stream()
                .map(AdminUser::normalizeEmail)
                .anyMatch(normalized::equals);
        return fallbackAllowed
                ? Authorization.allowed(AdminUserRole.ADMIN, "env_allowlist", false)
                : Authorization.denied();
    }

    @Transactional(readOnly = true)
    public List<AdminUser> list(String statusRaw, String roleRaw, int limit, int offset) {
        AdminUserStatus status = parseStatus(statusRaw, null);
        AdminUserRole role = parseRole(roleRaw, null);
        var page = PageRequest.of(offset / Math.max(1, limit), Math.max(1, Math.min(limit, 200)));
        if (status != null && role != null) {
            return repository.findByStatusAndRoleOrderByCreatedAtDesc(status, role, page);
        }
        if (status != null) {
            return repository.findByStatusOrderByCreatedAtDesc(status, page);
        }
        if (role != null) {
            return repository.findByRoleOrderByCreatedAtDesc(role, page);
        }
        return repository.findAllByOrderByCreatedAtDesc(page);
    }

    @Transactional
    public AdminUser upsert(String email,
                            String roleRaw,
                            String statusRaw,
                            String displayName,
                            String note,
                            String actor) {
        String normalized = AdminUser.normalizeEmail(email);
        if (normalized.isBlank() || !normalized.contains("@")) {
            throw new IllegalArgumentException("A valid email is required.");
        }
        AdminUserRole role = parseRole(roleRaw, AdminUserRole.EDITOR);
        AdminUserStatus status = parseStatus(statusRaw, AdminUserStatus.ACTIVE);
        enforceOwnerInvariant(normalized, role, status);

        AdminUser user = repository.findByEmailIgnoreCase(normalized).orElseGet(() -> AdminUser.builder()
                .email(normalized)
                .createdBy(actor)
                .build());
        user.setRole(role);
        user.setStatus(status);
        user.setDisplayName(blankToNull(displayName));
        user.setNote(blankToNull(note));
        user.setUpdatedBy(actor);
        return repository.save(user);
    }

    @Transactional
    public AdminUser updateStatus(UUID userId, String statusRaw, String note, String actor) {
        AdminUser user = repository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("Admin user not found: " + userId));
        AdminUserStatus status = parseStatus(statusRaw, AdminUserStatus.ACTIVE);
        enforceOwnerInvariant(user.getEmail(), user.getRole(), status);
        user.setStatus(status);
        if (note != null) user.setNote(blankToNull(note));
        user.setUpdatedBy(actor);
        return user;
    }

    private static AdminUserRole parseRole(String raw, AdminUserRole fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return AdminUserRole.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Invalid admin role: " + raw);
        }
    }

    private static AdminUserStatus parseStatus(String raw, AdminUserStatus fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return AdminUserStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            throw new IllegalArgumentException("Invalid admin status: " + raw);
        }
    }

    private static String blankToNull(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.isBlank() ? null : normalized;
    }

    public boolean isOwner(String email) {
        return ownerEmails.contains(AdminUser.normalizeEmail(email));
    }

    private void enforceOwnerInvariant(String email, AdminUserRole role, AdminUserStatus status) {
        if (!isOwner(email)) return;
        if (role != AdminUserRole.ADMIN || status != AdminUserStatus.ACTIVE) {
            throw new IllegalArgumentException("A configured owner must remain an active ADMIN.");
        }
    }

    public record Authorization(boolean allowed, AdminUserRole role, String source, boolean owner) {
        public static Authorization allowed(AdminUserRole role, String source, boolean owner) {
            if (role == null) {
                throw new IllegalArgumentException("An authorized administrator must have a role.");
            }
            return new Authorization(true, role, source, owner);
        }

        public static Authorization denied() {
            return new Authorization(false, null, null, false);
        }
    }
}
