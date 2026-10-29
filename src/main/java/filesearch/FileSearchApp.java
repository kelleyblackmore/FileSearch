package filesearch;

import filesearch.ui.SearchFrame;

import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point. Opens the GUI when started without arguments (or with {@code --gui});
 * otherwise runs as a command-line tool.
 */
public final class FileSearchApp {

    private FileSearchApp() {
    }

    public static void main(String[] args) {
        boolean gui = args.length == 0 || Arrays.asList(args).contains("--gui");
        if (gui && !GraphicsEnvironment.isHeadless()) {
            SwingUtilities.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                } catch (Exception ignored) {
                    // Fall back to the default look and feel.
                }
                new SearchFrame().setVisible(true);
            });
            return;
        }
        String[] cliArgs = Arrays.stream(args).filter(a -> !a.equals("--gui")).toArray(String[]::new);
        System.exit(new CommandLine(System.out, System.err).run(cliArgs));
    }
}
