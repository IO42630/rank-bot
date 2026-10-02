package io42630.rankbot.browser;

import java.net.URI;
import java.util.*;

import org.openqa.selenium.*;

public final class TrackPage {
	public record Challenge(String id, String url) {
	}

	private final HackerRankSession session;
	private final int pageLimit;

	public TrackPage(HackerRankSession session, int pageLimit) {
		if (pageLimit < 1 || pageLimit > 1000) {
			throw new IllegalArgumentException("pageLimit must be 1..1000");
		}
		this.session = session;
		this.pageLimit = pageLimit;
	}

	public List<Challenge> discover(String trackUrl) {
		session.navigate(trackUrl);
		LinkedHashMap<String, Challenge> queue = new LinkedHashMap<>();
		Set<String> pages = new HashSet<>();
		for (int page = 0; page < pageLimit; page++) {
			session.ensureAccessible();
			try {
				session.waitFor().until(driver -> !driver.findElements(Selectors.LINKS).isEmpty());
			} catch (TimeoutException exception) {
				throw new BrowserPause("Track empty, inaccessible, or selectors unsupported");
			}
			List<String> links = links();
			String signature = session.driver().getCurrentUrl() + links;
			if (!pages.add(signature)) {
				throw new BrowserPause("Track pagination did not advance");
			}
			collect(queue, links);
			for (int scroll = 0; scroll < 100; scroll++) {
				int before = links.size();
				((JavascriptExecutor) session.driver()).executeScript("window.scrollTo(0, document.body"
																	  + ".scrollHeight)");
				try {
					session.waitFor().until(driver -> {
						session.ensureAccessible();
						return links().size() > before;
					});
				} catch (TimeoutException done) {
					break;
				}
				links = links();
				collect(queue, links);
				if (scroll == 99) {
					throw new BrowserPause("Lazy-loading safety bound reached");
				}
			}
			var next = session.driver().findElements(Selectors.NEXT).stream()
					.filter(WebElement::isDisplayed).filter(WebElement::isEnabled)
					.filter(element -> !"true".equals(element.getDomAttribute("aria-disabled")))
					.filter(element -> !Optional.ofNullable(element.getDomAttribute("class")).orElse("")
							.contains("disabled"))
					.findFirst();
			if (next.isEmpty()) {
				if (queue.isEmpty()) {
					throw new BrowserPause("No accessible practice challenges found");
				}
				return List.copyOf(queue.values());
			}
			String href = next.get().getAttribute("href");
			if (href != null && !href.isBlank()) {
				session.navigate(href);
			} else {
				List<String> previous = links;
				next.get().click();
				try {
					session.waitFor().until(driver -> {
						session.ensureAccessible();
						return !links().equals(previous);
					});
				} catch (TimeoutException exception) {
					throw new BrowserPause("Track pagination timed out");
				}
			}
		}
		throw new BrowserPause("Track page safety bound reached; discovery was not complete");
	}

	private List<String> links() {
		return session.driver().findElements(Selectors.LINKS).stream().map(element -> element.getAttribute("href"))
				.filter(Objects::nonNull).toList();
	}

	private void collect(Map<String, Challenge> queue, List<String> links) {
		for (String url : links) {
			PracticeUrls.challengeId(url, session.fixture()).ifPresent(id -> {
				String canonical = session.fixture()
						? URI.create(url).resolve("problem.html").toString()
						: "https://www.hackerrank.com/challenges/" + id + "/problem";
				queue.putIfAbsent(id, new Challenge(id, canonical));
			});
		}
	}
}
