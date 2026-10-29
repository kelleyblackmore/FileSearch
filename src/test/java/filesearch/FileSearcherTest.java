package filesearch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSearcherTest {

    @TempDir
    Path root;

    private final FileSearcher searcher = new FileSearcher();

    @BeforeEach
    void createTree() throws IOException {
        write("Main.java", "public class Main {\n    // TODO fix\n}\n");
        write("README.md", "# Project\nSome notes\n");
        write("src/Util.java", "class Util {\n  int todo = 1;\n}\n");
        write("src/deep/nested/Data.txt", "hello world\n");
        write(".hidden/secret.txt", "hello hidden\n");
        write(".dotfile", "hello dot\n");
        Files.write(root.resolve("image.bin"), new byte[] {'h', 'e', 'l', 'l', 'o', 0, 1, 2});
        Files.write(root.resolve("latin1.txt"), "café hello".getBytes(StandardCharsets.ISO_8859_1));
    }

    private void write(String rel, String content) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private Set<String> names(SearchCriteria c) throws IOException {
        return searcher.search(c).stream().map(SearchResult::fileName).collect(Collectors.toSet());
    }

    @Test
    void emptyCriteriaMatchesAllVisibleFiles() throws IOException {
        assertEquals(Set.of("Main.java", "README.md", "Util.java", "Data.txt", "image.bin", "latin1.txt"),
                names(SearchCriteria.builder(root).build()));
    }

    @Test
    void includeHiddenFindsDotFilesAndDirectories() throws IOException {
        Set<String> found = names(SearchCriteria.builder(root).includeHidden(true).build());
        assertTrue(found.contains("secret.txt"));
        assertTrue(found.contains(".dotfile"));
    }

    @Test
    void globPattern() throws IOException {
        assertEquals(Set.of("Main.java", "Util.java"),
                names(SearchCriteria.builder(root).namePattern("*.java").build()));
    }

    @Test
    void substringIsCaseInsensitiveByDefault() throws IOException {
        assertEquals(Set.of("README.md"), names(SearchCriteria.builder(root).namePattern("readme").build()));
        assertEquals(Set.of(), names(SearchCriteria.builder(root).namePattern("readme").caseSensitive(true).build()));
    }

    @Test
    void regexNamePattern() throws IOException {
        assertEquals(Set.of("Main.java", "Util.java"),
                names(SearchCriteria.builder(root).namePattern("^[MU].*\\.java$").regex(true).build()));
    }

    @Test
    void extensionFilter() throws IOException {
        assertEquals(Set.of("Data.txt", "latin1.txt", "README.md"),
                names(SearchCriteria.builder(root).extensions(".txt, md").build()));
    }

    @Test
    void contentSearchReportsLineNumbers() throws IOException {
        List<SearchResult> results = searcher.search(SearchCriteria.builder(root).contentQuery("todo").build());
        assertEquals(2, results.size());
        SearchResult main = results.stream().filter(r -> r.fileName().equals("Main.java")).findFirst().orElseThrow();
        assertEquals(List.of(new SearchResult.LineMatch(2, "// TODO fix")), main.matches());
    }

    @Test
    void contentSearchSkipsBinaryAndHandlesNonUtf8() throws IOException {
        assertEquals(Set.of("Data.txt", "latin1.txt"),
                names(SearchCriteria.builder(root).contentQuery("hello").build()));
        assertEquals(Set.of("latin1.txt"),
                names(SearchCriteria.builder(root).contentQuery("café").build()));
    }

    @Test
    void contentRegex() throws IOException {
        assertEquals(Set.of("Util.java"),
                names(SearchCriteria.builder(root).contentQuery("int\\s+\\w+\\s*=").regex(true).build()));
    }

    @Test
    void maxDepthLimitsRecursion() throws IOException {
        assertEquals(Set.of("Main.java", "README.md", "image.bin", "latin1.txt"),
                names(SearchCriteria.builder(root).maxDepth(1).build()));
    }

    @Test
    void sizeFilter() throws IOException {
        assertEquals(Set.of("image.bin"), names(SearchCriteria.builder(root).maxSize(8).build()));
    }

    @Test
    void cancellationStopsSearch() throws IOException {
        FileSearcher.Summary s = searcher.search(SearchCriteria.builder(root).build(), r -> { }, () -> true);
        assertTrue(s.cancelled());
        assertEquals(0, s.matches());
    }

    @Test
    void symlinkedRootIsSearched() throws IOException {
        Path link = Files.createSymbolicLink(root.resolveSibling(root.getFileName() + "-link"), root);
        try {
            assertEquals(Set.of("Main.java", "Util.java"),
                    names(SearchCriteria.builder(link).namePattern("*.java").build()));
        } finally {
            Files.delete(link);
        }
    }

    @Test
    void symlinkedFilesAreIncluded() throws IOException {
        Files.createSymbolicLink(root.resolve("link.java"), root.resolve("Main.java"));
        List<SearchResult> results = searcher.search(
                SearchCriteria.builder(root).namePattern("link*").contentQuery("TODO").build());
        assertEquals(1, results.size());
        assertEquals(2, results.get(0).matches().get(0).lineNumber());
    }

    @Test
    void bracketsInNameAreLiteralWithoutWildcards() throws IOException {
        write("report[1].txt", "x");
        write("{draft.txt", "x");
        assertEquals(Set.of("report[1].txt"), names(SearchCriteria.builder(root).namePattern("report[1]").build()));
        assertEquals(Set.of("{draft.txt"), names(SearchCriteria.builder(root).namePattern("{draft").build()));
    }

    @Test
    void largeFilesAreReportedAsSkippedForContent() throws IOException {
        List<Path> skipped = new java.util.ArrayList<>();
        FileSearcher.Summary s = searcher.search(
                SearchCriteria.builder(root).contentQuery("hello").maxContentSize(5).build(),
                new FileSearcher.Listener() {
                    @Override
                    public void onResult(SearchResult r) {
                    }

                    @Override
                    public void onSkipped(Path path, long size) {
                        skipped.add(path);
                    }
                }, () -> false);
        assertEquals(0, s.matches());
        assertEquals(skipped.size(), s.skipped());
        assertTrue(s.skipped() >= 2);
    }

    @Test
    void searchCompletedWithoutCancelIsNotReportedCancelled() throws IOException {
        FileSearcher.Summary s = searcher.search(SearchCriteria.builder(root).build(), r -> { }, () -> false);
        assertEquals(false, s.cancelled());
    }

    @Test
    void missingRootFails() {
        assertThrows(NoSuchFileException.class,
                () -> searcher.search(SearchCriteria.builder(root.resolve("nope")).build()));
    }

    @Test
    void invalidRegexIsReported() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SearchCriteria.builder(root).contentQuery("(unclosed").regex(true).build());
        assertTrue(e.getMessage().contains("regular expression"));
    }
}
