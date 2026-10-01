package io42630.rankbot.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserFixtureTest {
	@TempDir
	Path directory;
	WebDriver driver;

	@BeforeEach
	void open() {
		ChromeOptions options = new ChromeOptions().addArguments("--headless=new", "--disable-dev-shm-usage");
		String binary = System.getProperty("rankbot.chrome");
		if (binary != null) {
			options.setBinary(binary);
		}
		driver = new ChromeDriver(options);
	}

	@AfterEach
	void close() {
		if (driver != null) {
			driver.quit();
		}
	}

	Path fixture(String file, String html) throws Exception {
		Path path = directory.resolve(file);
		Files.createDirectories(path.getParent());
		Files.writeString(path, "<!doctype html><html><body>" + html + "</body></html>");
		return path;
	}

	HackerRankSession session() {
		return new HackerRankSession(driver, Duration.ofSeconds(1), () -> false, true);
	}

	@Test
	void paginationLazyLoadingAndDeduplicationPreserveOrder() throws Exception {
		Path second = fixture(
				"page2.html",
				"<a href='challenges/b/problem.html'>B duplicate</a><a href='challenges/c/problem.html'>C</a>"
		);
		Path first = fixture(
				"page1.html", """
							  <a href='challenges/a/problem.html'>A</a><a href='challenges/a/problem.html'>duplicate</a>
							  <a href='https://www.hackerrank.com/contests/test/challenges/no/problem'>Forbidden</a>
							  <a rel='next' href='page2.html'>Next</a><div style='height:2000px'></div>
							  <script>window.addEventListener('scroll', () => {
							    if (!window.loaded) { window.loaded=true; const a=document.createElement('a');
							    a.href='challenges/b/problem.html'; a.textContent='B'; document.body.appendChild(a); }
							  });</script>
							  """
		);
		var challenges = new TrackPage(session(), 5).discover(first.toUri().toString());
		assertEquals(List.of("a", "b", "c"), challenges.stream().map(TrackPage.Challenge::id).toList());
		assertTrue(challenges.getFirst().url().endsWith("challenges/a/problem.html"));
	}

	@Test
	void extractionPreservesContractsAndRejectsIncompleteOrNonJava() throws Exception {
		String html = """
					  <article data-rankbot-statement>Print the input.</article>
					  <div data-rankbot-input>One integer</div><div data-rankbot-output>The same integer</div>
					  <div data-rankbot-constraints>0 &lt;= n &lt;= 10</div><div data-rankbot-examples>Input: 2 Output: 2</div>
					  <select data-rankbot-language><option>Python 3</option><option>Java 17</option></select>
					  <textarea data-rankbot-editor>public class Solution { public static void main(String[] args) {} }</textarea>
					  """;
		var page = new ChallengePage(session());
		var ready = page.extract(fixture("challenge.html", html).toUri().toString());
		assertEquals(ChallengePage.ExtractionStatus.READY, ready.status());
		assertEquals("Java 17", ready.inputs().language());
		assertTrue(ready.inputs().starterCode().contains("main(String[] args)"));
		assertTrue(ready.inputs().statement().contains("0 <= n <= 10"));
		assertTrue(ready.inputs().statement().contains("Input: 2 Output: 2"));
		assertEquals(
				ChallengePage.ExtractionStatus.UNSUPPORTED,
				page.extract(fixture("python.html", html.replace("<option>Java 17</option>", "")).toUri().toString())
						.status()
		);
		assertEquals(
				ChallengePage.ExtractionStatus.INCOMPLETE,
				page.extract(fixture("missing.html", html.replace("data-rankbot-constraints", "missing")).toUri()
						.toString()).status()
		);
	}

	@Test
	void manualHandoffExpiryAndBlocksDoNotBypass() throws Exception {
		Path login = fixture("login.html", "<input type='password'><div data-rankbot-ready>ready</div>");
		AtomicInteger calls = new AtomicInteger();
		var assisted = new HackerRankSession(
				driver, Duration.ofSeconds(1), () -> {
			calls.incrementAndGet();
			((JavascriptExecutor) driver).executeScript("document.querySelector('input').remove()");
			return true;
		}, true
		);
		assisted.navigate(login.toUri().toString());
		assertEquals(1, calls.get());
		((JavascriptExecutor) driver).executeScript("document.body.innerHTML += '<input type=password>'");
		assisted.ensureAccessible();
		assertEquals(2, calls.get());
		assertThrows(
				BrowserPause.class,
				() -> session().navigate(fixture(
						"blocked.html",
						"<div data-rankbot-access='blocked'>Too many requests</div>"
				).toUri().toString())
		);
		assertThrows(
				BrowserPause.class,
				() -> new TrackPage(session(), 2).discover(fixture("empty.html", "empty").toUri().toString())
		);
	}

	@Test
	void practiceUrlAllowlistRejectsAssessedOrForeignActivity() {
		assertThrows(
				IllegalArgumentException.class,
				() -> PracticeUrls.validate("https://www.hackerrank.com/contests/x", false)
		);
		assertThrows(
				IllegalArgumentException.class,
				() -> PracticeUrls.validate("https://www.hackerrank.com/tests/x", false)
		);
		assertThrows(
				IllegalArgumentException.class,
				() -> PracticeUrls.validate("https://evil.example/challenges/x/problem", false)
		);
		assertThrows(IllegalArgumentException.class, () -> PracticeUrls.validate("file:///local", false));
		assertEquals(
				"x",
				PracticeUrls.challengeId("https://www.hackerrank.com/challenges/x/problem?foo=1", false).orElseThrow()
		);
	}

	@Test
	void sessionShutdownIsIdempotent() throws Exception {
		var session = session();
		session.navigate(fixture("shutdown.html", "<div data-rankbot-ready>ready</div>").toUri().toString());
		session.close();
		session.close();
		assertThrows(org.openqa.selenium.NoSuchSessionException.class, driver::getTitle);
		driver = null;
	}

	@Test
	void editorNativeAndKeyboardInsertionReadBackExactly() throws Exception {
		String starter = "public class Solution { public static void main(String[] args) {} }";
		String source =
				"public class Solution { public static void main(String[] args) { System.out.println(\"hi\"); } }";
		session().navigate(fixture("textarea.html", "<textarea data-rankbot-editor>" + starter + "</textarea>").toUri()
				.toString());
		var editor = new CodeEditor(session());
		editor.insert(source, starter);
		assertEquals(source, editor.read());
		session().navigate(fixture(
				"native.html",
				"<script>window.value='class Solution {}'; window.monaco={editor:{getModels:()=>[{getValue:()=>window"
				+ ".value,setValue:v=>window.value=v}]}};</script>"
		).toUri().toString());
		editor.insert(source, starter);
		assertEquals(source, editor.read());
	}

	@Test
	void freshSamplesNeverSubmitAndStalePassCannotEnableSubmission() throws Exception {
		for (String feedback : List.of(
				"All sample test cases passed",
				"Compilation Error: missing symbol",
				"Runtime Error: exception",
				"Wrong Answer: expected 2"
		)) {
			session().navigate(fixture(
					"samples.html",
					"<textarea data-rankbot-editor>class Solution {}</textarea><div data-rankbot-samples>All sample "
					+ "test cases passed</div>"
					+ "<button data-rankbot-run onclick=\"document.querySelector('[data-rankbot-samples]')"
					+ ".textContent='"
					+ feedback
					+ "'\">Run</button>"
					+ "<button data-rankbot-submit onclick='window.submits=(window.submits||0)+1'>Submit</button>"
			).toUri().toString());
			var result = new ChallengePage(session()).runSamples("class Solution {}", "class Solution {}");
			assertEquals(ChallengePage.parseSamples(feedback).status(), result.status());
			assertEquals(0L, ((JavascriptExecutor) driver).executeScript("return window.submits||0"));
		}
		session().navigate(fixture(
				"stale.html",
				"<textarea data-rankbot-editor>class Solution {}</textarea><div data-rankbot-samples>All sample test "
				+ "cases passed</div><button data-rankbot-run>Run</button>"
		).toUri().toString());
		var page = new ChallengePage(session());
		assertEquals(
				ChallengePage.SampleStatus.UNKNOWN,
				page.runSamples("class Solution {}", "class Solution {}").status()
		);
		assertThrows(
				BrowserPause.class,
				() -> page.submit(new io42630.rankbot.workflow.SubmissionGuard.Intent(
						"challenge",
						"class Solution {}",
						java.time.Instant.now(),
						""
				))
		);
	}

	@Test
	void submissionAndReconciliationRequireMatchingEvidence() throws Exception {
		var intent = new io42630.rankbot.workflow.SubmissionGuard.Intent(
				"challenge",
				"class Solution {}",
				java.time.Instant.now().minusSeconds(2),
				""
		);
		String evidence =
				"<div data-rankbot-verdict data-challenge-id='challenge' data-submission-id='123' "
				+ "data-status='accepted' data-source-sha256='"
				+ intent.sourceHash() + "' data-submitted-at='" + java.time.Instant.now() + "'>Accepted</div>";
		Path history = fixture("history.html", evidence);
		var page = new ChallengePage(session());
		assertEquals(
				ChallengePage.VerdictStatus.ACCEPTED,
				page.reconcile(history.toUri().toString(), intent).status()
		);
		assertEquals(
				ChallengePage.VerdictStatus.PENDING,
				page.reconcile(
						fixture(
								"pending.html",
								evidence.replace("data-status='accepted'", "data-status='pending'")
						).toUri().toString(), intent
				).status()
		);
		assertEquals(
				ChallengePage.VerdictStatus.FAILED,
				page.reconcile(
						fixture(
								"failed.html",
								evidence.replace("data-status='accepted'", "data-status='wrong answer'")
						).toUri().toString(), intent
				).status()
		);
		assertEquals(
				ChallengePage.VerdictStatus.UNKNOWN,
				page.reconcile(fixture("ambiguous.html", evidence + evidence).toUri().toString(), intent).status()
		);
		assertEquals(
				ChallengePage.VerdictStatus.UNKNOWN,
				page.reconcile(
						fixture("wrong.html", evidence.replace(intent.sourceHash(), "wrong")).toUri().toString(),
						intent
				).status()
		);
		assertEquals(
				ChallengePage.VerdictStatus.UNKNOWN,
				page.reconcile(
						fixture(
								"old.html",
								evidence.replace("data-submitted-at='", "data-unused='") + "<div>old</div>"
						).toUri().toString(), intent
				).status()
		);
		assertEquals(0L, ((JavascriptExecutor) driver).executeScript("return window.submits||0"));
		String submitHtml = "<textarea data-rankbot-editor>class Solution {}</textarea><div "
							+ "data-rankbot-samples></div>"
							+ "<button data-rankbot-run onclick=\"document.querySelector('[data-rankbot-samples]')"
							+ ".textContent='All sample test cases passed'\">Run</button>"
							+ "<button data-rankbot-submit onclick=\"window.submits=(window.submits||0)+1;document"
							+ ".querySelector('#history').innerHTML=document.querySelector('#evidence')"
							+ ".innerHTML\">Submit</button>"
							+ "<div id=history></div><template id=evidence>" + evidence + "</template>";
		session().navigate(fixture("submit.html", submitHtml).toUri().toString());
		assertEquals(
				ChallengePage.SampleStatus.PASSED,
				page.runSamples("class Solution {}", "class Solution {}").status()
		);
		assertEquals(ChallengePage.VerdictStatus.ACCEPTED, page.submit(intent).status());
		assertEquals(1L, ((JavascriptExecutor) driver).executeScript("return window.submits"));
	}
}
