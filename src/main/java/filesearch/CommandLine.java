package filesearch;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Command-line front end. Prints matching paths, or {@code path:line: text} for content matches.
 *
 * <p>Exit status: 0 if anything matched, 1 if nothing matched, 2 on usage or I/O errors.
 */
public final class CommandLine {

    static final String USAGE = """
            Usage: filesearch [options] [directory]

            Searches DIRECTORY (default: current directory) for files.

            Options:
              -n, --name PATTERN      file name to match: substring, glob (*.java) or regex with -r
              -c, --contains TEXT     only files whose content contains TEXT (regex with -r)
              -e, --ext LIST          comma-separated extensions, e.g. java,txt
              -i, --ignore-case       case-insensitive matching (default)
              -s, --case-sensitive    case-sensitive matching
              -r, --regex             treat PATTERN and TEXT as regular expressions
              -a, --hidden            include hidden files and directories
              -d, --max-depth N       descend at most N directory levels
                  --min-size BYTES    minimum file size (suffixes K, M, G allowed)
                  --max-size BYTES    maximum file size (suffixes K, M, G allowed)
              -l, --files-only        with -c, print only file names, not matching lines
              -q, --quiet             do not report unreadable files
                  --gui               open the graphical interface
              -h, --help              show this help
            """;

    private final PrintStream out;
    private final PrintStream err;

    public CommandLine(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public int run(String[] args) {
        SearchCriteria criteria;
        boolean filesOnly = false;
        boolean quiet = false;
        try {
            Path dir = null;
            String name = "";
            String contains = "";
            String ext = null;
            boolean caseSensitive = false;
            boolean regex = false;
            boolean hidden = false;
            int maxDepth = Integer.MAX_VALUE;
            long minSize = 0;
            long maxSize = Long.MAX_VALUE;

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                switch (a) {
                    case "-h", "--help" -> {
                        out.print(USAGE);
                        return 0;
                    }
                    case "-n", "--name" -> name = value(args, ++i, a);
                    case "-c", "--contains" -> contains = value(args, ++i, a);
                    case "-e", "--ext" -> ext = value(args, ++i, a);
                    case "-i", "--ignore-case" -> caseSensitive = false;
                    case "-s", "--case-sensitive" -> caseSensitive = true;
                    case "-r", "--regex" -> regex = true;
                    case "-a", "--hidden" -> hidden = true;
                    case "-d", "--max-depth" -> maxDepth = parseInt(value(args, ++i, a), a);
                    case "--min-size" -> minSize = parseSize(value(args, ++i, a));
                    case "--max-size" -> maxSize = parseSize(value(args, ++i, a));
                    case "-l", "--files-only" -> filesOnly = true;
                    case "-q", "--quiet" -> quiet = true;
                    default -> {
                        if (a.startsWith("-") && !a.equals("-")) {
                            throw new IllegalArgumentException("Unknown option: " + a);
                        }
                        if (dir != null) {
                            throw new IllegalArgumentException("Only one directory may be given");
                        }
                        dir = Path.of(a);
                    }
                }
            }

            criteria = SearchCriteria.builder(dir == null ? Path.of(".") : dir)
                    .namePattern(name)
                    .contentQuery(contains)
                    .extensions(ext)
                    .caseSensitive(caseSensitive)
                    .regex(regex)
                    .includeHidden(hidden)
                    .maxDepth(maxDepth)
                    .minSize(minSize)
                    .maxSize(maxSize)
                    .build();
        } catch (IllegalArgumentException e) {
            err.println("filesearch: " + e.getMessage());
            err.println("Try 'filesearch --help' for more information.");
            return 2;
        }

        final boolean printLines = !filesOnly;
        final boolean reportErrors = !quiet;
        try {
            FileSearcher.Summary summary = new FileSearcher().search(criteria, new FileSearcher.Listener() {
                @Override
                public void onResult(SearchResult r) {
                    if (printLines && !r.matches().isEmpty()) {
                        for (SearchResult.LineMatch m : r.matches()) {
                            out.println(r.path() + ":" + m.lineNumber() + ": " + m.line());
                        }
                    } else {
                        out.println(r.path());
                    }
                }

                @Override
                public void onError(Path path, IOException error) {
                    if (reportErrors) {
                        err.println("filesearch: cannot read " + path + ": " + describe(error));
                    }
                }
            }, () -> false);
            return summary.matches() > 0 ? 0 : 1;
        } catch (IOException e) {
            err.println("filesearch: " + describe(e));
            return 2;
        }
    }

    private static String value(String[] args, int i, String option) {
        if (i >= args.length) {
            throw new IllegalArgumentException("Option " + option + " requires a value");
        }
        return args[i];
    }

    private static int parseInt(String s, String option) {
        try {
            int v = Integer.parseInt(s.trim());
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Option " + option + " expects a non-negative number: " + s);
        }
    }

    /** Parses sizes like {@code 512}, {@code 10K}, {@code 3M} or {@code 1G} (binary units). */
    static long parseSize(String s) {
        String t = s.trim().toUpperCase();
        long mult = 1;
        if (t.endsWith("B")) {
            t = t.substring(0, t.length() - 1);
        }
        if (!t.isEmpty()) {
            switch (t.charAt(t.length() - 1)) {
                case 'K' -> mult = 1024L;
                case 'M' -> mult = 1024L * 1024;
                case 'G' -> mult = 1024L * 1024 * 1024;
                default -> mult = 1;
            }
            if (mult != 1) {
                t = t.substring(0, t.length() - 1);
            }
        }
        try {
            long v = Long.parseLong(t.trim());
            if (v < 0) {
                throw new NumberFormatException();
            }
            return Math.multiplyExact(v, mult);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new IllegalArgumentException("Invalid size: " + s);
        }
    }

    static String describe(IOException e) {
        String type = e.getClass().getSimpleName();
        return switch (type) {
            case "NoSuchFileException" -> "no such file or directory: " + e.getMessage();
            case "NotDirectoryException" -> "not a directory: " + e.getMessage();
            case "AccessDeniedException" -> "permission denied";
            default -> e.getMessage() == null ? type : e.getMessage();
        };
    }
}
