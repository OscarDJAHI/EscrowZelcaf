package com.zlecaf.escrow.security;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

/**
 * Met le corps de la requête en mémoire tampon pour qu'il puisse être lu par le
 * filtre anti-bruteforce (extraction de l'email) PUIS rejoué intégralement au
 * contrôleur. Sans ce wrapper, lire le flux dans le filtre le viderait pour la
 * suite de la chaîne. Réservé aux petits corps JSON d'authentification, la
 * lecture est bornée pour ne pas offrir de vecteur de mémoire.
 */
final class CachedBodyRequestWrapper extends HttpServletRequestWrapper {

    private final byte[] body;

    CachedBodyRequestWrapper(HttpServletRequest request, int maxBytes) throws IOException {
        super(request);
        this.body = readBounded(request.getInputStream(), maxBytes);
    }

    byte[] body() {
        return body;
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int total = 0;
        int read;
        while ((read = in.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                // Corps anormalement gros pour un endpoint d'auth : on continue de
                // drainer pour ne pas casser la requête, mais on cesse de bufferiser.
                buffer.write(chunk, 0, read - (total - maxBytes));
                drain(in, chunk);
                break;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static void drain(InputStream in, byte[] chunk) throws IOException {
        while (in.read(chunk) != -1) {
            // consomme le reste
        }
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream source = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return source.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { /* non utilisé (I/O synchrone) */ }
            @Override public int read() { return source.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
}
