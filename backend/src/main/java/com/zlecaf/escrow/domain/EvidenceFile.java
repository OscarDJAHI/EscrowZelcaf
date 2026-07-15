package com.zlecaf.escrow.domain;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Metadata for one piece of evidence attached to a transaction. The binary lives
 * in object storage; {@code storageKey} is the opaque handle used to fetch it.
 * Rows are never physically deleted — withdrawal only flips {@link EvidenceStatus}.
 */
@Entity
@Table(name = "evidence_files")
public class EvidenceFile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_id", nullable = false)
    private Long transactionId;

    /** Null when the upload came from a partner rather than an authenticated user. */
    @Column(name = "uploaded_by_user_id")
    private Long uploadedByUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "uploader_type", nullable = false, length = 20)
    private UploaderType uploaderType;

    /** Set only for CARRIER_PARTNER uploads. */
    @Column(name = "partner_company_id")
    private Long partnerCompanyId;

    /** Name as supplied by the client. Never used to build the storage key. */
    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    /** Opaque handle returned by the storage port. */
    @Column(name = "storage_key", nullable = false, length = 500)
    private String storageKey;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EvidenceStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawn_by_user_id")
    private Long withdrawnByUserId;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTransactionId() { return transactionId; }
    public void setTransactionId(Long transactionId) { this.transactionId = transactionId; }

    public Long getUploadedByUserId() { return uploadedByUserId; }
    public void setUploadedByUserId(Long uploadedByUserId) { this.uploadedByUserId = uploadedByUserId; }

    public UploaderType getUploaderType() { return uploaderType; }
    public void setUploaderType(UploaderType uploaderType) { this.uploaderType = uploaderType; }

    public Long getPartnerCompanyId() { return partnerCompanyId; }
    public void setPartnerCompanyId(Long partnerCompanyId) { this.partnerCompanyId = partnerCompanyId; }

    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public String getComment() { return comment; }
    public void setComment(String comment) { this.comment = comment; }

    public EvidenceStatus getStatus() { return status; }
    public void setStatus(EvidenceStatus status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getWithdrawnAt() { return withdrawnAt; }
    public void setWithdrawnAt(Instant withdrawnAt) { this.withdrawnAt = withdrawnAt; }

    public Long getWithdrawnByUserId() { return withdrawnByUserId; }
    public void setWithdrawnByUserId(Long withdrawnByUserId) { this.withdrawnByUserId = withdrawnByUserId; }
}
