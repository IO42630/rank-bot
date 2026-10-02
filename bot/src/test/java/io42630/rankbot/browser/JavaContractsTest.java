package io42630.rankbot.browser;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JavaContractsTest {
	private static final String STARTER = """
										  class Result {
										      public static int solve(int value) { return 0; }
										      protected String solve(String value) { return null; }
										  }
										  public class Solution {
										      public static void main(String[] args) throws Exception {}
										  }
										  """;

	@TempDir
	Path directory;

	@Test
	void acceptsImplementationAndParameterNameChanges() {
		assertDoesNotThrow(() -> JavaContracts.verify(
				STARTER, """
						 class Result {
						     public static int solve(int renamed) { return renamed * 2; }
						     public String solve(String renamed) { return renamed.trim(); }
						     private void helper() {}
						 }
						 public class Solution {
						     public static void main(String [] renamed) { System.out.println(Result.solve(2)); }
						 }
						 class Extra {}
						 """
		));
	}

	@Test
	void rejectsMissingOrRenamedTypesIncludingPackagePrivateTypes() {
		for (String source : new String[] {
				STARTER.replace("class Result", "class Other"),
				STARTER.replace("class Solution", "class Other"),
				"public class Solution { public static void main(String[] args) {} }",
				STARTER.replace("class Result", "interface Result") }) {
			assertThrows(IllegalArgumentException.class, () -> JavaContracts.verify(STARTER, source), source);
		}
	}

	@Test
	void rejectsEachChangedSignatureComponentAndVisibility() {
		for (String source : new String[] {
				STARTER.replace("public static int solve(int value) { return 0; }", ""),
				STARTER.replace("int solve", "long solve"),
				STARTER.replace("solve(int value)", "solve(long value)"),
				STARTER.replace("int solve", "int renamed"),
				STARTER.replace("public static int", "public int"),
				STARTER.replace("public static int", "protected static int"),
				STARTER.replace("protected String", "private String"),
				STARTER.replace("public static void main", "public void main"),
				STARTER.replace("String[] args", "String args"),
				STARTER.replace("public static void main(String[] args) throws Exception {}", "") }) {
			assertThrows(IllegalArgumentException.class, () -> JavaContracts.verify(STARTER, source), source);
		}
	}

	@Test
	void checksEveryOverload() {
		String starter = "class Result { public int f(int n) { return 0; } public int f(String n) { return 0; } }";
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						"class Result { public int f(int n) { return 1; } }"
				)
		);
		assertDoesNotThrow(() -> JavaContracts.verify(
				starter,
				"class Result { public int f(String s) { return 2; } public int f(int x) { return 1; } public int f"
				+ "(long x) { return 3; } }"
		));
	}

	@Test
	void preservesMethodTypeParametersAndGenericParameterTypes() {
		String starter = "class Result { public <T extends Number> T f(java.util.List<T> values) { return null; } }";
		assertDoesNotThrow(() -> JavaContracts.verify(starter, starter.replace("values", "renamed")));
		for (String source : new String[] {
				starter.replace("extends Number", "extends Object"),
				starter.replace("java.util.List<T>", "java.util.List<String>"),
				starter.replace("<T extends Number>", "<T extends Number, U>"),
				starter.replace("> T f", "> Number f") }) {
			assertThrows(IllegalArgumentException.class, () -> JavaContracts.verify(starter, source), source);
		}
	}

	@Test
	void preservesExplicitAndImplicitExposedConstructors() {
		String starter = "public class Solution { public Solution(int n) {} protected Solution(String n) {} }";
		assertDoesNotThrow(() -> JavaContracts.verify(starter, starter.replace("int n", "int renamed")));
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						"public class Solution { public Solution(int n) {} }"
				)
		);
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						starter.replace("public Solution", "private Solution")
				)
		);
		assertDoesNotThrow(() -> JavaContracts.verify(
				"public class Solution {}",
				"public class Solution { public Solution() {} }"
		));
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						"public class Solution {}",
						"public class Solution { public Solution(int n) {} }"
				)
		);
	}

	@Test
	void associatesNestedSignaturesWithTheirOwningType() {
		String starter = "public class Solution { static class Worker { public int run(int n) { return 0; } } }";
		assertDoesNotThrow(() -> JavaContracts.verify(starter, starter.replace("return 0", "return n")));
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						starter.replace("class Worker", "class Helper")
				)
		);
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						"public class Solution { public int run(int n) { return n; } static class Worker {} }"
				)
		);
	}

	@Test
	void preservesImplicitPublicInterfaceMethods() {
		String starter = "interface Result { int solve(int n); private void helper() {} }";
		assertDoesNotThrow(() -> JavaContracts.verify(
				starter,
				"interface Result { public int solve(int renamed); }"
		));
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						"interface Result { private int solve(int n) { return n; } }"
				)
		);
	}

	@Test
	void permitsPrivateAndPackagePrivateMethodChanges() {
		assertDoesNotThrow(() -> JavaContracts.verify(
				"class Result { private int helper(int n) { return n; } void internal() {} }", "class Result {}"));
	}

	@Test
	void preservesPackagePrivateMainEntryPoints() {
		String starter = "class Solution { void main() {} }";
		assertDoesNotThrow(() -> JavaContracts.verify(starter, "class Solution { public void main() {} }"));
		assertThrows(IllegalArgumentException.class, () -> JavaContracts.verify(starter, "class Solution {}"));
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						"class Solution { private void main() {} }"
				)
		);
	}

	@Test
	void preservesImplicitPublicNestedInterfaceTypeConstructors() {
		String starter = "interface Result { class Worker {} }";
		assertDoesNotThrow(() -> JavaContracts.verify(
				starter,
				"interface Result { class Worker { public Worker() {} } }"
		));
		assertThrows(
				IllegalArgumentException.class, () -> JavaContracts.verify(
						starter,
						"interface Result { class Worker { private Worker() {} } }"
				)
		);
	}

	@Test
	void acceptsEquivalentVarargsAndArrayParameterTypes() {
		assertDoesNotThrow(() -> JavaContracts.verify(
				"class Result { public void f(String... values) {} }",
				"class Result { public void f(String[] renamed) {} }"
		));
	}

	@Test
	void rejectsChangedPackageTypeParametersAndNarrowedTypeVisibility() {
		String starter = "package example; public class Result<T extends Number> {}";
		for (String source : new String[] {
				starter.replace("package example;", "package other;"),
				starter.replace("extends Number", "extends Object"),
				starter.replace("public class", "class") }) {
			assertThrows(IllegalArgumentException.class, () -> JavaContracts.verify(starter, source), source);
		}
	}

	@Test
	void rejectsMalformedEmptyAndDuplicateInputOnEitherSide() {
		for (String invalid : new String[] { null, "", " ", "// comment", "class Solution {",
											 "class Solution { public void f( {} }",
											 "class Solution {} class Solution {}",
											 "class Solution { public void f() {} public void f() {} }" }) {
			assertThrows(IllegalArgumentException.class, () -> JavaContracts.verify(invalid, STARTER));
			assertThrows(IllegalArgumentException.class, () -> JavaContracts.verify(STARTER, invalid));
		}
	}

	@Test
	void parsesOnlyWithoutResolvingDependenciesExecutingCodeOrWritingClassFiles() throws Exception {
		Set<Path> before = classFiles();
		Path marker = directory.resolve("executed");
		String source = """
						public class ContractOutputProbe {
						    static {
						        try { java.nio.file.Files.writeString(java.nio.file.Path.of("%s"), "executed"); }
						        catch (java.io.IOException exception) { throw new RuntimeException(exception); }
						    }
						    public static MissingDependency solve(MissingDependency value) { return value; }
						    public static void main(String[] args) { throw new AssertionError("must not execute"); }
						}
						""".formatted(marker.toString().replace("\\", "\\\\").replace("\"", "\\\""));
		assertDoesNotThrow(() -> JavaContracts.verify(source, source));
		assertFalse(Files.exists(marker));
		try (var entries = Files.list(directory)) {
			assertEquals(0, entries.count());
		}
		assertEquals(before, classFiles());
	}

	private static Set<Path> classFiles() throws Exception {
		try (var entries = Files.list(Path.of("."))) {
			return entries.filter(path -> path.toString().endsWith(".class")).collect(Collectors.toSet());
		}
	}
}
