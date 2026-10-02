package io42630.rankbot.browser;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;

public final class JavaContracts {
	private JavaContracts() {
	}

	public static void verify(String starter, String source) {
		Contract required = parse(starter, "Starter");
		Contract submitted = parse(source, "Submission");
		if (!required.packageName().equals(submitted.packageName())) {
			throw new IllegalArgumentException("Java package changed");
		}
		for (var entry : required.types().entrySet()) {
			TypeContract expected = entry.getValue();
			TypeContract actual = submitted.types().get(entry.getKey());
			if (actual == null || expected.kind() != actual.kind()
				|| !expected.typeParameters().equals(actual.typeParameters())
				|| actual.visibility() < expected.visibility()) {
				throw new IllegalArgumentException("Required Java type changed: " + entry.getKey());
			}
			for (var method : expected.methods().entrySet()) {
				if (method.getValue() <= 0 && !method.getKey().name().equals("main")) {
					continue;
				}
				Integer visibility = actual.methods().get(method.getKey());
				if (visibility == null || visibility < method.getValue()) {
					throw new IllegalArgumentException("Required Java signature changed: "
													   + entry.getKey() + "." + method.getKey().name());
				}
			}
		}
	}

	private static Contract parse(String source, String label) {
		if (source == null || source.isBlank()) {
			throw new IllegalArgumentException(label + " Java source is empty");
		}
		var compiler = ToolProvider.getSystemJavaCompiler();
		if (compiler == null) {
			throw new IllegalArgumentException("A JDK compiler is required to check Java contracts");
		}
		var diagnostics = new DiagnosticCollector<JavaFileObject>();
		JavaFileObject input =
				new SimpleJavaFileObject(URI.create("string:///" + label + ".java"), JavaFileObject.Kind.SOURCE) {
					@Override
					public CharSequence getCharContent(boolean ignoreEncodingErrors) {
						return source;
					}
				};
		try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
			JavacTask task = (JavacTask) compiler.getTask(
					null, files, diagnostics,
					List.of("-proc:none"), null, List.of(input)
			);
			var units = task.parse().iterator();
			if (!units.hasNext()) {
				throw new IllegalArgumentException(label + " Java source has no compilation unit");
			}
			CompilationUnitTree unit = units.next();
			if (units.hasNext() || diagnostics.getDiagnostics().stream()
					.anyMatch(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)) {
				throw new IllegalArgumentException(label + " Java source is malformed");
			}
			Map<String, TypeContract> types = new LinkedHashMap<>();
			for (Tree declaration : unit.getTypeDecls()) {
				if (declaration instanceof ClassTree type) {
					collect(type, "", false, types);
				}
			}
			if (types.isEmpty()) {
				throw new IllegalArgumentException(label + " Java source has no named types");
			}
			return new Contract(
					unit.getPackageName() == null
							? ""
							: normalize(unit.getPackageName()), types
			);
		} catch (IOException | RuntimeException exception) {
			if (exception instanceof IllegalArgumentException invalid) {
				throw invalid;
			}
			throw new IllegalArgumentException("Cannot parse " + label + " Java source", exception);
		}
	}

	private static void collect(
			ClassTree type, String owner, boolean implicitPublic,
			Map<String, TypeContract> types
	) {
		String name = owner + type.getSimpleName();
		if (type.getSimpleName().isEmpty() || types.containsKey(name)) {
			throw new IllegalArgumentException("Duplicate or unnamed Java type: " + name);
		}
		boolean interfaceType = type.getKind() == Tree.Kind.INTERFACE || type.getKind() == Tree.Kind.ANNOTATION_TYPE;
		Map<Signature, Integer> methods = new LinkedHashMap<>();
		boolean constructor = false;
		for (Tree member : type.getMembers()) {
			if (member instanceof MethodTree method) {
				constructor |= method.getReturnType() == null;
				Set<Modifier> modifiers = method.getModifiers().getFlags();
				int visibility = visibility(modifiers);
				if (interfaceType && !modifiers.contains(Modifier.PRIVATE)) {
					visibility = 2;
				}
				Signature signature = new Signature(
						method.getName().toString(),
						method.getReturnType() == null
								? ""
								: normalize(method.getReturnType()),
						method.getTypeParameters().stream().map(JavaContracts::normalize).toList(),
						method.getParameters().stream().map(parameter -> normalize(parameter.getType())).toList(),
						modifiers.contains(Modifier.STATIC)
				);
				if (methods.putIfAbsent(signature, visibility) != null) {
					throw new IllegalArgumentException("Duplicate Java signature: " + name + "." + signature.name());
				}
			}
		}
		int visibility = implicitPublic
				? 2
				: visibility(type.getModifiers().getFlags());
		if (!constructor && type.getKind() == Tree.Kind.CLASS) {
			methods.put(new Signature("<init>", "", List.of(), List.of(), false), visibility);
		}
		types.put(
				name, new TypeContract(
						type.getKind(),
						type.getTypeParameters().stream().map(JavaContracts::normalize).toList(), visibility, methods
				)
		);
		for (Tree member : type.getMembers()) {
			if (member instanceof ClassTree nested) {
				collect(nested, name + ".", interfaceType, types);
			}
		}
	}

	private static int visibility(Set<Modifier> modifiers) {
		if (modifiers.contains(Modifier.PUBLIC)) {
			return 2;
		}
		if (modifiers.contains(Modifier.PROTECTED)) {
			return 1;
		}
		if (modifiers.contains(Modifier.PRIVATE)) {
			return -1;
		}
		return 0;
	}

	private static String normalize(Tree tree) {
		return tree.toString().replaceAll("\\s+", "");
	}

	private record Contract(String packageName, Map<String, TypeContract> types) {
	}

	private record TypeContract(
			Tree.Kind kind, List<String> typeParameters, int visibility,
			Map<Signature, Integer> methods
	) {
	}

	private record Signature(
			String name, String returnType, List<String> typeParameters,
			List<String> parameterTypes, boolean isStatic
	) {
	}
}
