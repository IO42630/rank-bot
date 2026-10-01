package io42630.rankbot.browser;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.TimeoutException;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.support.ui.Select;

import io42630.rankbot.jobs.JobContracts;
import io42630.rankbot.workflow.SubmissionGuard.Intent;

public final class ChallengePage {
	public enum ExtractionStatus {
		READY,
		UNSUPPORTED,
		INCOMPLETE
	}

	public record Inputs(String language, String statement, String starterCode) {
	}

	public record Extraction(ExtractionStatus status, Inputs inputs, String diagnostic) {
	}

	public enum SampleStatus {
		PASSED,
		COMPILE_ERROR,
		RUNTIME_ERROR,
		WRONG_ANSWER,
		INVALID_SOURCE,
		UNKNOWN
	}

	public record Sample(SampleStatus status, String feedback) {
	}

	public enum VerdictStatus {
		ACCEPTED,
		FAILED,
		PENDING,
		UNKNOWN
	}

	public record Verdict(VerdictStatus status, String feedback, String submissionId) {
	}

	private final HackerRankSession session;
	private String passedSource;

	public ChallengePage(HackerRankSession session) {
		this.session = session;
	}

	public Extraction extract(String url) {
		passedSource = null;
		session.navigate(url);
		try {
			session.waitFor().until(driver -> {
				session.ensureAccessible();
				return session.visible(Selectors.STATEMENT);
			});
			var elements = session.driver().findElements(Selectors.LANGUAGE);
			if (elements.isEmpty()) {
				return new Extraction(ExtractionStatus.INCOMPLETE, null, "Java language control not recognized");
			}
			Select select = new Select(elements.getFirst());
			var java = select.getOptions().stream().filter(option -> option.getText().matches("(?i)Java(?:\\s+.*)?"))
					.findFirst();
			if (java.isEmpty()) {
				return new Extraction(ExtractionStatus.UNSUPPORTED, null, "No Java runtime available");
			}
			if (!select.getFirstSelectedOption().getText().matches("(?i)Java(?:\\s+.*)?")) {
				select.selectByVisibleText(java.get().getText());
			}
			String language = select.getFirstSelectedOption().getText();
			String starter = session.waitFor().until(driver -> {
				Object value = ((JavascriptExecutor) driver).executeScript(EditorScripts.READ);
				return value instanceof String text && !text.isBlank()
						? text
						: null;
			});
			String statement = text(Selectors.STATEMENT);
			String input = text(Selectors.INPUT), output = text(Selectors.OUTPUT);
			String constraints = text(Selectors.CONSTRAINTS), examples = text(Selectors.EXAMPLES);
			if (statement.isBlank()
				|| input.isBlank()
				|| output.isBlank()
				|| constraints.isBlank()
				|| examples.isBlank()
				|| session.visible(By.cssSelector(
					"[data-rankbot-incomplete], [aria-expanded=false][data-rankbot-statement]"))) {
				return new Extraction(ExtractionStatus.INCOMPLETE, null, "Missing or truncated statement sections");
			}
			JobContracts.source(starter);
			JavaContracts.verify(starter, starter);
			String complete = statement + "\n\nInput format\n" + input + "\n\nOutput format\n" + output
							  + "\n\nConstraints\n" + constraints + "\n\nExamples\n" + examples;
			if (complete.length() > 500_000) {
				return new Extraction(ExtractionStatus.INCOMPLETE, null, "Statement exceeds protocol bound");
			}
			return new Extraction(ExtractionStatus.READY, new Inputs(language, complete, starter), "");
		} catch (TimeoutException | IllegalArgumentException exception) {
			return new Extraction(
					ExtractionStatus.INCOMPLETE,
					null,
					"Statement, runtime, or starter code could not be extracted completely"
			);
		}
	}

	public Sample runSamples(String source, String starter) {
		passedSource = null;
		session.ensureAccessible();
		try {
			new CodeEditor(session).insert(source, starter);
			((JavascriptExecutor) session.driver()).executeScript(
					"""
					if (window.__rankbotSampleObserver) window.__rankbotSampleObserver.disconnect();
					window.__rankbotSampleEpoch=0;
					const selector=arguments[0];
					const relevant=node => node && node.nodeType===1 &&
					  (node.matches(selector) || node.closest(selector));
					const contains=node => relevant(node) || (node && node.nodeType===1 && node.querySelector(selector));
					window.__rankbotSampleObserver=new MutationObserver(mutations => {
					  if(mutations.some(m => relevant(m.target.nodeType===1 ? m.target : m.target.parentElement) ||
					     Array.from(m.addedNodes).some(contains) || Array.from(m.removedNodes).some(contains)))
					     window.__rankbotSampleEpoch++;
					});
					window.__rankbotSampleObserver.observe(document.body,{subtree:true,childList:true,characterData:true,attributes:true});
					""", Selectors.SAMPLE_CSS
			);
			session.waitFor()
					.until(driver -> driver.findElements(Selectors.RUN).stream().filter(WebElement::isDisplayed)
							.filter(WebElement::isEnabled).findFirst().orElse(null)).click();
			Sample sample = session.waitFor().until(driver -> {
				session.ensureAccessible();
				if (((Number) ((JavascriptExecutor) driver).executeScript("return window.__rankbotSampleEpoch || 0")).intValue()
					== 0) {
					return null;
				}
				Sample result = parseSamples(text(Selectors.SAMPLES));
				return result.status() == SampleStatus.UNKNOWN
						? null
						: result;
			});
			if (sample.status() == SampleStatus.PASSED && CodeEditor.normalize(source)
					.equals(CodeEditor.normalize(new CodeEditor(session).read()))) {
				passedSource = source;
			} else if (sample.status() == SampleStatus.PASSED) {
				return new Sample(SampleStatus.UNKNOWN, "Editor changed during samples");
			}
			return sample;
		} catch (IllegalArgumentException exception) {
			return new Sample(
					SampleStatus.INVALID_SOURCE,
					"Generated source does not preserve the starter Java contract"
			);
		} catch (TimeoutException exception) {
			return new Sample(SampleStatus.UNKNOWN, "Editor or fresh sample feedback timed out; submission disabled");
		}
	}

	public Verdict submit(Intent intent) {
		session.ensureAccessible();
		if (passedSource == null || !passedSource.equals(intent.sourceCode())
			|| !CodeEditor.normalize(passedSource).equals(CodeEditor.normalize(new CodeEditor(session).read()))) {
			throw new BrowserPause("Submission requires matching, verified sample-passing source");
		}
		Set<String> oldIds = new HashSet<>();
		String[] observedId = { "" };
		session.driver().findElements(Selectors.VERDICT)
				.forEach(element -> oldIds.add(element.getDomAttribute("data-submission-id")));
		try {
			session.waitFor()
					.until(driver -> driver.findElements(Selectors.SUBMIT).stream().filter(WebElement::isDisplayed)
							.filter(WebElement::isEnabled).findFirst().orElse(null)).click();
			passedSource = null;
			return session.waitFor().until(driver -> {
				session.ensureAccessible();
				List<WebElement> matching =
						driver.findElements(Selectors.VERDICT).stream().filter(WebElement::isDisplayed)
								.filter(element -> intent.challengeId()
										.equals(element.getDomAttribute("data-challenge-id")))
								.filter(element -> nonempty(element.getDomAttribute("data-submission-id")))
								.filter(element -> !oldIds.contains(element.getDomAttribute("data-submission-id")))
								.toList();
				if (matching.size() != 1) {
					return null;
				}
				Verdict result = verdict(matching.getFirst());
				observedId[0] = result.submissionId();
				return result.status() == VerdictStatus.PENDING || result.status() == VerdictStatus.UNKNOWN
						? null
						: result;
			});
		} catch (TimeoutException exception) {
			return reconcileVisible(new Intent(
					intent.challengeId(),
					intent.sourceCode(),
					intent.createdAt(),
					observedId[0]
			));
		}
	}

	public Verdict reconcile(String challengeUrl, Intent intent) {
		String history = session.fixture()
				? challengeUrl
				: "https://www.hackerrank.com/challenges/" + intent.challengeId() + "/submissions";
		session.navigate(history);
		return reconcileVisible(intent);
	}

	private Verdict reconcileVisible(Intent intent) {
		session.ensureAccessible();
		List<WebElement> matching =
				session.driver().findElements(Selectors.VERDICT).stream().filter(WebElement::isDisplayed)
						.filter(element -> intent.challengeId().equals(element.getDomAttribute("data-challenge-id")))
						.filter(element -> nonempty(element.getDomAttribute("data-submission-id")))
						.filter(element -> {
							if (nonempty(intent.submissionId())) {
								return intent.submissionId().equals(element.getDomAttribute("data-submission-id"));
							}
							if (!intent.sourceHash().equals(element.getDomAttribute("data-source-sha256"))) {
								return false;
							}
							try {
								return !Instant.parse(element.getDomAttribute("data-submitted-at"))
										.isBefore(intent.createdAt());
							} catch (RuntimeException exception) {
								return false;
							}
						}).toList();
		if (matching.size() != 1) {
			return new Verdict(
					VerdictStatus.UNKNOWN,
					"Submission evidence missing or ambiguous; manual resolution required",
					""
			);
		}
		return verdict(matching.getFirst());
	}

	public static Sample parseSamples(String feedback) {
		String lower = feedback.toLowerCase(Locale.ROOT);
		SampleStatus status = lower.contains("compilation error") || lower.contains("compile error")
				? SampleStatus.COMPILE_ERROR
				: lower.contains("runtime error")
						? SampleStatus.RUNTIME_ERROR
						: lower.contains("wrong answer")
								? SampleStatus.WRONG_ANSWER
								: lower.lines().anyMatch(line -> line.strip().equals("all sample test cases passed"))
										? SampleStatus.PASSED
										: SampleStatus.UNKNOWN;
		return new Sample(status, bounded(feedback));
	}

	private static Verdict verdict(WebElement element) {
		String status = Optional.ofNullable(element.getDomAttribute("data-status")).orElse(element.getText()).strip()
				.toLowerCase(Locale.ROOT);
		VerdictStatus parsed = switch (status) {
			case "accepted" -> VerdictStatus.ACCEPTED;
			case "wrong answer", "compilation error", "runtime error", "time limit exceeded", "memory limit exceeded",
				 "failed" -> VerdictStatus.FAILED;
			case "pending", "submitted", "running", "processing" -> VerdictStatus.PENDING;
			default -> VerdictStatus.UNKNOWN;
		};
		return new Verdict(parsed, bounded(element.getText()), element.getDomAttribute("data-submission-id"));
	}

	private static boolean nonempty(String value) {
		return value != null && !value.isBlank();
	}

	private static String bounded(String value) {
		return value.substring(0, Math.min(value.length(), 100_000));
	}

	private String text(By selector) {
		return String.join(
				"\n",
				session.driver().findElements(selector).stream().filter(WebElement::isDisplayed)
						.map(WebElement::getText).toList()
		);
	}
}
