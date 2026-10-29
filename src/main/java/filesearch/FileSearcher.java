package filesearch;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * Walks a directory tree and reports files matching a {@link SearchCriteria}.
 *
 * <p>The searcher is stateless and thread-safe; each call to {@link #search} is independent.
 */
public final class FileSearcher {

    /** Callbacks invoked from the searching thread. */
    public interface Listener {
        void onResult(SearchResult result);

        /** Called when entering a directory, useful for progress display. */
        default void onDirectory(Path dir) {
        }

        /** Called when a file or directory could not be read. The search continues. */
        default void onError(Path path, IOException error) {
        }

        /** Called when a file matched by name was too large to search for content. */
        default void onSkipped(Path path, long size) {
        }
    }

    /**
     * Totals for a finished (or cancelled) search.
     *
     * @param skipped files not searched for content because they exceed the size limit
     */
    public record Summary(int filesScanned, int matches, int errors, int skipped, boolean cancelled) {
    }

    /** Bytes inspected to decide whether a file is binary. */
    static final int BINARY_SNIFF_BYTES = 8192;
    /** Longest line text kept in a {@link SearchResult.LineMatch}. */
    static final int MAX_LINE_LENGTH = 500;
    /** Most line matches recorded per file. */
    static final int MAX_MATCHES_PER_FILE = 1000;

    /**
     * Runs a search synchronously.
     *
     * @param criteria  what to look for
     * @param listener  receives each match as soon as it is found
     * @param cancelled polled regularly; return {@code true} to stop early
     * @throws IOException if the root directory cannot be searched at all
     */
    public Summary search(SearchCriteria criteria, Listener listener, BooleanSupplier cancelled)
            throws IOException {
        Path root = criteria.root().toAbsolutePath().normalize();
        if (!Files.exists(root)) {
            throw new NoSuchFileException(root.toString(), null, "Directory does not exist");
        }
        if (!Files.isDirectory(root)) {
            throw new NotDirectoryException(root.toString());
        }
        // Resolve symlinks so a linked root folder (e.g. /tmp on macOS) is walked, not skipped.
        root = root.toRealPath();

        Visitor visitor = new Visitor(criteria, root, listener, cancelled);
        Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), criteria.maxDepth(), visitor);
        return new Summary(visitor.scanned, visitor.matches, visitor.errors, visitor.skipped, visitor.stopped);
    }

    /** Convenience overload that collects all results into a list. */
    public List<SearchResult> search(SearchCriteria criteria) throws IOException {
        List<SearchResult> results = new ArrayList<>();
        search(criteria, results::add, () -> false);
        return results;
    }

    private static final class Visitor extends SimpleFileVisitor<Path> {
        private final SearchCriteria criteria;
        private final Path root;
        private final Listener listener;
        private final BooleanSupplier cancelled;
        int scanned;
        int matches;
        int errors;
        int skipped;
        boolean stopped;

        Visitor(SearchCriteria criteria, Path root, Listener listener, BooleanSupplier cancelled) {
            this.criteria = criteria;
            this.root = root;
            this.listener = listener;
            this.cancelled = cancelled;
        }

        private FileVisitResult continueUnlessCancelled() {
            if (cancelled.getAsBoolean()) {
                stopped = true;
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            if (continueUnlessCancelled() == FileVisitResult.TERMINATE) {
                return FileVisitResult.TERMINATE;
            }
            if (!dir.equals(root) && !criteria.includeHidden() && isHidden(dir)) {
                return FileVisitResult.SKIP_SUBTREE;
            }
            listener.onDirectory(dir);
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (continueUnlessCancelled() == FileVisitResult.TERMINATE) {
                return FileVisitResult.TERMINATE;
            }
            if (attrs.isSymbolicLink()) {
                // Include symlinked files, but never follow links to directories (avoids loops).
                try {
                    attrs = Files.readAttributes(file, BasicFileAttributes.class);
                } catch (IOException e) {
                    return visitFileFailed(file, e);
                }
            }
            // Directories at the depth limit and special files are reported here too; skip them.
            if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
            }
            if (!criteria.includeHidden() && isHidden(file)) {
                return FileVisitResult.CONTINUE;
            }
            scanned++;
            String name = file.getFileName().toString();
            if (!criteria.matchesName(name) || !criteria.matchesSize(attrs.size())) {
                return FileVisitResult.CONTINUE;
            }

            List<SearchResult.LineMatch> lines = List.of();
            if (criteria.hasContentQuery()) {
                if (attrs.size() > criteria.maxContentSize()) {
                    skipped++;
                    listener.onSkipped(file, attrs.size());
                    return FileVisitResult.CONTINUE;
                }
                try {
                    lines = findInContent(file, criteria.contentPattern(), cancelled);
                } catch (IOException e) {
                    return visitFileFailed(file, e);
                }
                // A scan interrupted by cancellation is incomplete; don't report it.
                if (continueUnlessCancelled() == FileVisitResult.TERMINATE) {
                    return FileVisitResult.TERMINATE;
                }
                if (lines.isEmpty()) {
                    return FileVisitResult.CONTINUE;
                }
            }
            matches++;
            listener.onResult(new SearchResult(file, attrs.size(),
                    attrs.lastModifiedTime().toInstant(), lines));
            return FileVisitResult.CONTINUE;
        }

        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            // Permission problems and files vanishing mid-search are expected; keep going.
            errors++;
            listener.onError(file, exc);
            return continueUnlessCancelled();
        }

        @Override
        public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            if (exc != null) {
                errors++;
                listener.onError(dir, exc);
            }
            return continueUnlessCancelled();
        }
    }

    private static boolean isHidden(Path path) {
        Path name = path.getFileName();
        if (name != null && name.toString().startsWith(".")) {
            return true;
        }
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Returns the lines of {@code file} matching {@code pattern}. Binary files yield no matches.
     * Text is decoded as UTF-8, falling back to ISO-8859-1 for files that are not valid UTF-8.
     */
    static List<SearchResult.LineMatch> findInContent(Path file, Pattern pattern,
                                                      BooleanSupplier cancelled) throws IOException {
        if (isBinary(file)) {
            return List.of();
        }
        try {
            return scanLines(file, StandardCharsets.UTF_8, pattern, cancelled);
        } catch (CharacterCodingException e) {
            return scanLines(file, StandardCharsets.ISO_8859_1, pattern, cancelled);
        }
    }

    private static List<SearchResult.LineMatch> scanLines(Path file, Charset charset, Pattern pattern,
                                                          BooleanSupplier cancelled) throws IOException {
        List<SearchResult.LineMatch> found = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (pattern.matcher(line).find()) {
                    found.add(new SearchResult.LineMatch(lineNo, truncate(line.strip())));
                    if (found.size() >= MAX_MATCHES_PER_FILE) {
                        break;
                    }
                }
                if ((lineNo & 0x3FF) == 0 && cancelled.getAsBoolean()) {
                    break;
                }
            }
        }
        return found;
    }

    private static String truncate(String line) {
        return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH) + "…";
    }

    static boolean isBinary(Path file) throws IOException {
        byte[] buf = new byte[BINARY_SNIFF_BYTES];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.readNBytes(buf, 0, buf.length);
            for (int i = 0; i < n; i++) {
                if (buf[i] == 0) {
                    return true;
                }
            }
        }
        return false;
    }
}
