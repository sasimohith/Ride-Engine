package com.ridesharing.auth.repository;

import com.ridesharing.auth.model.User;
import com.ridesharing.shared.enums.DriverApprovalStatus;
import com.ridesharing.shared.enums.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Database access layer for the User entity.
 *
 * By extending JpaRepository<User, Long>, we get these methods FOR FREE:
 *   save(user)       → INSERT or UPDATE a user
 *   findById(id)     → SELECT * FROM users WHERE id = ?
 *   findAll()        → SELECT * FROM users
 *   deleteById(id)   → DELETE FROM users WHERE id = ?
 *   count()          → SELECT COUNT(*) FROM users
 *   existsById(id)   → SELECT EXISTS(SELECT 1 FROM users WHERE id = ?)
 *
 * We add custom methods below. Spring reads the method name
 * and auto-generates the SQL. No implementation needed.
 *
 * @Repository marks this as a Spring-managed data access component.
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * Finds a user by email address.
     * Used during LOGIN to verify credentials.
     *
     * Spring auto-generates: SELECT * FROM users WHERE email = ?
     *
     * Returns Optional<User> because the user might not exist.
     * Optional forces the caller to handle the "not found" case explicitly
     * instead of risking a NullPointerException.
     *
     * @param email the email to search for
     * @return Optional containing the user if found, empty if not
     */
    Optional<User> findByEmail(String email);

    /**
     * Checks if an email is already registered.
     * Used during REGISTRATION to prevent duplicate accounts.
     *
     * Spring auto-generates: SELECT EXISTS(SELECT 1 FROM users WHERE email = ?)
     *
     * @param email the email to check
     * @return true if email exists, false otherwise
     */
    boolean existsByEmail(String email);

    /**
     * Finds all drivers with a specific approval status.
     * Used by Admin Module to list pending/approved/rejected drivers.
     *
     * Spring auto-generates: SELECT * FROM users WHERE role = ? AND approval_status = ?
     */
    List<User> findByRoleAndApprovalStatus(UserRole role, DriverApprovalStatus approvalStatus);

    /**
     * Finds all users with a given role.
     * Used by Admin Module to list all drivers.
     *
     * Spring auto-generates: SELECT * FROM users WHERE role = ?
     */
    List<User> findByRole(UserRole role);
}
