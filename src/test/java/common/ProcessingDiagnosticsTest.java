package common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ProcessingDiagnosticsTest {

    @Test
    void separatesExpectedSkipsFromProcessingErrors() {
        ProcessingDiagnostics diagnostics = new ProcessingDiagnostics();

        diagnostics.skipped("fecha futura");
        diagnostics.skipped("fecha futura");
        diagnostics.skipped("tipo no admitido");
        diagnostics.error("Steam AppID 10", new IllegalArgumentException("JSON inválido"));

        assertEquals(3, diagnostics.getSkippedCount());
        assertEquals(2, diagnostics.getSkippedCount("fecha futura"));
        assertEquals(1, diagnostics.getSkippedCount("tipo no admitido"));
        assertEquals(1, diagnostics.getErrors());
    }
}
