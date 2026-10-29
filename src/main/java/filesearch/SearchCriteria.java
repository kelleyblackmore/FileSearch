package filesearch;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Immutable description of what to search for. Build instances with {@link #builder(Path)}.
 *
 * <p>The file name pattern is interpreted as follows:
 * <ul>
 *   <li>empty: every file matches</li>
 *   <li>{@code regex == true}: a regular expression that must match part of the file name</li>
 *   <li>contains {@code *}, {@code ?}, <code>[</code> or <code>{</code>: a glob, e.g. {@code *.java}</li>
 *   <li>otherwise: a plain substring of the file name</li>
 * </ul>
 * The content query is a substring, or a regular expression when {@code regex == true}.
 */
public final class SearchCriteria {

    /** Files bigger than this are not scanned for content unless overridden. */
    public static final long DEFAULT_MAX_CONTENT_SIZE = 50L * 1024 * 1024;

    private final Path root;
    private final String namePattern;
    private final String contentQuery;
    private final boolean caseSensitive;
    private final boolean regex;
    private final boolean includeHidden;
    private final int maxDepth;
    private final long minSize;
    private final long maxSize;
    private final Set<String> extensions;
    private final long maxContentSize;

    private final Predicate<String> nameMatcher;
    private final Pattern contentPattern;

    private SearchCriteria(Builder b) {
        this.root = Objects.requireNonNull(b.root, "root");
        this.namePattern = b.namePattern == null ? "" : b.namePattern.trim();
        this.contentQuery = b.contentQuery == null ? "" : b.contentQuery;
        this.caseSensitive = b.caseSensitive;
        this.regex = b.regex;
        this.includeHidden = b.includeHidden;
        this.maxDepth = b.maxDepth;
        this.minSize = b.minSize;
        this.maxSize = b.maxSize;
        this.extensions = Collections.unmodifiableSet(new LinkedHashSet<>(b.extensions));
        this.maxContentSize = b.maxContentSize;

        if (maxDepth < 0) {
            throw new IllegalArgumentException("maxDepth must be >= 0");
        }
        if (minSize < 0 || maxSize < 0 || minSize > maxSize) {
            throw new IllegalArgumentException("invalid size range: " + minSize + ".." + maxSize);
        }
        this.nameMatcher = compileNameMatcher();
        this.contentPattern = compileContentPattern();
    }

    public static Builder builder(Path root) {
        return new Builder(root);
    }

    private Predicate<String> compileNameMatcher() {
        if (namePattern.isEmpty()) {
            return name -> true;
        }
        if (regex) {
            Pattern p = compile(namePattern, "file name");
            return name -> p.matcher(name).find();
        }
        if (isGlob(namePattern)) {
            String glob = caseSensitive ? namePattern : namePattern.toLowerCase(Locale.ROOT);
            PathMatcher m;
            try {
                m = FileSystems.getDefault().getPathMatcher("glob:" + glob);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid file name pattern: " + e.getMessage(), e);
            }
            return name -> m.matches(Path.of(caseSensitive ? name : name.toLowerCase(Locale.ROOT)));
        }
        if (caseSensitive) {
            return name -> name.contains(namePattern);
        }
        String needle = namePattern.toLowerCase(Locale.ROOT);
        return name -> name.toLowerCase(Locale.ROOT).contains(needle);
    }

    private Pattern compileContentPattern() {
        if (contentQuery.isEmpty()) {
            return null;
        }
        return regex ? compile(contentQuery, "content")
                : Pattern.compile(Pattern.quote(contentQuery), flags());
    }

    private Pattern compile(String expr, String what) {
        try {
            return Pattern.compile(expr, flags());
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Invalid " + what + " regular expression: "
                    + e.getDescription(), e);
        }
    }

    private int flags() {
        return caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
    }

    private static boolean isGlob(String s) {
        return s.indexOf('*') >= 0 || s.indexOf('?') >= 0 || s.indexOf('[') >= 0 || s.indexOf('{') >= 0;
    }

    /** Whether a file name passes the name pattern and extension filter. */
    public boolean matchesName(String fileName) {
        if (!extensions.isEmpty()) {
            int dot = fileName.lastIndexOf('.');
            String ext = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!extensions.contains(ext)) {
                return false;
            }
        }
        return nameMatcher.test(fileName);
    }

    public boolean matchesSize(long size) {
        return size >= minSize && size <= maxSize;
    }

    public boolean hasContentQuery() {
        return contentPattern != null;
    }

    /** Compiled content pattern, or {@code null} if no content query was given. */
    public Pattern contentPattern() {
        return contentPattern;
    }

    public Path root() { return root; }
    public String namePattern() { return namePattern; }
    public String contentQuery() { return contentQuery; }
    public boolean caseSensitive() { return caseSensitive; }
    public boolean regex() { return regex; }
    public boolean includeHidden() { return includeHidden; }
    public int maxDepth() { return maxDepth; }
    public long minSize() { return minSize; }
    public long maxSize() { return maxSize; }
    public Set<String> extensions() { return extensions; }
    public long maxContentSize() { return maxContentSize; }

    public static final class Builder {
        private final Path root;
        private String namePattern = "";
        private String contentQuery = "";
        private boolean caseSensitive;
        private boolean regex;
        private boolean includeHidden;
        private int maxDepth = Integer.MAX_VALUE;
        private long minSize;
        private long maxSize = Long.MAX_VALUE;
        private final Set<String> extensions = new LinkedHashSet<>();
        private long maxContentSize = DEFAULT_MAX_CONTENT_SIZE;

        private Builder(Path root) {
            this.root = root;
        }

        public Builder namePattern(String v) { this.namePattern = v; return this; }
        public Builder contentQuery(String v) { this.contentQuery = v; return this; }
        public Builder caseSensitive(boolean v) { this.caseSensitive = v; return this; }
        public Builder regex(boolean v) { this.regex = v; return this; }
        public Builder includeHidden(boolean v) { this.includeHidden = v; return this; }
        public Builder maxDepth(int v) { this.maxDepth = v; return this; }
        public Builder minSize(long v) { this.minSize = v; return this; }
        public Builder maxSize(long v) { this.maxSize = v; return this; }
        public Builder maxContentSize(long v) { this.maxContentSize = v; return this; }

        /** Adds extensions such as {@code "java"}, {@code ".txt"} or {@code "md, txt"}. */
        public Builder extensions(String... exts) {
            for (String e : exts) {
                if (e == null) {
                    continue;
                }
                for (String part : e.split("[,;\\s]+")) {
                    String clean = part.trim();
                    if (clean.startsWith("*.")) {
                        clean = clean.substring(2);
                    } else if (clean.startsWith(".")) {
                        clean = clean.substring(1);
                    }
                    if (!clean.isEmpty()) {
                        extensions.add(clean.toLowerCase(Locale.ROOT));
                    }
                }
            }
            return this;
        }

        public SearchCriteria build() {
            return new SearchCriteria(this);
        }
    }
}
