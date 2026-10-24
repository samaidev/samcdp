package cc.samaidev.samcdp;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * DownloadsProvider (v1.4.2) — serves files captured by {@link Downloads} over
 * content:// so they can be (a) opened by other apps from the phone UI
 * (ACTION_VIEW + FLAG_GRANT_READ_URI_PERMISSION) and (b) handed to a page's
 * file input via WebChromeClient.onShowFileChooser.
 *
 * URI shape: content://cc.samaidev.samcdp.downloads/<download-id>/<display-name>
 * The id is resolved through the on-disk meta.json registry (all slots) — no
 * arbitrary path traversal is possible since only registered ids resolve.
 *
 * Not exported; access is granted per-intent via grantUriPermissions.
 */
public class DownloadsProvider extends ContentProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    private Downloads.Entry resolve(Uri uri) {
        if (uri == null) return null;
        java.util.List<String> seg = uri.getPathSegments();
        if (seg == null || seg.isEmpty()) return null;
        return Downloads.findByIdFromDisk(getContext(), seg.get(0));
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode) && mode != null && mode.contains("w")) {
            throw new FileNotFoundException("read-only provider");
        }
        Downloads.Entry e = resolve(uri);
        if (e == null) throw new FileNotFoundException("no such download: " + uri);
        File f = new File(e.path);
        if (!f.isFile()) throw new FileNotFoundException("file gone: " + e.name);
        try {
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException fnf) {
            throw fnf;
        } catch (Exception ex) {
            throw new FileNotFoundException(ex.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        Downloads.Entry e = resolve(uri);
        if (e == null) return "application/octet-stream";
        String mime = e.mime == null || e.mime.isEmpty()
                ? "application/octet-stream" : e.mime;
        return mime.split(";")[0].trim();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        Downloads.Entry e = resolve(uri);
        if (e == null) return null;
        String name = e.name == null ? "file.bin" : e.name;
        long size = new File(e.path).length();
        if (projection == null) {
            projection = new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        }
        MatrixCursor c = new MatrixCursor(projection);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = name;
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = size;
            else row[i] = null;
        }
        c.addRow(row);
        return c;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only");
    }
}
