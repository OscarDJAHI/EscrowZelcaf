package com.zlecaf.escrow.service;

import java.io.InputStream;

/**
 * Binary carrier from the service to the web layer for an evidence download.
 * <p>
 * Not a JSON DTO: it deliberately exposes neither the opaque storage handle nor
 * any storage-technology type. The service opens {@code content} via the storage port
 * and the controller streams it straight to the response; the caller owns the
 * stream and must ensure it is consumed/closed (Spring does so after the
 * controller returns).
 */
public record EvidenceDownload(InputStream content, String filename, String contentType, long sizeBytes) {
}
