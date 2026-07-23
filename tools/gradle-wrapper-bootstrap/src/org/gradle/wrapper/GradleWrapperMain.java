package org.gradle.wrapper;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/**
 * Small self-contained Gradle bootstrap used because the project artifact is
 * generated outside a Gradle installation. It honors the standard wrapper
 * properties needed by this project and launches the downloaded Gradle binary.
 */
public final class GradleWrapperMain {
    public static void main(String[] args) throws Exception {
        Path projectDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        Path propsPath = projectDir.resolve("gradle/wrapper/gradle-wrapper.properties");
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(propsPath)) {
            props.load(in);
        }
        String urlText = required(props, "distributionUrl");
        String expectedSha = props.getProperty("distributionSha256Sum", "").trim().toLowerCase(Locale.ROOT);
        URI uri = URI.create(urlText);
        String fileName = Paths.get(uri.getPath()).getFileName().toString();
        String baseName = fileName.replace("-bin.zip", "").replace("-all.zip", "");
        Path gradleHome = Paths.get(System.getProperty("user.home"), ".gradle", "wrapper", "dists", baseName, "y2player");
        Path installation = gradleHome.resolve(baseName);
        Path executable = installation.resolve(isWindows() ? "bin/gradle.bat" : "bin/gradle");

        if (!Files.isRegularFile(executable)) {
            Files.createDirectories(gradleHome);
            Path zip = gradleHome.resolve(fileName);
            if (!Files.isRegularFile(zip) || (!expectedSha.isEmpty() && !sha256(zip).equals(expectedSha))) {
                download(uri.toURL(), zip);
            }
            if (!expectedSha.isEmpty()) {
                String actual = sha256(zip);
                if (!actual.equals(expectedSha)) {
                    throw new IOException("Gradle distribution SHA-256 mismatch. Expected " + expectedSha + " but got " + actual);
                }
            }
            deleteRecursively(installation);
            unzip(zip, gradleHome);
            if (!isWindows()) executable.toFile().setExecutable(true);
        }

        List<String> command = new ArrayList<>();
        command.add(executable.toString());
        command.addAll(Arrays.asList(args));
        Process process = new ProcessBuilder(command)
                .directory(projectDir.toFile())
                .inheritIO()
                .start();
        System.exit(process.waitFor());
    }

    private static void download(URL url, Path target) throws IOException {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        System.out.println("Downloading " + url);
        URLConnection connection = url.openConnection();
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        try (InputStream in = new BufferedInputStream(connection.getInputStream());
             OutputStream out = new BufferedOutputStream(Files.newOutputStream(partial))) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                total += read;
                if ((total & ((8L * 1024 * 1024) - 1)) < read) {
                    System.out.println("Downloaded " + (total / (1024 * 1024)) + " MiB");
                }
            }
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void unzip(Path zip, Path destination) throws IOException {
        try (ZipInputStream in = new ZipInputStream(new BufferedInputStream(Files.newInputStream(zip)))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Path output = destination.resolve(entry.getName()).normalize();
                if (!output.startsWith(destination)) throw new IOException("Unsafe ZIP entry: " + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(in, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        StringBuilder result = new StringBuilder();
        for (byte value : digest.digest()) result.append(String.format("%02x", value));
        return result.toString();
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (java.util.stream.Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (IOException error) { throw new UncheckedIOException(error); }
            });
        } catch (UncheckedIOException error) {
            throw error.getCause();
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value.trim();
    }
}
