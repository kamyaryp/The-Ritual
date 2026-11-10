package com.ritual.dial;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The record as a file the owner keeps: a folder on the phone, chosen once through the
 * system picker, where the page reads and writes golden-path.json. Nothing leaves the phone;
 * moving to a new phone is copying that file across and choosing its folder there.
 */
final class Vault {

    private static final String PREFS = "vault", TREE = "tree";

    private Vault() { }

    /** The chosen folder, if the app still holds permission to write in it. */
    static Uri tree(Context c) {
        String s = c.getSharedPreferences(PREFS, 0).getString(TREE, null);
        if (s == null) return null;
        Uri u = Uri.parse(s);
        for (UriPermission p : c.getContentResolver().getPersistedUriPermissions()) {
            if (p.getUri().equals(u) && p.isReadPermission() && p.isWritePermission()) return u;
        }
        return null;
    }

    /** Keep the folder the owner picked, across restarts. */
    static void choose(Context c, Uri u, int flags) {
        int keep = flags & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        c.getContentResolver().takePersistableUriPermission(u, keep);
        Uri old = tree(c);
        if (old != null && !old.equals(u)) {
            try {
                c.getContentResolver().releasePersistableUriPermission(old,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) { }
        }
        c.getSharedPreferences(PREFS, 0).edit().putString(TREE, u.toString()).apply();
    }

    private static Uri folderDoc(Uri t) {
        return DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
    }

    /** The folder's name as the owner sees it ("Documents"), or "" when none is chosen. */
    static String folderName(Context c) {
        Uri t = tree(c);
        if (t == null) return "";
        try (Cursor q = c.getContentResolver().query(folderDoc(t),
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst()) {
                String n = q.getString(0);
                if (n != null && n.length() > 0) return n;
            }
        } catch (Exception ignored) { }
        return "your folder";
    }

    /** A file directly inside the folder, by name. */
    private static Uri find(ContentResolver r, Uri t, String name) {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(t, DocumentsContract.getTreeDocumentId(t));
        try (Cursor q = r.query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (q == null) return null;
            while (q.moveToNext()) {
                if (name.equals(q.getString(1))) return DocumentsContract.buildDocumentUriUsingTree(t, q.getString(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** The file's text, or "" when the folder or the file isn't there. */
    static String read(Context c, String name) {
        Uri t = tree(c);
        if (t == null) return "";
        ContentResolver r = c.getContentResolver();
        Uri f = find(r, t, name);
        if (f == null) return "";
        try (InputStream in = r.openInputStream(f)) {
            if (in == null) return "";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /** Write the file whole, creating it the first time. */
    static boolean write(Context c, String name, String text) {
        Uri t = tree(c);
        if (t == null) return false;
        ContentResolver r = c.getContentResolver();
        try {
            Uri f = find(r, t, name);
            if (f == null) f = DocumentsContract.createDocument(r, folderDoc(t), "application/json", name);
            if (f == null) return false;
            OutputStream out;
            try { out = r.openOutputStream(f, "wt"); }          // truncate: the old text must not linger
            catch (Exception e) { out = r.openOutputStream(f, "w"); }
            if (out == null) return false;
            try { out.write(text.getBytes(StandardCharsets.UTF_8)); out.flush(); }
            finally { out.close(); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
