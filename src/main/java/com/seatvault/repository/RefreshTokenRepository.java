package com.seatvault.repository;

import com.seatvault.domain.RefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * Repository interface for managing RefreshToken entities (stored in PostgreSQL).
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);

    /**
     * Like {@link #findByToken} but takes a row lock ({@code SELECT ... FOR UPDATE}), so
     * two concurrent refreshes with the same token are serialized: the second one sees
     * the token already revoked and is treated as reuse instead of also rotating it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM RefreshToken t WHERE t.token = :token")
    Optional<RefreshToken> findByTokenForUpdate(@Param("token") String token);

    /** Revokes every still-active refresh token of one user in a single UPDATE. Returns rows changed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE RefreshToken t SET t.revoked = true WHERE t.user.id = :userId AND t.revoked = false")
    int revokeAllForUser(@Param("userId") Long userId);

    void deleteByUserEmail(String email);
    void deleteByToken(String token);
}
