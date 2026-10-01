package io42630.rankbot.browser;

import org.openqa.selenium.By;

public final class Selectors {
	private Selectors() {
	}

	public static final By LOGIN = By.cssSelector("input[type=password], [data-rankbot-access=login]");
	public static final By BLOCK =
			By.cssSelector("[data-rankbot-access=blocked], iframe[src*='captcha'], .g-recaptcha");
	public static final By READY =
			By.cssSelector("[data-rankbot-ready], .challenges-list, .challenge-body-html, .challenge-text");
	public static final By LINKS = By.cssSelector("a[href*='challenges/']");
	public static final By NEXT = By.cssSelector("a[rel=next], button[aria-label='Next page'], [data-rankbot-next]");
	public static final By LANGUAGE =
			By.cssSelector("select[data-rankbot-language], select[name=language], select#language");
	public static final By STATEMENT =
			By.cssSelector("[data-rankbot-statement], .challenge-body-html, .challenge-text");
	public static final By INPUT = By.cssSelector("[data-rankbot-input], .challenge_input_format");
	public static final By OUTPUT = By.cssSelector("[data-rankbot-output], .challenge_output_format");
	public static final By CONSTRAINTS = By.cssSelector("[data-rankbot-constraints], .challenge_constraints");
	public static final By EXAMPLES = By.cssSelector(
			"[data-rankbot-examples], .challenge_sample_input, .challenge_sample_output, .challenge_explanation");
	public static final By TEXT_EDITOR =
			By.cssSelector("textarea[data-rankbot-editor], textarea[name=code], textarea#code");
	public static final String SAMPLE_CSS = "[data-rankbot-samples], .challenge-testcases, .compile-error";
	public static final By SAMPLES = By.cssSelector(SAMPLE_CSS);
	public static final By RUN =
			By.cssSelector("button[data-rankbot-run], button.hr-monaco__run-code, button[data-analytics=RunCode]");
	public static final By SUBMIT = By.cssSelector(
			"button[data-rankbot-submit], button.hr-monaco__submit-code, button[data-analytics=SubmitCode]");
	public static final By VERDICT = By.cssSelector("[data-rankbot-verdict], [data-rankbot-submission]");
}
