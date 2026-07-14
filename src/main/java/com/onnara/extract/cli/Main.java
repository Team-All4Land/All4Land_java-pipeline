package com.onnara.extract.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * CLI 진입점 — {@code java -jar extract.jar <서브커맨드> [옵션]} (§8).
 */
@Command(
        name = "extract",
        mixinStandardHelpOptions = true,
        version = "extract-pipeline 1.0.0",
        description = "공유수면 점용·사용 고시류 문서 추출 파이프라인",
        subcommands = {
                PipelineCommand.class,
                DetectCommand.class,
                ExtractCommand.class,
                MapCommand.class,
                LoadCommand.class,
        })
public class Main implements Runnable {

    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            System.err.println("[오류] " + ex.getMessage());
            return 1;
        });
        System.exit(cli.execute(args));
    }

    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
