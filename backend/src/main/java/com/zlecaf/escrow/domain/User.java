package com.zlecaf.escrow.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "first_name", length = 100)
    private String firstName;

    @Column(name = "last_name", length = 100)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private Role role;

    /**
     * Version de jeton (Story 1.6, NFR-P5). Chaque JWT émis embarque cette valeur ;
     * l'incrémenter révoque instantanément toutes les sessions du compte côté serveur.
     */
    @Column(name = "token_version", nullable = false)
    private int tokenVersion = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id")
    private Company company;

    /**
     * Compte vérifié par OTP (Story 2.4, FR-P27). Faux à la création : un compte non
     * vérifié n'obtient pas de session, et sa connexion est renvoyée vers l'étape OTP.
     *
     * <p>La migration V10 a passé les comptes PRÉEXISTANTS à vrai — les laisser au défaut
     * les aurait tous bloqués derrière un code qu'aucun d'eux ne peut recevoir.
     */
    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    /**
     * Gestionnaire de l'entreprise rattachée (FR-P9), forme minimale assumée.
     *
     * <p>À NE PAS confondre avec {@link #role}, qui porte les rôles PLATEFORME et dont
     * AD-21 réserve le routage des trois espaces. Gestionnaire est un axe différent : les
     * rôles INTERNES à l'entreprise et le multi-utilisateur appartiennent à la Story 2.5,
     * qui étendra ou remplacera cette colonne.
     */
    @Column(name = "company_manager", nullable = false)
    private boolean companyManager = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    // --- Getters / setters ---
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getFirstName() { return firstName; }
    public void setFirstName(String firstName) { this.firstName = firstName; }

    public String getLastName() { return lastName; }
    public void setLastName(String lastName) { this.lastName = lastName; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public int getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(int tokenVersion) { this.tokenVersion = tokenVersion; }

    public Company getCompany() { return company; }
    public void setCompany(Company company) { this.company = company; }

    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

    public boolean isCompanyManager() { return companyManager; }
    public void setCompanyManager(boolean companyManager) { this.companyManager = companyManager; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
