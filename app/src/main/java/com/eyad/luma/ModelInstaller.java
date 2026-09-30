package com.eyad.luma;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ModelInstaller {
    public static final String MODEL_NAME = "vosk-model-small-en-us-0.15";
    private static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip";
    private static final int BUFFER_SIZE = 64 * 1024;

    public interface ProgressListener {
        void onProgress(int percent);
    }

    private ModelInstaller() { }

    public static File modelDirectory(Context context) {
        return new File(new File(context.getFilesDir(), "models"), MODEL_NAME);
    }

    public static boolean isInstalled(Context context) {
        File model = modelDirectory(context);
        return new File(model, "am/final.mdl").isFile()
                && new File(model, "conf/model.conf").isFile();
    }

    public static void downloadAndInstall(Context context, ProgressListener listener) throws Exception {
        File model = modelDirectory(context);
        if (isInstalled(context)) return;

        File modelsRoot = model.getParentFile();
        if (modelsRoot == null || (!modelsRoot.exists() && !modelsRoot.mkdirs())) {
            throw new IllegalStateException("Could not create model storage directory.");
        }
        File archive = new File(modelsRoot, MODEL_NAME + ".zip.part");
        File staging = new File(modelsRoot, MODEL_NAME + ".extracting");
        deleteRecursively(staging);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "LumaSubtitles/1.0 Android");
            connection.connect();
            int response = connection.getResponseCode();
            if (response < 200 || response >= 300) {
                throw new IllegalStateException("Speech model download failed (HTTP " + response + ").");
            }
            long total = connection.getContentLengthLong();
            long received = 0;
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 FileOutputStream output = new FileOutputStream(archive)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int count;
                int lastPercent = -1;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    received += count;
                    if (total > 0 && listener != null) {
                        int percent = (int) Math.min(100, (received * 100) / total);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            listener.onProgress(percent);
                        }
                    }
                }
                output.getFD().sync();
            }
            if (archive.length() < 1_000_000L) {
                throw new IllegalStateException("The downloaded model archive is incomplete.");
            }
            if (!staging.mkdirs()) {
                throw new IllegalStateException("Could not prepare model extraction directory.");
            }
            extractSafely(archive, staging);
            if (!new File(staging, "am/final.mdl").isFile()
                    || !new File(staging, "conf/model.conf").isFile()) {
                throw new IllegalStateException("The archive did not contain a usable English model.");
            }
            deleteRecursively(model);
            if (!staging.renameTo(model)) {
                throw new IllegalStateException("Could not move the speech model into place.");
            }
            if (listener != null) listener.onProgress(100);
        } finally {
            if (connection != null) connection.disconnect();
            if (archive.exists()) archive.delete();
            if (staging.exists()) deleteRecursively(staging);
        }
    }

    private static void extractSafely(File archive, File destination) throws Exception {
        String rootPrefix = MODEL_NAME + "/";
        String destinationPath = destination.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith(rootPrefix)) name = name.substring(rootPrefix.length());
                if (name.isEmpty()) continue;
                File target = new File(destination, name);
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(destinationPath)) {
                    throw new SecurityException("Unsafe path in speech model archive.");
                }
                if (entry.isDirectory()) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw new IllegalStateException("Could not create model folder.");
                    }
                } else {
                    File parent = target.getParentFile();
                    if (parent == null || (!parent.exists() && !parent.mkdirs())) {
                        throw new IllegalStateException("Could not create model folder.");
                    }
                    try (BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(target))) {
                        int count;
                        while ((count = zip.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }
}
