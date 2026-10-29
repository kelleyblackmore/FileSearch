package filesearch;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * A file that matched the search criteria.
 *
 * @param path         absolute path of the file
 * @param size         size in bytes
 * @param lastModified last modification time
 * @param matches      matching lines; empty when no content query was given
 */
public record SearchResult(Path path, long size, Instant lastModified, List<LineMatch> matches) {

    public SearchResult {
        matches = List.copyOf(matches);
    }

    public String fileName() {
        Path name = path.getFileName();
        return name == null ? path.toString() : name.toString();
    }

    /**
     * A single line of a file that matched the content query.
     *
     * @param lineNumber 1-based line number
     * @param line       the line text (possibly truncated for very long lines)
     */
    public record LineMatch(int lineNumber, String line) {
    }
}
