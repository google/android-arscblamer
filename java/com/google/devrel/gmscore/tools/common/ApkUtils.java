package com.google.devrel.gmscore.tools.common;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.checkerframework.checker.nullness.compatqual.NullableType;

/** Utilities for working with apk files. */
public final class ApkUtils {

  private ApkUtils() {} // Prevent instantiation

  /** Returns true if there exists a file whose name matches {@code filename} in {@code apkFile}. */
  public static boolean hasFile(File apkFile, String filename) throws IOException {
    try (ZipFile apkZip = new ZipFile(apkFile)) {
      return apkZip.getEntry(filename) != null;
    }
  }

  /**
   * Returns a file whose name matches {@code filename}, or null if no file was found.
   *
   * @param apkFile The file containing the apk zip archive.
   * @param filename The full filename (e.g. res/raw/foo.bar).
   * @return A byte array containing the contents of the matching file, or null if not found.
   * @throws IOException Thrown if there's a matching file, but it cannot be read from the apk.
   */
  public static byte @NullableType [] getFile(File apkFile, String filename) throws IOException {
    try (ZipFile apkZip = new ZipFile(apkFile)) {
      ZipEntry zipEntry = apkZip.getEntry(filename);
      if (zipEntry == null) {
        return null;
      }
      try (InputStream in = apkZip.getInputStream(zipEntry)) {
        return ByteStreams.toByteArray(in);
      }
    }
  }

  /**
   * Returns a file whose name matches {@code filename}, or null if no file was found.
   *
   * @param apkFile The {@link FileSystem} representation of the apk zip archive.
   * @param filename The full filename (e.g. res/raw/foo.bar).
   * @return A byte array containing the contents of the matching file, or null if not found.
   * @throws IOException Thrown if there's a matching file, but it cannot be read from the apk.
   */
  public static byte @NullableType [] getFile(FileSystem apkFile, String filename)
      throws IOException {
    return Files.readAllBytes(apkFile.getPath("/", filename));
  }

  /**
   * Returns a file whose name matches {@code filename}, or null if no file was found.
   *
   * @param inputStream The input stream containing the apk zip archive.
   * @param filename The full filename (e.g. res/raw/foo.bar).
   * @return A byte array containing the contents of the matching file, or null if not found.
   * @throws IOException Thrown if there's a matching file, but it cannot be read from the apk.
   */
  public static byte @NullableType [] getFile(InputStream inputStream, String filename)
      throws IOException {
    Map<String, byte[]> files = getFiles(inputStream, Pattern.compile(Pattern.quote(filename)));
    return files.get(filename);
  }

  /**
   * Returns all files in an apk that match a given regular expression.
   *
   * @param apkFile The file containing the apk zip archive.
   * @param regex A regular expression to match the requested filenames.
   * @return A mapping of the matched filenames to their byte contents.
   * @throws IOException Thrown if a matching file cannot be read from the apk.
   */
  public static Map<String, byte[]> getFiles(File apkFile, String regex) throws IOException {
    return getFiles(apkFile, Pattern.compile(regex));
  }

  /**
   * Returns all files in an apk that match a given regular expression.
   *
   * @param apkFile The file containing the apk zip archive.
   * @param regex A regular expression to match the requested filenames.
   * @return A mapping of the matched filenames to their byte contents.
   * @throws IOException Thrown if a matching file cannot be read from the apk.
   */
  public static Map<String, byte[]> getFiles(File apkFile, Pattern regex) throws IOException {
    try (ZipFile apkZip = new ZipFile(apkFile)) {
      Map<String, byte[]> result = new LinkedHashMap<>();
      final Enumeration<? extends ZipEntry> entries = apkZip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        if (regex.matcher(entry.getName()).matches()) {
          try (InputStream in = apkZip.getInputStream(entry)) {
            result.put(entry.getName(), ByteStreams.toByteArray(in));
          }
        }
      }
      return result;
    }
  }

  /**
   * Returns all files in an apk that match a given regular expression.
   *
   * @param apkFile The {@link FileSystem} representation of the apk zip archive.
   * @param matcher A {@link PathMatcher} to match the requested filenames.
   * @return A mapping of the matched filenames to their byte contents.
   * @throws IOException Thrown if a matching file cannot be read from the apk.
   */
  public static Map<String, byte[]> getFiles(FileSystem apkFile, PathMatcher matcher)
      throws IOException {
    ImmutableMap.Builder<String, byte[]> result = ImmutableMap.builder();
    for (Path path : findFiles(apkFile, matcher)) {
      result.put(path.toString(), Files.readAllBytes(path));
    }
    return result.build();
  }

  /**
   * Finds all files in an apk that match a given regular expression.
   *
   * @param apkFile The {@link FileSystem} representation of the apk zip archive.
   * @param matcher A {@link PathMatcher} to match the requested filenames.
   * @return A list of paths matching the provided matcher.
   * @throws IOException Thrown if a matching file cannot be read from the apk.
   */
  public static ImmutableList<Path> findFiles(FileSystem apkFile, PathMatcher matcher)
      throws IOException {
    ImmutableList.Builder<Path> result = ImmutableList.builder();
    Path root = apkFile.getPath("/");
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path p, BasicFileAttributes attrs) throws IOException {
            if (matcher.matches(p) || matcher.matches(p.normalize())) {
              result.add(
                  // fancy way of eliding leading slash
                  root.relativize(p));
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException e) {
            return FileVisitResult.SKIP_SUBTREE;
          }
        });
    return result.build();
  }

  /** Reads all files from an input stream that is reading from a zip file. */
  public static Map<String, byte[]> getFiles(InputStream inputStream, Pattern regex)
      throws IOException {
    Map<String, byte[]> files = new LinkedHashMap<>();
    try (ZipInputStream zipInputStream = new ZipInputStream(inputStream);
        BufferedInputStream bis = new BufferedInputStream(zipInputStream)) {
      ZipEntry entry;
      while ((entry = zipInputStream.getNextEntry()) != null) {
        if (regex.matcher(entry.getName()).matches()) {
          files.put(entry.getName(), ByteStreams.toByteArray(bis));
        }
        zipInputStream.closeEntry();
      }
    }
    return files;
  }

  /**
   * Writes a file to an apk. If the file already exists, it is overwritten.
   *
   * @param apkFile The file containing the apk zip archive.
   * @param filename The path of the file to write. e.g. /foo/bar.txt.
   * @param data The file data that will be written.
   * @throws IOException Thrown if the apk could not be written to.
   */
  public static void writeFile(File apkFile, String filename, byte[] data) throws IOException {
    try (FileSystem zipfs = FileSystems.newFileSystem(Paths.get(apkFile.getPath()), null)) {
      Files.write(
          zipfs.getPath(filename),
          data,
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.CREATE);
    }
  }
}
