package io42630.rankbot.browser;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

public final class PracticeUrls {
	private static final Pattern CHALLENGE =
			Pattern.compile("/challenges/([A-Za-z0-9][A-Za-z0-9_-]{0,127})(?:/(?:problem|submissions))?/?");

	private PracticeUrls() {
	}

	public static void validate(String url, boolean fixture) {
		URI uri = URI.create(url);
		if (fixture && "file".equals(uri.getScheme())) {
			return;
		}
		if (!"https".equals(uri.getScheme()) || !"www.hackerrank.com".equals(uri.getHost())
			|| uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
			|| !(uri.getPath().matches("/domains/java(?:/[A-Za-z0-9_-]+)*/?")
				 || CHALLENGE.matcher(uri.getPath()).matches())) {
			throw new IllegalArgumentException("Only HackerRank Java-track and practice challenge URLs are allowed");
		}
	}

	public static Optional<String> challengeId(String url, boolean fixture) {
		try {
			URI uri = URI.create(url);
			if (fixture && "file".equals(uri.getScheme())) {
				var match = Pattern.compile(".*/challenges/([A-Za-z0-9_-]+)/problem\\.html").matcher(uri.getPath());
				return match.matches()
						? Optional.of(match.group(1))
						: Optional.empty();
			}
			validate(url, false);
			var match = CHALLENGE.matcher(uri.getPath());
			return match.matches()
					? Optional.of(match.group(1))
					: Optional.empty();
		} catch (IllegalArgumentException exception) {
			return Optional.empty();
		}
	}
}
