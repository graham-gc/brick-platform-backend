package apiworkflow;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.ansi.AnsiColor;
import org.springframework.boot.ansi.AnsiOutput;
import org.springframework.boot.ansi.AnsiStyle;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BrickApplication {

    private static final String BRICK_LOGO = String.join(System.lineSeparator(),
            "██████╗ ██████╗ ██╗ ██████╗██╗  ██╗",
            "██╔══██╗██╔══██╗██║██╔════╝██║ ██╔╝",
            "██████╔╝██████╔╝██║██║     █████╔╝ ",
            "██╔══██╗██╔══██╗██║██║     ██╔═██╗ ",
            "██████╔╝██║  ██║██║╚██████╗██║  ██╗",
            "╚═════╝ ╚═╝  ╚═╝╚═╝ ╚═════╝╚═╝  ╚═╝");

    public static void main(String[] args) {
        AnsiOutput.setEnabled(AnsiOutput.Enabled.ALWAYS);

        SpringApplication application = new SpringApplication(BrickApplication.class);
        application.setBannerMode(Banner.Mode.CONSOLE);
        application.setBanner((environment, sourceClass, out) -> {
            String springBootVersion = SpringBootVersion.getVersion();

            out.println();
            out.println(AnsiOutput.toString(AnsiStyle.BOLD, AnsiColor.CYAN, BRICK_LOGO));
            out.println(AnsiOutput.toString(AnsiColor.MAGENTA,
                    "╔══════════════════════════════════════════════════════╗"));
            out.println(AnsiOutput.toString(AnsiColor.MAGENTA,
                    "║          API WORKFLOW AUTOMATION PLATFORM            ║"));
            out.println(AnsiOutput.toString(AnsiColor.MAGENTA,
                    "╠══════════════════════════════════════════════════════╣"));
            out.println(AnsiOutput.toString(AnsiColor.MAGENTA,
                    "║       Swagger  →  Compose  →  Execute  →  Report     ║"));
            out.println(AnsiOutput.toString(AnsiColor.MAGENTA,
                    "╚══════════════════════════════════════════════════════╝"));
            out.println(AnsiOutput.toString(AnsiColor.GREEN,
                    "              Built to test. Designed to scale."));
            out.println(AnsiOutput.toString(AnsiColor.DEFAULT,
                    "              Powered by Spring Boot " + springBootVersion));
            out.println();
        });
        application.run(args);
    }
}
