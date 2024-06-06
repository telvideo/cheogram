package eu.siacs.conversations.persistance;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaMetadataRetriever;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.Os;
import android.system.StructStat;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.util.Log;
import android.util.LruCache;

import androidx.annotation.RequiresApi;
import androidx.annotation.StringRes;
import androidx.core.content.FileProvider;
import androidx.documentfile.provider.DocumentFile;
import androidx.exifinterface.media.ExifInterface;

import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGParseException;

import com.cheogram.android.BobTransfer;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;

import com.madebyevan.thumbhash.ThumbHash;

import com.wolt.blurhashkt.BlurHashDecoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import io.ipfs.cid.Cid;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.DownloadableFile;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.AttachFileToConversationRunnable;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.utils.CryptoHelper;
import eu.siacs.conversations.utils.FileUtils;
import eu.siacs.conversations.utils.FileWriterException;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.xmpp.pep.Avatar;
import eu.siacs.conversations.xml.Element;

public class FileBackend {

    private static final Object THUMBNAIL_LOCK = new Object();

    private static final SimpleDateFormat IMAGE_DATE_FORMAT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private static final String FILE_PROVIDER = ".files";
    private static final float IGNORE_PADDING = 0.15f;
    private final XmppConnectionService mXmppConnectionService;

    private static final List<String> STORAGE_TYPES;

    static {
        final ImmutableList.Builder<String> builder =
                new ImmutableList.Builder<String>()
                        .add(
                                Environment.DIRECTORY_DOWNLOADS,
                                Environment.DIRECTORY_PICTURES,
                                Environment.DIRECTORY_MOVIES);
        if (Build.VERSION.SDK_INT >= 19) {
            builder.add(Environment.DIRECTORY_DOCUMENTS);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.add(Environment.DIRECTORY_RECORDINGS);
        }
        STORAGE_TYPES = builder.build();
    }

    public FileBackend(XmppConnectionService service) {
        this.mXmppConnectionService = service;
    }

    public static long getFileSize(Context context, Uri uri) {
        try (final Cursor cursor =
                context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index == -1) {
                    return -1;
                }
                return cursor.getLong(index);
            }
            return -1;
        } catch (final Exception ignored) {
            return -1;
        }
    }

    public static boolean allFilesUnderSize(
            Context context, List<Attachment> attachments, long max) {
        final boolean compressVideo =
                !AttachFileToConversationRunnable.getVideoCompression(context)
                        .equals("uncompressed");
        if (max <= 0) {
            Log.d(Config.LOGTAG, "server did not report max file size for http upload");
            return true; // exception to be compatible with HTTP Upload < v0.2
        }
        for (Attachment attachment : attachments) {
            if (attachment.getType() != Attachment.Type.FILE) {
                continue;
            }
            String mime = attachment.getMime();
            if (mime != null && mime.startsWith("video/") && compressVideo) {
                try {
                    Dimensions dimensions =
                            FileBackend.getVideoDimensions(context, attachment.getUri());
                    if (dimensions.getMin() > 720) {
                        Log.d(
                                Config.LOGTAG,
                                "do not consider video file with min width larger than 720 for size check");
                        continue;
                    }
                } catch (final IOException | NotAVideoFile e) {
                    // ignore and fall through
                }
            }
            if (FileBackend.getFileSize(context, attachment.getUri()) > max) {
                Log.d(
                        Config.LOGTAG,
                        "not all files are under "
                                + max
                                + " bytes. suggesting falling back to jingle");
                return false;
            }
        }
        return true;
    }

    public static File getBackupDirectory(final Context context) {
        final File conversationsDownloadDirectory =
                new File(
                        Environment.getExternalStoragePublicDirectory(
                                Environment.DIRECTORY_DOWNLOADS),
                        context.getString(R.string.app_name));
        return new File(conversationsDownloadDirectory, "Backup");
    }

    public static File getLegacyBackupDirectory(final String app) {
        final File appDirectory = new File(Environment.getExternalStorageDirectory(), app);
        return new File(appDirectory, "Backup");
    }

    private static Bitmap rotate(final Bitmap bitmap, final int degree) {
        if (degree == 0) {
            return bitmap;
        }
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        final Bitmap result = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true);
        if (!bitmap.isRecycled()) {
            bitmap.recycle();
        }
        return result;
    }

    public static boolean isPathBlacklisted(String path) {
        final String androidDataPath =
                Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android/data/";
        final File f = new File(path);
        return path.startsWith(androidDataPath) || !f.canRead();
    }

    private static Paint createAntiAliasingPaint() {
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);
        return paint;
    }

    public static Uri getUriForUri(Context context, Uri uri) {
        if ("file".equals(uri.getScheme())) {
            return getUriForFile(context, new File(uri.getPath()));
        } else {
            return uri;
        }
    }

    public static Uri getUriForFile(Context context, File file) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N || Config.ONLY_INTERNAL_STORAGE) {
            try {
                return FileProvider.getUriForFile(context, getAuthority(context), file);
            } catch (IllegalArgumentException e) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    throw new SecurityException(e);
                } else {
                    return Uri.fromFile(file);
                }
            }
        } else {
            return Uri.fromFile(file);
        }
    }

    public static String getAuthority(Context context) {
        return context.getPackageName() + FILE_PROVIDER;
    }

    private static boolean hasAlpha(final Bitmap bitmap) {
        final int w = bitmap.getWidth();
        final int h = bitmap.getHeight();
        final int yStep = Math.max(1, w / 100);
        final int xStep = Math.max(1, h / 100);
        for (int x = 0; x < w; x += xStep) {
            for (int y = 0; y < h; y += yStep) {
                if (Color.alpha(bitmap.getPixel(x, y)) < 255) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int calcSampleSize(File image, int size) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), options);
        return calcSampleSize(options, size);
    }

    private static int calcSampleSize(BitmapFactory.Options options, int size) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > size || width > size) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) > size && (halfWidth / inSampleSize) > size) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private static Dimensions getVideoDimensions(Context context, Uri uri) throws NotAVideoFile, IOException {
        MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
        try {
            mediaMetadataRetriever.setDataSource(context, uri);
        } catch (RuntimeException e) {
            throw new NotAVideoFile(e);
        }
        return getVideoDimensions(mediaMetadataRetriever);
    }

    private static Dimensions getVideoDimensionsOfFrame(
            MediaMetadataRetriever mediaMetadataRetriever) {
        Bitmap bitmap = null;
        try {
            bitmap = mediaMetadataRetriever.getFrameAtTime();
            return new Dimensions(bitmap.getHeight(), bitmap.getWidth());
        } catch (Exception e) {
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
        }
    }

    private static Dimensions getVideoDimensions(MediaMetadataRetriever metadataRetriever)
            throws NotAVideoFile, IOException {
        String hasVideo =
                metadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
        if (hasVideo == null) {
            throw new NotAVideoFile();
        }
        Dimensions dimensions = getVideoDimensionsOfFrame(metadataRetriever);
        if (dimensions != null) {
            return dimensions;
        }
        final int rotation = extractRotationFromMediaRetriever(metadataRetriever);
        boolean rotated = rotation == 90 || rotation == 270;
        int height;
        try {
            String h =
                    metadataRetriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            height = Integer.parseInt(h);
        } catch (Exception e) {
            height = -1;
        }
        int width;
        try {
            String w =
                    metadataRetriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            width = Integer.parseInt(w);
        } catch (Exception e) {
            width = -1;
        }
        try {
            metadataRetriever.release();
        } catch (final IOException e) {
            throw new NotAVideoFile();
        }
        Log.d(Config.LOGTAG, "extracted video dims " + width + "x" + height);
        return rotated ? new Dimensions(width, height) : new Dimensions(height, width);
    }

    private static int extractRotationFromMediaRetriever(MediaMetadataRetriever metadataRetriever) {
        String r =
                metadataRetriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        try {
            return Integer.parseInt(r);
        } catch (Exception e) {
            return 0;
        }
    }

    public static void close(final Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (Exception e) {
                Log.d(Config.LOGTAG, "unable to close stream", e);
            }
        }
    }

    public static void close(final Socket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.d(Config.LOGTAG, "unable to close socket", e);
            }
        }
    }

    public static void close(final ServerSocket socket) {
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException e) {
                Log.d(Config.LOGTAG, "unable to close server socket", e);
            }
        }
    }

    public static boolean dangerousFile(final Uri uri) {
        if (uri == null || Strings.isNullOrEmpty(uri.getScheme())) {
            return true;
        }
        if (ContentResolver.SCHEME_FILE.equals(uri.getScheme())) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // On Android 7 (and apps that target 7) it is now longer possible to share files
                // with a file scheme. By now you should probably not be running apps that target
                // anything less than 7 any more
                return true;
            } else {
                return isFileOwnedByProcess(uri);
            }
        }
        return false;
    }

    private static boolean isFileOwnedByProcess(final Uri uri) {
        final String path = uri.getPath();
        if (path == null) {
            return true;
        }
        try (final var pfd =
                ParcelFileDescriptor.open(new File(path), ParcelFileDescriptor.MODE_READ_ONLY)) {
            final FileDescriptor fd = pfd.getFileDescriptor();
            final StructStat st = Os.fstat(fd);
            return st.st_uid == android.os.Process.myUid();
        } catch (final Exception e) {
            // when in doubt. better safe than sorry
            return true;
        }
    }

    public static Uri getMediaUri(Context context, File file) {
        final String filePath = file.getAbsolutePath();
        try (final Cursor cursor =
                context.getContentResolver()
                        .query(
                                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                                new String[] {MediaStore.Images.Media._ID},
                                MediaStore.Images.Media.DATA + "=? ",
                                new String[] {filePath},
                                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int id =
                        cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID));
                return Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
            } else {
                return null;
            }
        } catch (final Exception e) {
            return null;
        }
    }

    public static void updateFileParams(Message message, String url, long size) {
        Message.FileParams fileParams = new Message.FileParams();
        fileParams.url = url;
        fileParams.size = size;
        message.setFileParams(fileParams);
    }

    public Bitmap getPreviewForUri(Attachment attachment, int size, boolean cacheOnly) {
        final String key = "attachment_" + attachment.getUuid().toString() + "_" + size;
        final LruCache<String, Drawable> cache = mXmppConnectionService.getDrawableCache();
        Drawable drawable = cache.get(key);
        if (drawable != null || cacheOnly) {
            return drawDrawable(drawable);
        }
        Bitmap bitmap = null;
        final String mime = attachment.getMime();
        if ("application/pdf".equals(mime)) {
            bitmap = cropCenterSquarePdf(attachment.getUri(), size);
            drawOverlay(
                    bitmap,
                    paintOverlayBlackPdf(bitmap)
                            ? R.drawable.open_pdf_black
                            : R.drawable.open_pdf_white,
                    0.75f);
        } else if (mime != null && mime.startsWith("video/")) {
            bitmap = cropCenterSquareVideo(attachment.getUri(), size);
            drawOverlay(
                    bitmap,
                    paintOverlayBlack(bitmap)
                            ? R.drawable.play_video_black
                            : R.drawable.play_video_white,
                    0.75f);
        } else {
            bitmap = cropCenterSquare(attachment.getUri(), size);
            if (bitmap != null && "image/gif".equals(mime)) {
                Bitmap withGifOverlay = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                drawOverlay(
                        withGifOverlay,
                        paintOverlayBlack(withGifOverlay)
                                ? R.drawable.play_gif_black
                                : R.drawable.play_gif_white,
                        1.0f);
                bitmap.recycle();
                bitmap = withGifOverlay;
            }
        }
        if (key != null && bitmap != null) {
            cache.put(key, new BitmapDrawable(bitmap));
        }
        return bitmap;
    }

    public void updateMediaScanner(File file) {
        updateMediaScanner(file, null);
    }

    public void updateMediaScanner(File file, final Runnable callback) {
        MediaScannerConnection.scanFile(
                mXmppConnectionService,
                new String[] {file.getAbsolutePath()},
                null,
                new MediaScannerConnection.MediaScannerConnectionClient() {
                    @Override
                    public void onMediaScannerConnected() {}

                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        if (callback != null && file.getAbsolutePath().equals(path)) {
                            callback.run();
                        } else {
                            Log.d(Config.LOGTAG, "media scanner scanned wrong file");
                            if (callback != null) {
                                callback.run();
                            }
                        }
                    }
                });
    }

    public boolean deleteFile(Message message) {
        File file = getFile(message);
        if (file.delete()) {
            message.setDeleted(true);
            updateMediaScanner(file);
            return true;
        } else {
            return false;
        }
    }

    public DownloadableFile getFile(Message message) {
        return getFile(message, true);
    }

    public DownloadableFile getFileForPath(String path) {
        return getFileForPath(
                path,
                MimeUtils.guessMimeTypeFromExtension(MimeUtils.extractRelevantExtension(path)));
    }

    private DownloadableFile getFileForPath(final String path, final String mime) {
        if (path.startsWith("/")) {
            return new DownloadableFile(path);
        } else {
            return getLegacyFileForFilename(path, mime);
        }
    }

    public DownloadableFile getLegacyFileForFilename(final String filename, final String mime) {
        if (Strings.isNullOrEmpty(mime)) {
            return new DownloadableFile(getLegacyStorageLocation("Files"), filename);
        } else if (mime.startsWith("image/")) {
            return new DownloadableFile(getLegacyStorageLocation("Images"), filename);
        } else if (mime.startsWith("video/")) {
            return new DownloadableFile(getLegacyStorageLocation("Videos"), filename);
        } else {
            return new DownloadableFile(getLegacyStorageLocation("Files"), filename);
        }
    }

    public boolean isInternalFile(final File file) {
        final File internalFile = getFileForPath(file.getName());
        return file.getAbsolutePath().equals(internalFile.getAbsolutePath());
    }

    public DownloadableFile getFile(Message message, boolean decrypted) {
        final boolean encrypted =
                !decrypted
                        && (message.getEncryption() == Message.ENCRYPTION_PGP
                                || message.getEncryption() == Message.ENCRYPTION_DECRYPTED);
        String path = message.getRelativeFilePath();
        if (path == null) {
            path = message.getUuid();
        }
        final var msgFile = getFileForPath(path, message.getMimeType());

        final DownloadableFile file;
        if (encrypted) {
            file = new DownloadableFile(
                    mXmppConnectionService.getCacheDir(),
                    String.format("%s.%s", msgFile.getName(), "pgp"));
        } else {
            file = msgFile;
        }

        try {
            if (file.exists() && file.toString().startsWith(mXmppConnectionService.getCacheDir().toString())) {
                java.nio.file.Files.setAttribute(file.toPath(), "lastAccessTime", java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
            }
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "unable to set lastAccessTime for " + file);
        }

        return file;
    }

    public List<Attachment> convertToAttachments(List<DatabaseBackend.FilePath> relativeFilePaths) {
        final List<Attachment> attachments = new ArrayList<>();
        for (final DatabaseBackend.FilePath relativeFilePath : relativeFilePaths) {
            final String mime =
                    MimeUtils.guessMimeTypeFromExtension(
                            MimeUtils.extractRelevantExtension(relativeFilePath.path));
            final File file = getFileForPath(relativeFilePath.path, mime);
            attachments.add(Attachment.of(relativeFilePath.uuid, file, mime));
        }
        return attachments;
    }

    private File getLegacyStorageLocation(final String type) {
        if (Config.ONLY_INTERNAL_STORAGE) {
            return new File(mXmppConnectionService.getFilesDir(), type);
        } else {
            final File appDirectory =
                    new File(
                            Environment.getExternalStorageDirectory(),
                            mXmppConnectionService.getString(R.string.app_name));
            final File appMediaDirectory = new File(appDirectory, "Media");
            final String locationName =
                    String.format(
                            "%s %s", mXmppConnectionService.getString(R.string.app_name), type);
            return new File(appMediaDirectory, locationName);
        }
    }

    private Bitmap resize(final Bitmap originalBitmap, int size) throws IOException {
        int w = originalBitmap.getWidth();
        int h = originalBitmap.getHeight();
        if (w <= 0 || h <= 0) {
            throw new IOException("Decoded bitmap reported bounds smaller 0");
        } else if (Math.max(w, h) > size) {
            int scalledW;
            int scalledH;
            if (w <= h) {
                scalledW = Math.max((int) (w / ((double) h / size)), 1);
                scalledH = size;
            } else {
                scalledW = size;
                scalledH = Math.max((int) (h / ((double) w / size)), 1);
            }
            final Bitmap result =
                    Bitmap.createScaledBitmap(originalBitmap, scalledW, scalledH, true);
            if (!originalBitmap.isRecycled()) {
                originalBitmap.recycle();
            }
            return result;
        } else {
            return originalBitmap;
        }
    }

    public long getUriSize(final Uri uri) {
        Cursor cursor = null;
        try {
            cursor = mXmppConnectionService.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                return cursor.getLong(sizeIndex);
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return 0; // Return 0 if the size information is not available
    }

    public boolean useImageAsIs(final Uri uri) {
        try {
            for (Cid cid : calculateCids(uri)) {
                if (mXmppConnectionService.getUrlForCid(cid) != null) return true;
            }

            long fsize = getUriSize(uri);
            if (fsize == 0 || fsize >= mXmppConnectionService.getResources().getInteger(R.integer.auto_accept_filesize)) {
                return false;
            }

            if (android.os.Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source source = ImageDecoder.createSource(mXmppConnectionService.getContentResolver(), uri);
                int[] size = new int[] { 0, 0 };
                boolean[] animated = new boolean[] { false };
                String[] mimeType = new String[] { null };
                Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    mimeType[0] = info.getMimeType();
                    animated[0] = info.isAnimated();
                    size[0] = info.getSize().getWidth();
                    size[1] = info.getSize().getHeight();
                });

                return animated[0] || (size[0] <= Config.IMAGE_SIZE && size[1] <= Config.IMAGE_SIZE);
            }

            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            final InputStream inputStream =
                    mXmppConnectionService.getContentResolver().openInputStream(uri);
            BitmapFactory.decodeStream(inputStream, null, options);
            close(inputStream);
            if (options.outMimeType == null || options.outHeight <= 0 || options.outWidth <= 0) {
                return false;
            }
            return (options.outWidth <= Config.IMAGE_SIZE && options.outHeight <= Config.IMAGE_SIZE);
        } catch (final IOException e) {
            Log.d(Config.LOGTAG, "unable to get image dimensions", e);
            return false;
        }
    }

    public String getOriginalPath(final Uri uri) {
        return FileUtils.getPath(mXmppConnectionService, uri);
    }

    public void copyFileToDocumentFile(Context ctx, File file, DocumentFile df) throws FileCopyException {
        Log.d(
                Config.LOGTAG,
                "copy file (" + file + ") to " + df);
        try (final InputStream is = new FileInputStream(file);
                final OutputStream os =
                        mXmppConnectionService.getContentResolver().openOutputStream(df.getUri())) {
            if (is == null) {
                throw new FileCopyException(R.string.error_file_not_found);
            }
            try {
                ByteStreams.copy(is, os);
                os.flush();
            } catch (IOException e) {
                throw new FileWriterException(file);
            }
        } catch (final FileNotFoundException e) {
            throw new FileCopyException(R.string.error_file_not_found);
        } catch (final FileWriterException e) {
            throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
        } catch (final SecurityException | IllegalStateException e) {
            throw new FileCopyException(R.string.error_security_exception);
        } catch (final IOException e) {
            throw new FileCopyException(R.string.error_io_exception);
        }
    }

    private InputStream openInputStream(Uri uri) throws IOException {
        if (uri != null && "data".equals(uri.getScheme())) {
            String[] parts = uri.getSchemeSpecificPart().split(",", 2);
            byte[] data;
            if (Arrays.asList(parts[0].split(";")).contains("base64")) {
                String[] parts2 = parts[0].split(";", 2);
                parts[0] = parts2[0];
                data = Base64.decode(parts[1], 0);
            } else {
                try {
                    data = parts[1].getBytes("UTF-8");
                } catch (final IOException e) {
                    data = new byte[0];
                }
            }
            return new ByteArrayInputStream(data);
        }
        final InputStream is = mXmppConnectionService.getContentResolver().openInputStream(uri);
        if (is == null) throw new FileNotFoundException("File not found");
        return is;
    }

    private void copyFileToPrivateStorage(File file, Uri uri) throws FileCopyException {
        Log.d(
                Config.LOGTAG,
                "copy file (" + uri.toString() + ") to private storage " + file.getAbsolutePath());
        file.getParentFile().mkdirs();
        try {
            if (!file.createNewFile() && file.length() > 0) {
                if (file.canRead() && file.getName().startsWith("zb2")) return; // We have this content already
                throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
            }
        } catch (IOException e) {
            throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
        }
        try (final OutputStream os = new FileOutputStream(file);
                final InputStream is = openInputStream(uri)) {
            if (is == null) {
                throw new FileCopyException(R.string.error_file_not_found);
            }
            try {
                ByteStreams.copy(is, os);
            } catch (IOException e) {
                throw new FileWriterException(file);
            }
            try {
                os.flush();
            } catch (IOException e) {
                throw new FileWriterException(file);
            }
        } catch (final FileNotFoundException e) {
            cleanup(file);
            throw new FileCopyException(R.string.error_file_not_found);
        } catch (final FileWriterException e) {
            cleanup(file);
            throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
        } catch (final SecurityException | IllegalStateException e) {
            cleanup(file);
            throw new FileCopyException(R.string.error_security_exception);
        } catch (final IOException e) {
            cleanup(file);
            throw new FileCopyException(R.string.error_io_exception);
        }
    }

    public void copyFileToPrivateStorage(Message message, Uri uri, String type)
            throws FileCopyException {
        String mime = MimeUtils.guessMimeTypeFromUriAndMime(mXmppConnectionService, uri, type);
        Log.d(Config.LOGTAG, "copy " + uri.toString() + " to private storage (mime=" + mime + ")");
        String extension = MimeUtils.guessExtensionFromMimeType(mime);
        if (extension == null) {
            Log.d(Config.LOGTAG, "extension from mime type was null");
            extension = getExtensionFromUri(uri);
        }
        if ("ogg".equals(extension) && type != null && type.startsWith("audio/")) {
            extension = "oga";
        }

        try {
            setupRelativeFilePath(message, uri, extension);
            copyFileToPrivateStorage(mXmppConnectionService.getFileBackend().getFile(message), uri);
            final String name = getDisplayNameFromUri(uri);
            if (name != null) {
                message.getFileParams().setName(name);
            }
        } catch (final XmppConnectionService.BlockedMediaException e) {
            message.setRelativeFilePath(null);
            message.setDeleted(true);
        }
    }

    private String getDisplayNameFromUri(final Uri uri) {
        final String[] projection = {OpenableColumns.DISPLAY_NAME};
        String filename = null;
        try (final Cursor cursor =
                mXmppConnectionService
                        .getContentResolver()
                        .query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                filename = cursor.getString(0);
            }
        } catch (final Exception e) {
            filename = null;
        }
        return filename;
    }

    private String getExtensionFromUri(final Uri uri) {
        final String[] projection = {MediaStore.MediaColumns.DATA};
        String filename = null;
        try (final Cursor cursor =
                mXmppConnectionService
                        .getContentResolver()
                        .query(uri, projection, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                filename = cursor.getString(0);
            }
        } catch (final Exception e) {
            filename = null;
        }
        if (filename == null) {
            final List<String> segments = uri.getPathSegments();
            if (segments.size() > 0) {
                filename = segments.get(segments.size() - 1);
            }
        }
        final int pos = filename == null ? -1 : filename.lastIndexOf('.');
        return pos > 0 ? filename.substring(pos + 1) : null;
    }

    private void copyImageToPrivateStorage(File file, Uri image, int sampleSize)
            throws FileCopyException, ImageCompressionException {
        final File parent = file.getParentFile();
        if (parent != null && parent.mkdirs()) {
            Log.d(Config.LOGTAG, "created parent directory");
        }
        InputStream is = null;
        OutputStream os = null;
        try {
            if (!file.exists() && !file.createNewFile()) {
                throw new FileCopyException(R.string.error_unable_to_create_temporary_file);
            }
            is = mXmppConnectionService.getContentResolver().openInputStream(image);
            if (is == null) {
                throw new FileCopyException(R.string.error_not_an_image_file);
            }
            final Bitmap originalBitmap;
            final BitmapFactory.Options options = new BitmapFactory.Options();
            final int inSampleSize = (int) Math.pow(2, sampleSize);
            Log.d(Config.LOGTAG, "reading bitmap with sample size " + inSampleSize);
            options.inSampleSize = inSampleSize;
            originalBitmap = BitmapFactory.decodeStream(is, null, options);
            is.close();
            if (originalBitmap == null) {
                throw new ImageCompressionException("Source file was not an image");
            }
            if (!"image/jpeg".equals(options.outMimeType) && hasAlpha(originalBitmap)) {
                originalBitmap.recycle();
                throw new ImageCompressionException("Source file had alpha channel");
            }
            Bitmap scaledBitmap = resize(originalBitmap, Config.IMAGE_SIZE);
            final int rotation = getRotation(image);
            scaledBitmap = rotate(scaledBitmap, rotation);
            boolean targetSizeReached = false;
            int quality = Config.IMAGE_QUALITY;
            final int imageMaxSize =
                    mXmppConnectionService
                            .getResources()
                            .getInteger(R.integer.auto_accept_filesize);
            while (!targetSizeReached) {
                os = new FileOutputStream(file);
                Log.d(Config.LOGTAG, "compressing image with quality " + quality);
                boolean success = scaledBitmap.compress(Config.IMAGE_FORMAT, quality, os);
                if (!success) {
                    throw new FileCopyException(R.string.error_compressing_image);
                }
                os.flush();
                final long fileSize = file.length();
                Log.d(Config.LOGTAG, "achieved file size of " + fileSize);
                targetSizeReached = fileSize <= imageMaxSize || quality <= 50;
                quality -= 5;
            }
            scaledBitmap.recycle();
        } catch (final FileNotFoundException e) {
            cleanup(file);
            throw new FileCopyException(R.string.error_file_not_found);
        } catch (final IOException e) {
            cleanup(file);
            throw new FileCopyException(R.string.error_io_exception);
        } catch (SecurityException e) {
            cleanup(file);
            throw new FileCopyException(R.string.error_security_exception_during_image_copy);
        } catch (final OutOfMemoryError e) {
            ++sampleSize;
            if (sampleSize <= 3) {
                copyImageToPrivateStorage(file, image, sampleSize);
            } else {
                throw new FileCopyException(R.string.error_out_of_memory);
            }
        } finally {
            close(os);
            close(is);
        }
    }

    private static void cleanup(final File file) {
        try {
            file.delete();
        } catch (Exception e) {

        }
    }

    public void copyImageToPrivateStorage(File file, Uri image)
            throws FileCopyException, ImageCompressionException {
        Log.d(
                Config.LOGTAG,
                "copy image ("
                        + image.toString()
                        + ") to private storage "
                        + file.getAbsolutePath());
        copyImageToPrivateStorage(file, image, 0);
    }

    public void copyImageToPrivateStorage(Message message, Uri image)
            throws FileCopyException, ImageCompressionException {
        final String filename;
        switch (Config.IMAGE_FORMAT) {
            case JPEG:
                filename = String.format("%s.%s", message.getUuid(), "jpg");
                break;
            case PNG:
                filename = String.format("%s.%s", message.getUuid(), "png");
                break;
            case WEBP:
                filename = String.format("%s.%s", message.getUuid(), "webp");
                break;
            default:
                throw new IllegalStateException("Unknown image format");
        }
        setupRelativeFilePath(message, filename);
        final File tmp = getFile(message);
        copyImageToPrivateStorage(tmp, image);
        final String extension = MimeUtils.extractRelevantExtension(filename);
        try {
            setupRelativeFilePath(message, new FileInputStream(tmp), extension);
        } catch (final FileNotFoundException e) {
            throw new FileCopyException(R.string.error_file_not_found);
        } catch (final IOException e) {
            throw new FileCopyException(R.string.error_io_exception);
        } catch (final XmppConnectionService.BlockedMediaException e) {
            tmp.delete();
            message.setRelativeFilePath(null);
            message.setDeleted(true);
            return;
        }
        tmp.renameTo(getFile(message));
        updateFileParams(message, null, false);
    }

    public void setupRelativeFilePath(final Message message, final Uri uri, final String extension) throws FileCopyException, XmppConnectionService.BlockedMediaException {
        try {
            setupRelativeFilePath(message, openInputStream(uri), extension);
        } catch (final FileNotFoundException e) {
            throw new FileCopyException(R.string.error_file_not_found);
        } catch (final IOException e) {
            throw new FileCopyException(R.string.error_io_exception);
        }
    }

    public Cid[] calculateCids(final Uri uri) throws IOException {
        return calculateCids(mXmppConnectionService.getContentResolver().openInputStream(uri));
    }

    public Cid[] calculateCids(final InputStream is) throws IOException {
        try {
            return CryptoHelper.cid(is, new String[]{"SHA-256", "SHA-1", "SHA-512"});
        } catch (final NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    public void setupRelativeFilePath(final Message message, final InputStream is, final String extension) throws IOException, XmppConnectionService.BlockedMediaException {
        message.setRelativeFilePath(getStorageLocation(message, is, extension).getAbsolutePath());
    }

    public void setupRelativeFilePath(final Message message, final String filename) {
        final String extension = MimeUtils.extractRelevantExtension(filename);
        final String mime = MimeUtils.guessMimeTypeFromExtension(extension);
        setupRelativeFilePath(message, filename, mime);
    }

    public File getStorageLocation(final Message message, final InputStream is, final String extension) throws IOException, XmppConnectionService.BlockedMediaException {
        final String mime = MimeUtils.guessMimeTypeFromExtension(extension);
        Cid[] cids = calculateCids(is);
        String base = cids[0].toString();

        File file = null;
        while (file == null || (file.exists() && !file.canRead())) {
            file = getStorageLocation(message, String.format("%s.%s", base, extension), mime);
            base += "_";
        }
        for (int i = 0; i < cids.length; i++) {
            mXmppConnectionService.saveCid(cids[i], file);
        }
        return file;
    }

    public File getStorageLocation(final Message message, final String filename, final String mime) {
        final File parentDirectory;
        if (Strings.isNullOrEmpty(mime)) {
            parentDirectory =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        } else if (mime.startsWith("image/")) {
            parentDirectory =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        } else if (mime.startsWith("video/")) {
            parentDirectory =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        } else if (MediaAdapter.DOCUMENT_MIMES.contains(mime)) {
            parentDirectory =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
        } else {
            parentDirectory =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        }
        final File appDirectory =
                new File(parentDirectory, mXmppConnectionService.getString(R.string.app_name));
        if (message == null || message.getStatus() == Message.STATUS_DUMMY || (message.getConversation() instanceof Conversation && ((Conversation) message.getConversation()).storeInCache())) {
            final var mediaCache = new File(mXmppConnectionService.getCacheDir(), "/media");
            return new File(mediaCache, filename);
        } else {
            return new File(appDirectory, filename);
        }
    }

    public static boolean inConversationsDirectory(final Context context, String path) {
        final File fileDirectory = new File(path).getParentFile();
        for (final String type : STORAGE_TYPES) {
            final File typeDirectory =
                    new File(
                            Environment.getExternalStoragePublicDirectory(type),
                            context.getString(R.string.app_name));
            if (typeDirectory.equals(fileDirectory)) {
                return true;
            }
        }
        return false;
    }

    public void setupRelativeFilePath(
            final Message message, final String filename, final String mime) {
        final File file = getStorageLocation(message, filename, mime);
        message.setRelativeFilePath(file.getAbsolutePath());
    }

    public boolean unusualBounds(final Uri image) {
        try {
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            final InputStream inputStream =
                    mXmppConnectionService.getContentResolver().openInputStream(image);
            BitmapFactory.decodeStream(inputStream, null, options);
            close(inputStream);
            float ratio = (float) options.outHeight / options.outWidth;
            return ratio > (21.0f / 9.0f) || ratio < (9.0f / 21.0f);
        } catch (final Exception e) {
            Log.w(Config.LOGTAG, "unable to detect image bounds", e);
            return false;
        }
    }

    private int getRotation(final File file) {
        try (final InputStream inputStream = new FileInputStream(file)) {
            return getRotation(inputStream);
        } catch (Exception e) {
            return 0;
        }
    }

    private int getRotation(final Uri image) {
        try (final InputStream is =
                mXmppConnectionService.getContentResolver().openInputStream(image)) {
            return is == null ? 0 : getRotation(is);
        } catch (final Exception e) {
            return 0;
        }
    }

    private static int getRotation(final InputStream inputStream) throws IOException {
        final ExifInterface exif = new ExifInterface(inputStream);
        final int orientation =
                exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_180:
                return 180;
            case ExifInterface.ORIENTATION_ROTATE_90:
                return 90;
            case ExifInterface.ORIENTATION_ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    public BitmapDrawable getFallbackThumbnail(final Message message, int size, boolean cacheOnly) {
        List<Element> thumbs = message.getFileParams() != null ? message.getFileParams().getThumbnails() : null;
        if (thumbs != null && !thumbs.isEmpty()) {
            for (Element thumb : thumbs) {
                final var uriS = thumb.getAttribute("uri");
                if (uriS == null) continue;
                Uri uri = Uri.parse(uriS);
                if (uri.getScheme().equals("data")) {
                    String[] parts = uri.getSchemeSpecificPart().split(",", 2);

                    final LruCache<String, Drawable> cache = mXmppConnectionService.getDrawableCache();
                    BitmapDrawable cached = (BitmapDrawable) cache.get(parts[1]);
                    if (cached != null || cacheOnly) return cached;

                    byte[] data;
                    if (Arrays.asList(parts[0].split(";")).contains("base64")) {
                        String[] parts2 = parts[0].split(";", 2);
                        parts[0] = parts2[0];
                        data = Base64.decode(parts[1], 0);
                    } else {
                        try {
                            data = parts[1].getBytes("UTF-8");
                        } catch (final IOException e) {
                            data = new byte[0];
                        }
                    }

                    if (parts[0].equals("image/blurhash")) {
                        int width = message.getFileParams().width;
                        if (width < 1 && thumb.getAttribute("width") != null) width = Integer.parseInt(thumb.getAttribute("width"));
                        if (width < 1) width = 1920;

                        int height = message.getFileParams().height;
                        if (height < 1 && thumb.getAttribute("height") != null) height = Integer.parseInt(thumb.getAttribute("height"));
                        if (height < 1) height = 1080;
                        Rect r = rectForSize(width, height, size);

                        Bitmap blurhash = BlurHashDecoder.INSTANCE.decode(parts[1], r.width(), r.height(), 1.0f, false);
                        if (blurhash != null) {
                            cached = new BitmapDrawable(blurhash);
                            if (parts[1] != null && cached != null) cache.put(parts[1], cached);
                            return cached;
                        }
                    } else if (parts[0].equals("image/thumbhash")) {
                        ThumbHash.Image image;
                        try {
                            image = ThumbHash.thumbHashToRGBA(data);
                        } catch (final Exception e) {
                            continue;
                        }
                        int[] pixels = new int[image.width * image.height];
                        for (int i = 0; i < pixels.length; i++) {
                            pixels[i] = Color.argb(image.rgba[(i*4)+3] & 0xff, image.rgba[i*4] & 0xff, image.rgba[(i*4)+1] & 0xff, image.rgba[(i*4)+2] & 0xff);
                        }
                        cached = new BitmapDrawable(Bitmap.createBitmap(pixels, image.width, image.height, Bitmap.Config.ARGB_8888));
                        if (parts[1] != null && cached != null) cache.put(parts[1], cached);
                        return cached;
                    }
                }
            }
         }

        return null;
    }

    public Drawable getThumbnail(Message message, Resources res, int size, boolean cacheOnly) throws IOException {
        final LruCache<String, Drawable> cache = mXmppConnectionService.getDrawableCache();
        DownloadableFile file = getFile(message);
        Drawable thumbnail = cache.get(file.getAbsolutePath());
        if (thumbnail != null) return thumbnail;

        if ((thumbnail == null) && (!cacheOnly)) {
            synchronized (THUMBNAIL_LOCK) {
                List<Element> thumbs = message.getFileParams() != null ? message.getFileParams().getThumbnails() : null;
                if (thumbs != null && !thumbs.isEmpty()) {
                    for (Element thumb : thumbs) {
                        final var uriS = thumb.getAttribute("uri");
                        if (uriS == null) continue;
                        Uri uri = Uri.parse(uriS);
                        if (uri.getScheme().equals("data")) {
                            if (android.os.Build.VERSION.SDK_INT < 28) continue;
                            String[] parts = uri.getSchemeSpecificPart().split(",", 2);

                            byte[] data;
                            if (Arrays.asList(parts[0].split(";")).contains("base64")) {
                                String[] parts2 = parts[0].split(";", 2);
                                parts[0] = parts2[0];
                                data = Base64.decode(parts[1], 0);
                            } else {
                                data = parts[1].getBytes("UTF-8");
                            }

                            if (parts[0].equals("image/blurhash")) continue; // blurhash only for fallback
                            if (parts[0].equals("image/thumbhash")) continue; // thumbhash only for fallback

                            ImageDecoder.Source source = ImageDecoder.createSource(ByteBuffer.wrap(data));
                            thumbnail = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                                int w = info.getSize().getWidth();
                                int h = info.getSize().getHeight();
                                Rect r = rectForSize(w, h, size);
                                decoder.setTargetSize(r.width(), r.height());
                            });

                            if (thumbnail != null && file.getAbsolutePath() != null) {
                                cache.put(file.getAbsolutePath(), thumbnail);
                                return thumbnail;
                            }
                        } else if (uri.getScheme().equals("cid")) {
                            Cid cid = BobTransfer.cid(uri);
                            if (cid == null) continue;
                            DownloadableFile f = mXmppConnectionService.getFileForCid(cid);
                            if (f != null && f.canRead()) {
                                return getThumbnail(f, res, size, cacheOnly);
                            }
                        }
                    }
                }
            }
        }

        return getThumbnail(file, res, size, cacheOnly);
    }

    public Drawable getThumbnail(DownloadableFile file, Resources res, int size, boolean cacheOnly) throws IOException {
        return getThumbnail(file, res, size, cacheOnly, file.getAbsolutePath());
    }

    public Drawable getThumbnail(DownloadableFile file, Resources res, int size, boolean cacheOnly, String cacheKey) throws IOException {
        final LruCache<String, Drawable> cache = mXmppConnectionService.getDrawableCache();
        Drawable thumbnail = cache.get(cacheKey);
        if ((thumbnail == null) && (!cacheOnly) && file.exists()) {
            synchronized (THUMBNAIL_LOCK) {
                thumbnail = cache.get(cacheKey);
                if (thumbnail != null) {
                    return thumbnail;
                }
                final String mime = file.getMimeType();
                if ("image/svg+xml".equals(mime)) {
                    thumbnail = getSVG(file, size);
                } else if ("application/pdf".equals(mime)) {
                    thumbnail = new BitmapDrawable(res, getPdfDocumentPreview(file, size));
                } else if (mime.startsWith("video/")) {
                    thumbnail = new BitmapDrawable(res, getVideoPreview(file, size));
                } else {
                    thumbnail = getImagePreview(file, res, size, mime);
                    if (thumbnail == null) {
                        throw new FileNotFoundException();
                    }
                }
                if (cacheKey != null && thumbnail != null) cache.put(cacheKey, thumbnail);
            }
        }
        return thumbnail;
    }

    public Bitmap getThumbnailBitmap(Message message, Resources res, int size) throws IOException {
          final Drawable drawable = getThumbnail(message, res, size, false);
          if (drawable == null) return null;
          return drawDrawable(drawable);
    }

    public Bitmap getThumbnailBitmap(DownloadableFile file, Resources res, int size, String cacheKey) throws IOException {
          final Drawable drawable = getThumbnail(file, res, size, false, cacheKey);
          if (drawable == null) return null;
          return drawDrawable(drawable);
    }

    public static Rect rectForSize(int w, int h, int size) {
        int scalledW;
        int scalledH;
        if (w <= h) {
            scalledW = Math.max((int) (w / ((double) h / size)), 1);
            scalledH = size;
        } else {
            scalledW = size;
            scalledH = Math.max((int) (h / ((double) w / size)), 1);
        }

        if (scalledW > w || scalledH > h) return new Rect(0, 0, w, h);

        return new Rect(0, 0, scalledW, scalledH);
    }

    private Drawable getImagePreview(File file, Resources res, int size, final String mime) throws IOException {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.Source source = ImageDecoder.createSource(file);
            return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                Rect r = rectForSize(w, h, size);
                decoder.setTargetSize(r.width(), r.height());
            });
        } else {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calcSampleSize(file, size);
            Bitmap bitmap = null;
            try {
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (OutOfMemoryError e) {
                options.inSampleSize *= 2;
                bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
            if (bitmap == null) return null;

            bitmap = resize(bitmap, size);
            bitmap = rotate(bitmap, getRotation(file));
            if (mime.equals("image/gif")) {
                Bitmap withGifOverlay = bitmap.copy(Bitmap.Config.ARGB_8888, true);
                drawOverlay(withGifOverlay, paintOverlayBlack(withGifOverlay) ? R.drawable.play_gif_black : R.drawable.play_gif_white, 1.0f);
                bitmap.recycle();
                bitmap = withGifOverlay;
            }
            return new BitmapDrawable(res, bitmap);
        }
    }

    public static Bitmap drawDrawable(Drawable drawable) {
        if (drawable == null) return null;

        Bitmap bitmap = null;

        if (drawable instanceof BitmapDrawable) {
            bitmap = ((BitmapDrawable) drawable).getBitmap();
            if (bitmap != null) return bitmap;
        }

        Rect bounds = drawable.getBounds();
        int width = drawable.getIntrinsicWidth();
        if (width < 1) width = bounds == null || bounds.right < 1 ? 256 : bounds.right;
        int height = drawable.getIntrinsicHeight();
        if (height < 1) height = bounds == null || bounds.bottom < 1 ? 256 : bounds.bottom;

        if (width < 1) {
            Log.w(Config.LOGTAG, "Drawable with no width: " + drawable);
            width = 48;
        }
        if (height < 1) {
            Log.w(Config.LOGTAG, "Drawable with no height: " + drawable);
            height = 48;
        }

        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private void drawOverlay(Bitmap bitmap, int resource, float factor) {
        Bitmap overlay =
                BitmapFactory.decodeResource(mXmppConnectionService.getResources(), resource);
        Canvas canvas = new Canvas(bitmap);
        float targetSize = Math.min(canvas.getWidth(), canvas.getHeight()) * factor;
        Log.d(
                Config.LOGTAG,
                "target size overlay: "
                        + targetSize
                        + " overlay bitmap size was "
                        + overlay.getHeight());
        float left = (canvas.getWidth() - targetSize) / 2.0f;
        float top = (canvas.getHeight() - targetSize) / 2.0f;
        RectF dst = new RectF(left, top, left + targetSize - 1, top + targetSize - 1);
        canvas.drawBitmap(overlay, null, dst, createAntiAliasingPaint());
    }

    /** https://stackoverflow.com/a/3943023/210897 */
    private boolean paintOverlayBlack(final Bitmap bitmap) {
        final int h = bitmap.getHeight();
        final int w = bitmap.getWidth();
        int record = 0;
        for (int y = Math.round(h * IGNORE_PADDING); y < h - Math.round(h * IGNORE_PADDING); ++y) {
            for (int x = Math.round(w * IGNORE_PADDING);
                    x < w - Math.round(w * IGNORE_PADDING);
                    ++x) {
                int pixel = bitmap.getPixel(x, y);
                if ((Color.red(pixel) * 0.299
                                + Color.green(pixel) * 0.587
                                + Color.blue(pixel) * 0.114)
                        > 186) {
                    --record;
                } else {
                    ++record;
                }
            }
        }
        return record < 0;
    }

    private boolean paintOverlayBlackPdf(final Bitmap bitmap) {
        final int h = bitmap.getHeight();
        final int w = bitmap.getWidth();
        int white = 0;
        for (int y = 0; y < h; ++y) {
            for (int x = 0; x < w; ++x) {
                int pixel = bitmap.getPixel(x, y);
                if ((Color.red(pixel) * 0.299
                                + Color.green(pixel) * 0.587
                                + Color.blue(pixel) * 0.114)
                        > 186) {
                    white++;
                }
            }
        }
        return white > (h * w * 0.4f);
    }

    private Bitmap cropCenterSquareVideo(Uri uri, int size) {
        MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
        Bitmap frame;
        try {
            metadataRetriever.setDataSource(mXmppConnectionService, uri);
            frame = metadataRetriever.getFrameAtTime(0);
            metadataRetriever.release();
            return cropCenterSquare(frame, size);
        } catch (Exception e) {
            frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff000000);
            return frame;
        }
    }

    private Bitmap getVideoPreview(final File file, final int size) {
        final MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
        Bitmap frame;
        try {
            metadataRetriever.setDataSource(file.getAbsolutePath());
            frame = metadataRetriever.getFrameAtTime(0);
            metadataRetriever.release();
            frame = resize(frame, size);
        } catch (IOException | RuntimeException e) {
            frame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff000000);
        }
        drawOverlay(
                frame,
                paintOverlayBlack(frame)
                        ? R.drawable.play_video_black
                        : R.drawable.play_video_white,
                0.75f);
        return frame;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private Bitmap getPdfDocumentPreview(final File file, final int size) {
        try {
            final ParcelFileDescriptor fileDescriptor =
                    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            final Bitmap rendered = renderPdfDocument(fileDescriptor, size, true);
            drawOverlay(
                    rendered,
                    paintOverlayBlackPdf(rendered)
                            ? R.drawable.open_pdf_black
                            : R.drawable.open_pdf_white,
                    0.75f);
            return rendered;
        } catch (final IOException | SecurityException e) {
            Log.d(Config.LOGTAG, "unable to render PDF document preview", e);
            final Bitmap placeholder = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            placeholder.eraseColor(0xff000000);
            return placeholder;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private Bitmap cropCenterSquarePdf(final Uri uri, final int size) {
        try {
            ParcelFileDescriptor fileDescriptor =
                    mXmppConnectionService.getContentResolver().openFileDescriptor(uri, "r");
            final Bitmap bitmap = renderPdfDocument(fileDescriptor, size, false);
            return cropCenterSquare(bitmap, size);
        } catch (Exception e) {
            final Bitmap placeholder = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            placeholder.eraseColor(0xff000000);
            return placeholder;
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private Bitmap renderPdfDocument(
            ParcelFileDescriptor fileDescriptor, int targetSize, boolean fit) throws IOException {
        final PdfRenderer pdfRenderer = new PdfRenderer(fileDescriptor);
        final PdfRenderer.Page page = pdfRenderer.openPage(0);
        final Dimensions dimensions =
                scalePdfDimensions(
                        new Dimensions(page.getHeight(), page.getWidth()), targetSize, fit);
        final Bitmap rendered =
                Bitmap.createBitmap(dimensions.width, dimensions.height, Bitmap.Config.ARGB_8888);
        rendered.eraseColor(0xffffffff);
        page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        page.close();
        pdfRenderer.close();
        fileDescriptor.close();
        return rendered;
    }

    public Uri getTakePhotoUri() {
        final String filename =
                String.format("IMG_%s.%s", IMAGE_DATE_FORMAT.format(new Date()), "jpg");
        final File directory;
        if (Config.ONLY_INTERNAL_STORAGE) {
            directory = new File(mXmppConnectionService.getCacheDir(), "Camera");
        } else {
            directory =
                    new File(
                            Environment.getExternalStoragePublicDirectory(
                                    Environment.DIRECTORY_DCIM),
                            "Camera");
        }
        final File file = new File(directory, filename);
        file.getParentFile().mkdirs();
        return getUriForFile(mXmppConnectionService, file);
    }

    public Avatar getPepAvatar(Uri image, int size, Bitmap.CompressFormat format) {

        final Pair<Avatar,Boolean> uncompressAvatar = getUncompressedAvatar(image);
        if (uncompressAvatar != null && uncompressAvatar.first != null &&
                (uncompressAvatar.first.image.length() <= Config.AVATAR_CHAR_LIMIT || uncompressAvatar.second)) {
            return uncompressAvatar.first;
        }
        if (uncompressAvatar != null && uncompressAvatar.first != null) {
            Log.d(
                    Config.LOGTAG,
                    "uncompressed avatar exceeded char limit by "
                            + (uncompressAvatar.first.image.length() - Config.AVATAR_CHAR_LIMIT));
        }

        Bitmap bm = cropCenterSquare(image, size);
        if (bm == null) {
            return null;
        }
        if (hasAlpha(bm)) {
            Log.d(Config.LOGTAG, "alpha in avatar detected; uploading as PNG");
            bm.recycle();
            bm = cropCenterSquare(image, 96);
            return getPepAvatar(bm, Bitmap.CompressFormat.PNG, 100);
        }
        return getPepAvatar(bm, format, 100);
    }

    private Pair<Avatar,Boolean> getUncompressedAvatar(Uri uri) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.Source source = ImageDecoder.createSource(mXmppConnectionService.getContentResolver(), uri);
                int[] size = new int[] { 0, 0 };
                boolean[] animated = new boolean[] { false };
                String[] mimeType = new String[] { null };
                Drawable drawable = ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    mimeType[0] = info.getMimeType();
                    animated[0] = info.isAnimated();
                    size[0] = info.getSize().getWidth();
                    size[1] = info.getSize().getHeight();
                });

                if (animated[0]) {
                    Avatar avatar = getPepAvatar(uri, size[0], size[1], mimeType[0]);
                    if (avatar != null) return new Pair(avatar, true);
                }

                return new Pair(getPepAvatar(drawDrawable(drawable), Bitmap.CompressFormat.PNG, 100), false);
            } else {
                Bitmap bitmap =
                    BitmapFactory.decodeStream(
                            mXmppConnectionService.getContentResolver().openInputStream(uri));
                return new Pair(getPepAvatar(bitmap, Bitmap.CompressFormat.PNG, 100), false);
            }
        } catch (Exception e) {
            try {
                final SVG svg = SVG.getFromInputStream(mXmppConnectionService.getContentResolver().openInputStream(uri));
                return new Pair(getPepAvatar(uri, (int) svg.getDocumentWidth(), (int) svg.getDocumentHeight(), "image/svg+xml"), true);
            } catch (Exception e2) {
                return null;
            }
        }
    }

    private Avatar getPepAvatar(Uri uri, int width, int height, final String mimeType) throws IOException, NoSuchAlgorithmException {
        AssetFileDescriptor fd = mXmppConnectionService.getContentResolver().openAssetFileDescriptor(uri, "r");
        if (fd.getLength() > 100000) return null; // Too big to use raw file

        ByteArrayOutputStream mByteArrayOutputStream = new ByteArrayOutputStream();
        Base64OutputStream mBase64OutputStream =
                new Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT);
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        DigestOutputStream mDigestOutputStream =
                new DigestOutputStream(mBase64OutputStream, digest);

        ByteStreams.copy(fd.createInputStream(), mDigestOutputStream);
        mDigestOutputStream.flush();
        mDigestOutputStream.close();

        final Avatar avatar = new Avatar();
        avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest());
        avatar.image = new String(mByteArrayOutputStream.toByteArray());
        avatar.type = mimeType;
        avatar.width = width;
        avatar.height = height;
        return avatar;
    }

    private Avatar getPepAvatar(Bitmap bitmap, Bitmap.CompressFormat format, int quality) {
        try {
            ByteArrayOutputStream mByteArrayOutputStream = new ByteArrayOutputStream();
            Base64OutputStream mBase64OutputStream =
                    new Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            DigestOutputStream mDigestOutputStream =
                    new DigestOutputStream(mBase64OutputStream, digest);
            if (!bitmap.compress(format, quality, mDigestOutputStream)) {
                return null;
            }
            mDigestOutputStream.flush();
            mDigestOutputStream.close();
            long chars = mByteArrayOutputStream.size();
            if (format != Bitmap.CompressFormat.PNG
                    && quality >= 50
                    && chars >= Config.AVATAR_CHAR_LIMIT) {
                int q = quality - 2;
                Log.d(
                        Config.LOGTAG,
                        "avatar char length was " + chars + " reducing quality to " + q);
                return getPepAvatar(bitmap, format, q);
            }
            Log.d(Config.LOGTAG, "settled on char length " + chars + " with quality=" + quality);
            final Avatar avatar = new Avatar();
            avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest());
            avatar.image = new String(mByteArrayOutputStream.toByteArray());
            if (format.equals(Bitmap.CompressFormat.WEBP)) {
                avatar.type = "image/webp";
            } else if (format.equals(Bitmap.CompressFormat.JPEG)) {
                avatar.type = "image/jpeg";
            } else if (format.equals(Bitmap.CompressFormat.PNG)) {
                avatar.type = "image/png";
            }
            avatar.width = bitmap.getWidth();
            avatar.height = bitmap.getHeight();
            return avatar;
        } catch (OutOfMemoryError e) {
            Log.d(Config.LOGTAG, "unable to convert avatar to base64 due to low memory");
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Avatar getStoredPepAvatar(String hash) {
        if (hash == null) {
            return null;
        }
        Avatar avatar = new Avatar();
        final File file = getAvatarFile(hash);
        FileInputStream is = null;
        try {
            avatar.size = file.length();
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            is = new FileInputStream(file);
            ByteArrayOutputStream mByteArrayOutputStream = new ByteArrayOutputStream();
            Base64OutputStream mBase64OutputStream =
                    new Base64OutputStream(mByteArrayOutputStream, Base64.DEFAULT);
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            DigestOutputStream os = new DigestOutputStream(mBase64OutputStream, digest);
            byte[] buffer = new byte[4096];
            int length;
            while ((length = is.read(buffer)) > 0) {
                os.write(buffer, 0, length);
            }
            os.flush();
            os.close();
            avatar.sha1sum = CryptoHelper.bytesToHex(digest.digest());
            avatar.image = new String(mByteArrayOutputStream.toByteArray());
            avatar.height = options.outHeight;
            avatar.width = options.outWidth;
            avatar.type = options.outMimeType;
            return avatar;
        } catch (NoSuchAlgorithmException | IOException e) {
            return null;
        } finally {
            close(is);
        }
    }

    public boolean isAvatarCached(Avatar avatar) {
        final File file = getAvatarFile(avatar.getFilename());
        return file.exists();
    }

    public boolean save(final Avatar avatar) {
        File file;
        if (isAvatarCached(avatar)) {
            file = getAvatarFile(avatar.getFilename());
            avatar.size = file.length();
        } else {
            file =
                    new File(
                            mXmppConnectionService.getCacheDir().getAbsolutePath()
                                    + "/"
                                    + UUID.randomUUID().toString());
            if (file.getParentFile().mkdirs()) {
                Log.d(Config.LOGTAG, "created cache directory");
            }
            OutputStream os = null;
            try {
                if (!file.createNewFile()) {
                    Log.d(
                            Config.LOGTAG,
                            "unable to create temporary file " + file.getAbsolutePath());
                }
                os = new FileOutputStream(file);
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                digest.reset();
                DigestOutputStream mDigestOutputStream = new DigestOutputStream(os, digest);
                final byte[] bytes = avatar.getImageAsBytes();
                mDigestOutputStream.write(bytes);
                mDigestOutputStream.flush();
                mDigestOutputStream.close();
                String sha1sum = CryptoHelper.bytesToHex(digest.digest());
                if (sha1sum.equals(avatar.sha1sum)) {
                    final File outputFile = getAvatarFile(avatar.getFilename());
                    if (outputFile.getParentFile().mkdirs()) {
                        Log.d(Config.LOGTAG, "created avatar directory");
                    }
                    final File avatarFile = getAvatarFile(avatar.getFilename());
                    if (!file.renameTo(avatarFile)) {
                        Log.d(
                                Config.LOGTAG,
                                "unable to rename " + file.getAbsolutePath() + " to " + outputFile);
                        return false;
                    }
                } else {
                    Log.d(Config.LOGTAG, "sha1sum mismatch for " + avatar.owner);
                    if (!file.delete()) {
                        Log.d(Config.LOGTAG, "unable to delete temporary file");
                    }
                    return false;
                }
                avatar.size = bytes.length;
            } catch (IllegalArgumentException | IOException | NoSuchAlgorithmException e) {
                return false;
            } finally {
                close(os);
            }
        }
        return true;
    }

    public void deleteHistoricAvatarPath() {
        delete(getHistoricAvatarPath());
    }

    private void delete(final File file) {
        if (file.isDirectory()) {
            final File[] files = file.listFiles();
            if (files != null) {
                for (final File f : files) {
                    delete(f);
                }
            }
        }
        if (file.delete()) {
            Log.d(Config.LOGTAG, "deleted " + file.getAbsolutePath());
        }
    }

    private File getHistoricAvatarPath() {
        return new File(mXmppConnectionService.getFilesDir(), "/avatars/");
    }

    public File getAvatarFile(String avatar) {
        final var f = new File(mXmppConnectionService.getCacheDir(), "/avatars/" + avatar);
        try {
            if (f.exists()) java.nio.file.Files.setAttribute(f.toPath(), "lastAccessTime", java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (final IOException e) {
            Log.w(Config.LOGTAG, "unable to set lastAccessTime for " + f);
        }
        return f;
    }

    public Uri getAvatarUri(String avatar) {
        return Uri.fromFile(getAvatarFile(avatar));
    }

    public Drawable cropCenterSquareDrawable(Uri image, int size) {
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(mXmppConnectionService.getContentResolver(), image);
                return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    Rect r = rectForSize(w, h, size);
                    decoder.setTargetSize(r.width(), r.height());

                    int newSize = Math.min(r.width(), r.height());
                    int left = (r.width() - newSize) / 2;
                    int top = (r.height() - newSize) / 2;
                    decoder.setCrop(new Rect(left, top, left + newSize, top + newSize));
                });
            } catch (final IOException e) {
                return getSVGSquare(image, size);
            }
        } else {
            Bitmap bitmap = cropCenterSquare(image, size);
            return bitmap == null ? null : new BitmapDrawable(bitmap);
        }
    }

    public Bitmap cropCenterSquare(Uri image, int size) {
        if (image == null) {
            return null;
        }
        InputStream is = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calcSampleSize(image, size);
            is = mXmppConnectionService.getContentResolver().openInputStream(image);
            if (is == null) {
                return null;
            }
            Bitmap input = BitmapFactory.decodeStream(is, null, options);
            if (input == null) {
                return null;
            } else {
                input = rotate(input, getRotation(image));
                return cropCenterSquare(input, size);
            }
        } catch (FileNotFoundException | SecurityException e) {
            Log.d(Config.LOGTAG, "unable to open file " + image.toString(), e);
            return null;
        } finally {
            close(is);
        }
    }

    public Bitmap cropCenter(Uri image, int newHeight, int newWidth) {
        if (image == null) {
            return null;
        }
        InputStream is = null;
        try {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calcSampleSize(image, Math.max(newHeight, newWidth));
            is = mXmppConnectionService.getContentResolver().openInputStream(image);
            if (is == null) {
                return null;
            }
            Bitmap source = BitmapFactory.decodeStream(is, null, options);
            if (source == null) {
                return null;
            }
            int sourceWidth = source.getWidth();
            int sourceHeight = source.getHeight();
            float xScale = (float) newWidth / sourceWidth;
            float yScale = (float) newHeight / sourceHeight;
            float scale = Math.max(xScale, yScale);
            float scaledWidth = scale * sourceWidth;
            float scaledHeight = scale * sourceHeight;
            float left = (newWidth - scaledWidth) / 2;
            float top = (newHeight - scaledHeight) / 2;

            RectF targetRect = new RectF(left, top, left + scaledWidth, top + scaledHeight);
            Bitmap dest = Bitmap.createBitmap(newWidth, newHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(dest);
            canvas.drawBitmap(source, null, targetRect, createAntiAliasingPaint());
            if (source.isRecycled()) {
                source.recycle();
            }
            return dest;
        } catch (SecurityException e) {
            return null; // android 6.0 with revoked permissions for example
        } catch (FileNotFoundException e) {
            return null;
        } finally {
            close(is);
        }
    }

    public Bitmap cropCenterSquare(Bitmap input, int size) {
        int w = input.getWidth();
        int h = input.getHeight();

        float scale = Math.max((float) size / h, (float) size / w);

        float outWidth = scale * w;
        float outHeight = scale * h;
        float left = (size - outWidth) / 2;
        float top = (size - outHeight) / 2;
        RectF target = new RectF(left, top, left + outWidth, top + outHeight);

        Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawBitmap(input, null, target, createAntiAliasingPaint());
        if (!input.isRecycled()) {
            input.recycle();
        }
        return output;
    }

    private int calcSampleSize(Uri image, int size)
            throws FileNotFoundException, SecurityException {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        final InputStream inputStream =
                mXmppConnectionService.getContentResolver().openInputStream(image);
        BitmapFactory.decodeStream(inputStream, null, options);
        close(inputStream);
        return calcSampleSize(options, size);
    }

    public void updateFileParams(Message message) {
        updateFileParams(message, null);
    }

    public void updateFileParams(final Message message, final String url) {
        updateFileParams(message, url, true);
    }

    public void updateFileParams(final Message message, String url, boolean updateCids) {
        final boolean encrypted =
                message.getEncryption() == Message.ENCRYPTION_PGP
                        || message.getEncryption() == Message.ENCRYPTION_DECRYPTED;
        final DownloadableFile file = getFile(message);
        final String mime = file.getMimeType();
        final boolean privateMessage = message.isPrivateMessage();
        final boolean image =
                message.getType() == Message.TYPE_IMAGE
                        || (mime != null && mime.startsWith("image/"));
        Message.FileParams fileParams = message.getFileParams();
        if (fileParams == null) fileParams = new Message.FileParams();
        Cid[] cids = new Cid[0];
        try {
            cids = calculateCids(new FileInputStream(file));
            fileParams.setCids(List.of(cids));
        } catch (final IOException | NoSuchAlgorithmException e) { }
        if (url == null) {
            for (Cid cid : cids) {
                url = mXmppConnectionService.getUrlForCid(cid);
                if (url != null) {
                    fileParams.url = url;
                    break;
                }
            }
        } else {
            fileParams.url = url;
        }
        if (fileParams.getName() == null) fileParams.setName(file.getName());
        fileParams.setMediaType(mime);
        if (encrypted && !file.exists()) {
            Log.d(Config.LOGTAG, "skipping updateFileParams because file is encrypted");
            final DownloadableFile encryptedFile = getFile(message, false);
            if (encryptedFile.canRead()) fileParams.size = encryptedFile.getSize();
        } else {
            Log.d(Config.LOGTAG, "running updateFileParams");
            final boolean ambiguous = MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(mime);
            final boolean video = mime != null && mime.startsWith("video/");
            final boolean audio = mime != null && mime.startsWith("audio/");
            final boolean pdf = "application/pdf".equals(mime);
            if (file.canRead()) fileParams.size = file.getSize();
            if (ambiguous) {
                try {
                    final Dimensions dimensions = getVideoDimensions(file);
                    if (dimensions.valid()) {
                        Log.d(Config.LOGTAG, "ambiguous file " + mime + " is video");
                        fileParams.width = dimensions.width;
                        fileParams.height = dimensions.height;
                    } else {
                        Log.d(Config.LOGTAG, "ambiguous file " + mime + " is audio");
                        fileParams.runtime = getMediaRuntime(file);
                    }
                } catch (final IOException | NotAVideoFile e) {
                    Log.d(Config.LOGTAG, "ambiguous file " + mime + " is audio");
                    fileParams.runtime = getMediaRuntime(file);
                }
            } else if (image || video || pdf) {
                try {
                    final Dimensions dimensions;
                    if (video) {
                        dimensions = getVideoDimensions(file);
                    } else if (pdf) {
                        dimensions = getPdfDocumentDimensions(file);
                    } else if ("image/svg+xml".equals(mime)) {
                        SVG svg = SVG.getFromInputStream(new FileInputStream(file));
                        dimensions = new Dimensions((int) svg.getDocumentHeight(), (int) svg.getDocumentWidth());
                    } else {
                        dimensions = getImageDimensions(file);
                    }
                    if (dimensions.valid()) {
                        fileParams.width = dimensions.width;
                        fileParams.height = dimensions.height;
                    }
                } catch (final IOException | SVGParseException | NotAVideoFile notAVideoFile) {
                    Log.d(
                            Config.LOGTAG,
                            "file with mime type " + file.getMimeType() + " was not a video file");
                    // fall threw
                }
            } else if (audio) {
                fileParams.runtime = getMediaRuntime(file);
            }
            try {
                Bitmap thumb = getThumbnailBitmap(file, mXmppConnectionService.getResources(), 100, file.getAbsolutePath() + " x 100");
                if (thumb != null) {
                    int[] pixels = new int[thumb.getWidth() * thumb.getHeight()];
                    byte[] rgba = new byte[pixels.length * 4];
                    try {
                        thumb.getPixels(pixels, 0, thumb.getWidth(), 0, 0, thumb.getWidth(), thumb.getHeight());
                    } catch (final IllegalStateException e) {
                        Bitmap softThumb = thumb.copy(Bitmap.Config.ARGB_8888, false);
                        softThumb.getPixels(pixels, 0, thumb.getWidth(), 0, 0, thumb.getWidth(), thumb.getHeight());
                        softThumb.recycle();
                    }
                    for (int i = 0; i < pixels.length; i++) {
                        rgba[i*4] = (byte)((pixels[i] >> 16) & 0xff);
                        rgba[(i*4)+1] = (byte)((pixels[i] >> 8) & 0xff);
                        rgba[(i*4)+2] = (byte)(pixels[i] & 0xff);
                        rgba[(i*4)+3] = (byte)((pixels[i] >> 24) & 0xff);
                    }
                    fileParams.addThumbnail(thumb.getWidth(), thumb.getHeight(), "image/thumbhash", "data:image/thumbhash;base64," + Base64.encodeToString(ThumbHash.rgbaToThumbHash(thumb.getWidth(), thumb.getHeight(), rgba), Base64.NO_WRAP));
                }
            } catch (final IOException e) { }
        }
        message.setFileParams(fileParams);
        message.setDeleted(false);
        message.setType(
                privateMessage
                        ? Message.TYPE_PRIVATE_FILE
                        : (image ? Message.TYPE_IMAGE : Message.TYPE_FILE));

        if (updateCids) {
            try {
                for (int i = 0; i < cids.length; i++) {
                    mXmppConnectionService.saveCid(cids[i], file);
                }
            } catch (XmppConnectionService.BlockedMediaException e) { }
        }
    }

    private int getMediaRuntime(final File file) {
        try {
            final MediaMetadataRetriever mediaMetadataRetriever = new MediaMetadataRetriever();
            mediaMetadataRetriever.setDataSource(file.toString());
            final String value =
                    mediaMetadataRetriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (Strings.isNullOrEmpty(value)) {
                return 0;
            }
            return Integer.parseInt(value);
        } catch (final Exception e) {
            return 0;
        }
    }

    private Dimensions getImageDimensions(File file) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        final int rotation = getRotation(file);
        final boolean rotated = rotation == 90 || rotation == 270;
        final int imageHeight = rotated ? options.outWidth : options.outHeight;
        final int imageWidth = rotated ? options.outHeight : options.outWidth;
        return new Dimensions(imageHeight, imageWidth);
    }

    private Dimensions getVideoDimensions(final File file) throws NotAVideoFile, IOException {
        MediaMetadataRetriever metadataRetriever = new MediaMetadataRetriever();
        try {
            metadataRetriever.setDataSource(file.getAbsolutePath());
        } catch (RuntimeException e) {
            throw new NotAVideoFile(e);
        }
        return getVideoDimensions(metadataRetriever);
    }

    private Dimensions getPdfDocumentDimensions(final File file) {
        final ParcelFileDescriptor fileDescriptor;
        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            if (fileDescriptor == null) {
                return new Dimensions(0, 0);
            }
        } catch (final FileNotFoundException e) {
            return new Dimensions(0, 0);
        }
        try {
            final PdfRenderer pdfRenderer = new PdfRenderer(fileDescriptor);
            final PdfRenderer.Page page = pdfRenderer.openPage(0);
            final int height = page.getHeight();
            final int width = page.getWidth();
            page.close();
            pdfRenderer.close();
            return scalePdfDimensions(new Dimensions(height, width));
        } catch (final IOException | SecurityException e) {
            Log.d(Config.LOGTAG, "unable to get dimensions for pdf document", e);
            return new Dimensions(0, 0);
        }
    }

    private Dimensions scalePdfDimensions(Dimensions in) {
        final DisplayMetrics displayMetrics =
                mXmppConnectionService.getResources().getDisplayMetrics();
        final int target = (int) (displayMetrics.density * 288);
        return scalePdfDimensions(in, target, true);
    }

    private static Dimensions scalePdfDimensions(
            final Dimensions in, final int target, final boolean fit) {
        final int w, h;
        if (fit == (in.width <= in.height)) {
            w = Math.max((int) (in.width / ((double) in.height / target)), 1);
            h = target;
        } else {
            w = target;
            h = Math.max((int) (in.height / ((double) in.width / target)), 1);
        }
        return new Dimensions(h, w);
    }

    public Drawable getAvatar(String avatar, int size) {
        if (avatar == null) {
            return null;
        }

        if (android.os.Build.VERSION.SDK_INT >= 28) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getAvatarFile(avatar));
                return ImageDecoder.decodeDrawable(source, (decoder, info, src) -> {
                    int w = info.getSize().getWidth();
                    int h = info.getSize().getHeight();
                    Rect r = rectForSize(w, h, size);
                    decoder.setTargetSize(r.width(), r.height());

                    int newSize = Math.min(r.width(), r.height());
                    int left = (r.width() - newSize) / 2;
                    int top = (r.height() - newSize) / 2;
                    decoder.setCrop(new Rect(left, top, left + newSize, top + newSize));
                });
            } catch (final IOException e) {
                return getSVGSquare(getAvatarUri(avatar), size);
            }
        } else {
            Bitmap bm = cropCenter(getAvatarUri(avatar), size, size);
            return bm == null ? null : new BitmapDrawable(bm);
        }
    }

    public Drawable getSVGSquare(Uri uri, int size) {
        try {
            SVG svg = SVG.getFromInputStream(mXmppConnectionService.getContentResolver().openInputStream(uri));
            svg.setDocumentPreserveAspectRatio(com.caverock.androidsvg.PreserveAspectRatio.FULLSCREEN);

            float w = svg.getDocumentWidth();
            float h = svg.getDocumentHeight();
            float scale = Math.max((float) size / h, (float) size / w);
            float outWidth = scale * w;
            float outHeight = scale * h;
            float left = (size - outWidth) / 2;
            float top = (size - outHeight) / 2;
            RectF target = new RectF(left, top, left + outWidth, top + outHeight);
            if (svg.getDocumentViewBox() == null) svg.setDocumentViewBox(0, 0, w, h);
            svg.setDocumentWidth("100%");
            svg.setDocumentHeight("100%");

            Bitmap output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            svg.renderToCanvas(canvas, target);

            return new SVGDrawable(output);
        } catch (final IOException | SVGParseException | IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e);
            return null;
        }
    }

    public Drawable getSVG(File file, int size) {
        try {
            SVG svg = SVG.getFromInputStream(new FileInputStream(file));
            return drawSVG(svg, size);
        } catch (final IOException | SVGParseException | IllegalArgumentException e) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e);
            return null;
        }
    }

    public Drawable drawSVG(SVG svg, int size) {
        try {
            svg.setDocumentPreserveAspectRatio(com.caverock.androidsvg.PreserveAspectRatio.LETTERBOX);

            float w = svg.getDocumentWidth();
            float h = svg.getDocumentHeight();
            Rect r = rectForSize(w < 1 ? size : (int) w, h < 1 ? size : (int) h, size);
            if (svg.getDocumentViewBox() == null) svg.setDocumentViewBox(0, 0, w, h);
            svg.setDocumentWidth("100%");
            svg.setDocumentHeight("100%");

            Bitmap output = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(output);
            svg.renderToCanvas(canvas);

            return new SVGDrawable(output);
        } catch (final SVGParseException e) {
            Log.w(Config.LOGTAG, "Could not parse SVG: " + e);
            return null;
        }
    }

    private static class Dimensions {
        public final int width;
        public final int height;

        Dimensions(int height, int width) {
            this.width = width;
            this.height = height;
        }

        public int getMin() {
            return Math.min(width, height);
        }

        public boolean valid() {
            return width > 0 && height > 0;
        }
    }

    private static class NotAVideoFile extends Exception {
        public NotAVideoFile(Throwable t) {
            super(t);
        }

        public NotAVideoFile() {
            super();
        }
    }

    public static class ImageCompressionException extends Exception {

        ImageCompressionException(String message) {
            super(message);
        }
    }

    public static class FileCopyException extends Exception {
        private final int resId;

        private FileCopyException(@StringRes int resId) {
            this.resId = resId;
        }

        public @StringRes int getResId() {
            return resId;
        }
    }

    public static class SVGDrawable extends BitmapDrawable {
        public SVGDrawable(Bitmap bm) { super(bm); }
    }
}
