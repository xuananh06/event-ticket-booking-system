package com.eventbooking.config;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/** Explicit UTF-8 file selection, otherwise environment selection; never merges sources. */
public final class DatabaseConfigLoader {
    public static final String CONFIG_PROPERTY = "eventbooking.db.config";
    public enum Source { FILE, ENVIRONMENT, NONE }
    public record Loaded(Source source, Optional<DatabaseSettings> settings) { }

    private DatabaseConfigLoader() { }

    public static Loaded load() {
        try {
            String selectedFile = System.getProperty(CONFIG_PROPERTY);
            return load(selectedFile, selectedFile == null ? System.getenv() : Map.of());
        } catch (SecurityException denied) {
            throw new IllegalArgumentException("Cannot read the database configuration source selector or environment.");
        }
    }

    static Loaded load(String selectedFile, Map<String, String> environment) {
        return load(selectedFile, environment, path -> Files.newBufferedReader(path, StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    interface ReaderFactory { BufferedReader open(Path path) throws IOException; }

    static Loaded load(String selectedFile, Map<String, String> environment, ReaderFactory readers) {
        if (selectedFile == null) {
            Optional<DatabaseSettings> settings = DatabaseSettings.fromEnvironment(environment);
            return new Loaded(settings.isPresent() ? Source.ENVIRONMENT : Source.NONE, settings);
        }
        Path path;
        try {
            if (selectedFile.isBlank()) { throw fileError(); }
            path = Path.of(selectedFile);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) { throw fileError(); }
        } catch (InvalidPathException | SecurityException invalid) { throw fileError(); }
        Properties properties = new UniqueProperties();
        try (BufferedReader reader = readers.open(path)) {
            // Accept an optional UTF-8 BOM without changing normal .properties decoding.
            reader.mark(1);
            if (reader.read() != '\uFEFF') { reader.reset(); }
            properties.load(reader);
        } catch (DuplicateKey duplicate) {
            throw new IllegalArgumentException("Duplicate database setting: " + DatabaseSettings.safeKey(duplicate.key) + ".");
        } catch (IOException | IllegalArgumentException | SecurityException invalid) { throw fileError(); }
        Map<String, String> values = new HashMap<>();
        for (String key : properties.stringPropertyNames()) { values.put(key, properties.getProperty(key)); }
        return new Loaded(Source.FILE, Optional.of(DatabaseSettings.fromProperties(values)));
    }

    private static IllegalArgumentException fileError() {
        return new IllegalArgumentException("Cannot load the explicitly selected UTF-8 database properties file (eventbooking.db.config). Use a readable absolute file path and valid properties syntax.");
    }

    private static final class UniqueProperties extends Properties {
        @Override public synchronized Object put(Object key, Object value) {
            if (containsKey(key)) { throw new DuplicateKey((String) key); }
            return super.put(key, value);
        }
    }

    private static final class DuplicateKey extends IllegalArgumentException {
        private final String key;
        private DuplicateKey(String key) { this.key = key; }
    }
}
