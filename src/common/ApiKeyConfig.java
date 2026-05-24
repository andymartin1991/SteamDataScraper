package common;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public final class ApiKeyConfig {
    private static final String LOCAL_PROPERTIES_FILE = "gradle-local.properties";
    private static final String LOCAL_ENV_FILE = ".env";
    private static Properties localProperties;

    private ApiKeyConfig() {
    }

    public static String getRequiredValue(String propertyName, String envName, String description) {
        String value = clean(System.getProperty(propertyName));
        if (value == null) {
            value = clean(System.getenv(envName));
        }
        if (value == null) {
            Properties props = getLocalProperties();
            value = clean(props.getProperty(propertyName));
            if (value == null) {
                value = clean(props.getProperty(envName));
            }
        }

        if (value == null) {
            throw new IllegalStateException(
                "Falta " + description + ". Configura " + envName +
                " en variables de entorno/.env o " + propertyName +
                " en " + LOCAL_PROPERTIES_FILE + " o como propiedad JVM -D" + propertyName + "=..."
            );
        }

        return value;
    }

    public static String[] getRequiredCsvValues(String propertyName, String envName, String description) {
        String rawValue = getRequiredValue(propertyName, envName, description);
        List<String> values = new ArrayList<>();
        for (String value : rawValue.split("[,;]")) {
            String trimmed = clean(value);
            if (trimmed != null) {
                values.add(trimmed);
            }
        }

        if (values.isEmpty()) {
            throw new IllegalStateException("No se encontró ningún valor válido para " + description + ".");
        }

        return values.toArray(new String[0]);
    }

    private static Properties getLocalProperties() {
        if (localProperties == null) {
            localProperties = new Properties();
            loadPropertiesFile(Path.of(LOCAL_PROPERTIES_FILE), localProperties);
            loadEnvFile(Path.of(LOCAL_ENV_FILE), localProperties);
        }
        return localProperties;
    }

    private static void loadPropertiesFile(Path path, Properties target) {
        if (!Files.isRegularFile(path)) {
            return;
        }

        try (InputStream input = Files.newInputStream(path)) {
            target.load(input);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + path.toAbsolutePath(), e);
        }
    }

    private static void loadEnvFile(Path path, Properties target) {
        if (!Files.isRegularFile(path)) {
            return;
        }

        try {
            for (String line : Files.readAllLines(path)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                if (trimmed.startsWith("export ")) {
                    trimmed = trimmed.substring("export ".length()).trim();
                }

                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }

                String key = trimmed.substring(0, separator).trim();
                String value = stripOptionalQuotes(trimmed.substring(separator + 1).trim());
                if (!key.isEmpty() && !value.isEmpty()) {
                    target.setProperty(key, value);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo leer " + path.toAbsolutePath(), e);
        }
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = stripOptionalQuotes(value.trim());
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String stripOptionalQuotes(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1).trim();
            }
        }
        return value;
    }
}

