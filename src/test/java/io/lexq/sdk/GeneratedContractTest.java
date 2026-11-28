package io.lexq.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Contract.java is what scripts/GenerateContract.java writes from spec/values.json. */
class GeneratedContractTest {

    @Test
    void contractIsWhatTheGeneratorWritesFromSpecValues() throws IOException, InterruptedException {
        Path root = Path.of(System.getProperty("basedir", "."));
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path output = Files.createTempFile("generate-contract", ".log");
        try {
            Process process = new ProcessBuilder(java.toString(),
                    root.resolve("scripts/GenerateContract.java").toString(), "--check", root.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(output.toFile())
                    .start();
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                fail("scripts/GenerateContract.java --check did not finish in 2 minutes. Its output so far:\n"
                        + read(output));
            }
            assertEquals(0, process.exitValue(), () -> read(output));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    /** The generator's output, with any bytes that are not UTF-8 replaced. */
    private static String read(Path output) {
        try {
            return new String(Files.readAllBytes(output), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "The output of scripts/GenerateContract.java could not be read: " + e;
        }
    }
}
