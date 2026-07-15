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

    /** 프로세스 진입점 — 서브커맨드를 파싱·실행하고 종료 코드로 프로세스를 종료한다. */
    public static void main(String[] args) {
        CommandLine cli = new CommandLine(new Main());
        cli.setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
            System.err.println("[오류] " + ex.getMessage());
            return 1;
        });
        System.exit(cli.execute(args));
    }

    /** 서브커맨드 없이 호출됐을 때 사용법을 출력한다. */
    @Override
    public void run() {
        new CommandLine(this).usage(System.out);
    }
}
