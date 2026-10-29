package filesearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new CommandLine(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    private String stdout() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void printsMatchingLines() throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "one\nneedle here\nthree\n");
        assertEquals(0, run("-c", "needle", root.toString()));
        assertEquals(f + ":2: needle here" + System.lineSeparator(), stdout());
    }

    @Test
    void filesOnlyPrintsPaths() throws IOException {
        Path f = root.resolve("a.txt");
        Files.writeString(f, "needle\nneedle\n");
        assertEquals(0, run("-l", "-c", "needle", root.toString()));
        assertEquals(f + System.lineSeparator(), stdout());
    }

    @Test
    void noMatchExitsWithOne() {
        assertEquals(1, run("-n", "*.nothing", root.toString()));
        assertEquals("", stdout());
    }

    @Test
    void badUsageExitsWithTwo() {
        assertEquals(2, run("--bogus"));
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("Unknown option"));
        assertEquals(2, run("-n"));
        assertEquals(2, run(root.resolve("missing").toString()));
        assertEquals(2, run("-"));
    }

    @Test
    void helpPrintsUsage() {
        assertEquals(0, run("--help"));
        assertTrue(stdout().startsWith("Usage:"));
    }

    @Test
    void parsesSizes() {
        assertEquals(512, CommandLine.parseSize("512"));
        assertEquals(10 * 1024, CommandLine.parseSize("10K"));
        assertEquals(3L * 1024 * 1024, CommandLine.parseSize("3mb"));
    }
}
