package com.ritual.dial;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Google Drive for the page: one request at a time, made by the phone (the page itself
 * never reaches the network). Only Google's API host is allowed, and only with the token
 * the owner granted for the app's hidden Drive folder.
 */
final class Drive {

    static final String SCOPE = "https://www.googleapis.com/auth/drive.appdata";
    private static final String HOST = "https://www.googleapis.com/";

    private Drive() { }

    /** {status, body}; status 0 when the request couldn't be made at all. */
    static String[] fetch(String method, String url, String type, String body, String token) {
        if (url == null || !url.startsWith(HOST)) return new String[]{"0", ""};
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setUseCaches(false);
            try {
                c.setRequestMethod(method);
            } catch (ProtocolException e) {                 // PATCH where it isn't supported
                c.setRequestMethod("POST");
                c.setRequestProperty("X-HTTP-Method-Override", method);
            }
            c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null && body.length() > 0) {
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                if (type != null && type.length() > 0) c.setRequestProperty("Content-Type", type);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream o = c.getOutputStream()) { o.write(b); }
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (in != null) {
                try (InputStream i = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = i.read(buf)) > 0) out.write(buf, 0, n);
                    text = new String(out.toByteArray(), StandardCharsets.UTF_8);
                }
            }
            return new String[]{String.valueOf(code), text};
        } catch (Exception e) {
            return new String[]{"0", ""};
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
