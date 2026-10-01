package io42630.rankbot.browser;

import io42630.rankbot.jobs.JsonFiles;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicBoolean;

import org.openqa.selenium.*;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;

public final class HackerRankSession implements AutoCloseable {
	private final WebDriver driver;
	private final Duration timeout;
	private final BooleanSupplier handoff;
	private final boolean fixture;
	private final AtomicBoolean closed = new AtomicBoolean();
	private final Thread shutdown;

	public HackerRankSession(WebDriver driver, Duration timeout, BooleanSupplier handoff, boolean fixture) {
		if (timeout.isZero() || timeout.isNegative()) {
			throw new IllegalArgumentException("Positive browser timeout required");
		}
		this.driver = driver;
		this.timeout = timeout;
		this.handoff = handoff;
		this.fixture = fixture;
		driver.manage().timeouts().pageLoadTimeout(timeout);
		driver.manage().timeouts().scriptTimeout(timeout);
		driver.manage().timeouts().implicitlyWait(Duration.ZERO);
		shutdown = fixture
				? null
				: new Thread(
						() -> {
							try {
								close();
							} catch (WebDriverException ignored) {
							}
						}, "rankbot-browser-shutdown"
				);
		if (shutdown != null) {
			Runtime.getRuntime().addShutdownHook(shutdown);
		}
	}

	public static HackerRankSession open(Path profile, Duration timeout, BooleanSupplier handoff) throws IOException {
		return open(profile, timeout, handoff, null);
	}

	public static HackerRankSession open(Path profile, Duration timeout, BooleanSupplier handoff, Path binary)
			throws IOException {
		JsonFiles.privateDirectory(profile);
		ChromeOptions options = new ChromeOptions();
		if (binary != null) {
			options.setBinary(binary.toFile());
		}
		options.addArguments("--user-data-dir=" + profile.toAbsolutePath());
		return new HackerRankSession(new ChromeDriver(options), timeout, handoff, false);
	}

	public WebDriver driver() {
		return driver;
	}

	public boolean fixture() {
		return fixture;
	}

	public WebDriverWait waitFor() {
		return new WebDriverWait(driver, timeout, Duration.ofMillis(200));
	}

	public void navigate(String url) {
		PracticeUrls.validate(url, fixture);
		if (!fixture) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new BrowserPause("Browser navigation interrupted");
			}
		}
		try {
			driver.get(url);
		} catch (TimeoutException exception) {
			throw new BrowserPause("Navigation timed out; inspect the browser before resuming");
		}
		ensureAccessible();
	}

	public void ensureAccessible() {
		String current = driver.getCurrentUrl();
		if (!fixture && !current.matches("https://www\\.hackerrank\\.com/.*")) {
			throw new BrowserPause("Unexpected navigation; inspect the browser");
		}
		if (restricted()) {
			if (!handoff.getAsBoolean() || restricted()) {
				throw new BrowserPause("Login or access check requires manual assistance");
			}
		}
		if (!fixture) {
			try {
				PracticeUrls.validate(driver.getCurrentUrl(), false);
			} catch (IllegalArgumentException exception) {
				throw new BrowserPause("Unexpected or assessed activity is not supported");
			}
		}
	}

	private boolean restricted() {
		String url = driver.getCurrentUrl();
		if (url.contains("/auth/") || visible(Selectors.LOGIN) || visible(Selectors.BLOCK)) {
			return true;
		}
		String body = driver.findElement(By.tagName("body")).getText().toLowerCase(java.util.Locale.ROOT);
		return body.contains("too many requests") || body.contains("access denied")
			   || body.contains("verify you are human") || body.contains("temporarily blocked");
	}

	public boolean visible(By selector) {
		return driver.findElements(selector).stream().anyMatch(WebElement::isDisplayed);
	}

	@Override
	public void close() {
		if (!closed.compareAndSet(false, true)) {
			return;
		}
		try {
			driver.quit();
		} finally {
			if (shutdown != null && Thread.currentThread() != shutdown) {
				try {
					Runtime.getRuntime().removeShutdownHook(shutdown);
				} catch (IllegalStateException ignored) {
				}
			}
		}
	}
}
