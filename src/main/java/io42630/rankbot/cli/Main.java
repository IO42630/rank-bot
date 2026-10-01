package io42630.rankbot.cli;

import picocli.CommandLine;

@CommandLine.Command(name = "rank-bot",
					 mixinStandardHelpOptions = true,
					 subcommands = { BrowserCommand.class, WorkerCommand.class },
					 description = "Java practice automation; never for assessed competitions.")
public final class Main {
	static void main(String[] args) {
		var command = new CommandLine(new Main());
		command.setExecutionExceptionHandler((exception, cli, parseResult) -> {
			if (exception instanceof IllegalArgumentException || exception instanceof IllegalStateException
				|| exception instanceof io42630.rankbot.browser.BrowserPause) {
				cli.getErr().println(exception.getMessage());
			} else {
				cli.getErr().println("Operation failed ("
									 + exception.getClass().getSimpleName()
									 + "); inspect configuration and resume from durable progress.");
			}
			return 1;
		});
		System.exit(command.execute(args));
	}
}
