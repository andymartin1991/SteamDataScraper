package common;

import java.util.LinkedHashMap;
import java.util.Map;

/** Contabiliza descartes esperados y errores reales sin inundar la consola. */
public final class ProcessingDiagnostics {

    private static final int MAX_ERROR_DETAILS = 10;

    private final Map<String, Integer> skippedByReason = new LinkedHashMap<>();
    private int errors;

    public void skipped(String reason) {
        skippedByReason.merge(reason, 1, Integer::sum);
    }

    public void error(String itemId, RuntimeException exception) {
        errors++;
        if (errors <= MAX_ERROR_DETAILS) {
            System.err.println("⚠️ Error procesando " + itemId + ": "
                + exception.getClass().getSimpleName() + ": " + safeMessage(exception));
        } else if (errors == MAX_ERROR_DETAILS + 1) {
            System.err.println("⚠️ Se omiten más detalles individuales; se mostrarán en el resumen final.");
        }
    }

    public int getErrors() {
        return errors;
    }

    public int getSkippedCount() {
        return skippedByReason.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int getSkippedCount(String reason) {
        return skippedByReason.getOrDefault(reason, 0);
    }

    public void printSummary() {
        System.out.println("   -> Descartados por filtros: " + getSkippedCount());
        for (Map.Entry<String, Integer> entry : skippedByReason.entrySet()) {
            System.out.println("      - " + entry.getKey() + ": " + entry.getValue());
        }
        System.out.println("   -> Errores de procesamiento: " + errors);
    }

    private static String safeMessage(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "sin detalle" : message;
    }
}
