package io42630.rankbot.browser;

import java.nio.file.Path;

import com.olexyn.tabdriver.DefaultTabDriverConfig;
import org.openqa.selenium.chrome.ChromeDriverService;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.remote.service.DriverFinder;

final class RankBotTabDriverConfig extends DefaultTabDriverConfig {
	private final Path profile;
	private final boolean headless;
	private final ChromeOptions options;
	private final Path driverPath;

	RankBotTabDriverConfig(Path profile, Path binary, boolean headless) {
		this.profile = profile.toAbsolutePath();
		this.headless = headless;
		options = super.getOptions();
		options.setAcceptInsecureCerts(false);
		options.addArguments("--user-data-dir=" + this.profile);
		if (binary != null) {
			options.setBinary(binary.toFile());
		}
		var finder = new DriverFinder(ChromeDriverService.createDefaultService(), options);
		driverPath = Path.of(finder.getDriverPath());
		if (binary == null && finder.hasBrowserPath()) {
			options.setBinary(finder.getBrowserPath());
		}
	}

	@Override
	public Path getDriverPath() {
		return driverPath;
	}

	@Override
	public String getDownloadDir() {
		return profile.resolve("downloads").toString();
	}

	@Override
	public boolean isHeadless() {
		return headless;
	}

	@Override
	public ChromeOptions getOptions() {
		return options;
	}
}