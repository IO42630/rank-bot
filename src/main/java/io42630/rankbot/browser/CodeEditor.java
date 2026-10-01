package io42630.rankbot.browser;

import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.Keys;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.interactions.Actions;

import io42630.rankbot.jobs.JobContracts;

public final class CodeEditor {
	private final HackerRankSession session;

	public CodeEditor(HackerRankSession session) {
		this.session = session;
	}

	public String read() {
		Object value = ((JavascriptExecutor) session.driver()).executeScript(EditorScripts.READ);
		return value instanceof String text
				? text
				: null;
	}

	public void insert(String source, String starter) {
		JobContracts.source(source);
		JavaContracts.verify(starter, source);
		session.ensureAccessible();
		boolean nativeWrite = Boolean.TRUE.equals(((JavascriptExecutor) session.driver()).executeScript(
				"""
				const value=arguments[0];
				if (window.monaco && monaco.editor) {
				  const models=monaco.editor.getModels();
				  if (models.length===1) { models[0].setValue(value); return true; }
				}
				const aceEl=document.querySelector('.ace_editor');
				if (window.ace && aceEl && aceEl.env && aceEl.env.editor) { aceEl.env.editor.setValue(value,-1); return true; }
				const cm=document.querySelector('.CodeMirror');
				if (cm && cm.CodeMirror) { cm.CodeMirror.setValue(value); return true; }
				return false;
				""", source
		));
		if (!nativeWrite) {
			WebElement element = session.waitFor().until(driver -> driver.findElements(Selectors.TEXT_EDITOR).stream()
					.filter(WebElement::isDisplayed).filter(WebElement::isEnabled).findFirst().orElse(null));
			Keys select = System.getProperty("os.name").startsWith("Mac")
					? Keys.COMMAND
					: Keys.CONTROL;
			new Actions(session.driver()).click(element).keyDown(select).sendKeys("a").keyUp(select)
					.sendKeys(Keys.BACK_SPACE).sendKeys(source).perform();
		}
		session.waitFor().until(driver -> normalize(source).equals(normalize(read())));
	}

	static String normalize(String value) {
		return value == null
				? ""
				: value.replace("\r\n", "\n").replace('\r', '\n');
	}
}
