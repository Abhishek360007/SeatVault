package com.seatvault.domain;

/**
 * Application roles. Stored as a string in the DB (see {@code @Enumerated(STRING)})
 * so adding a role later never shifts ordinal values of existing rows.
 */
public enum Role {
    USER,
    ADMIN,
    /** Gate-check-in staff: may verify e-ticket QR codes but has no admin privileges. */
    SCANNER
}
