package io42630.rankbot.browser;

final class EditorScripts {
	private EditorScripts() {
	}

	static final String READ = """
							   if (window.monaco && monaco.editor) {
							     const models = monaco.editor.getModels();
							     if (models.length === 1) return models[0].getValue();
							   }
							   const aceEl = document.querySelector('.ace_editor');
							   if (window.ace && aceEl && aceEl.env && aceEl.env.editor) return aceEl.env.editor.getValue();
							   const cm = document.querySelector('.CodeMirror');
							   if (cm && cm.CodeMirror) return cm.CodeMirror.getValue();
							   const textarea = document.querySelector('textarea[data-rankbot-editor], textarea[name=code], textarea#code');
							   return textarea ? textarea.value : null;
							   """;
}
