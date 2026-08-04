/*******************************************************************************
 * Copyright (c) 2018 - 2026 Maxprograms / Levsha.
 *
 * Regression suite for MQXLIFF / Levsha Convert ↔ Merge problems fixed in
 * OpenXLIFF (preserve tag ids, opaque mq:rxt payloads, tag-only + locked units,
 * Merge restoring MemoQ &lt;ph&gt; instead of unbound mq:rxt elements).
 *******************************************************************************/
package com.maxprograms.converters.xliff;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.maxprograms.converters.Constants;
import com.maxprograms.converters.Merge;
import com.maxprograms.xliff2.ToXliff2;

/**
 * Runnable with {@code ant test}. Exits 0 on success, 1 on failure.
 */
public final class MqxliffConvertRoundTripTest {

	private static int failures;

	private MqxliffConvertRoundTripTest() {
	}

	public static void main(String[] args) throws Exception {
		Path catalog = Path.of(args.length > 0 ? args[0] : "catalog/catalog.xml").toAbsolutePath();
		if (!Files.isRegularFile(catalog)) {
			fail("catalog not found: " + catalog);
			System.exit(1);
		}

		testPreservesMismatchedTagIdsAndNestedEntities(catalog);
		testKeepsLockedAndTagOnlyUnits(catalog);
		testMergeRestoresPhPayloadsWithoutUnboundMq(catalog);
		testLevshaEmptyPlaceholderStillWorks(catalog);
		testApprovedFinalSurvivesEmptyTargetHarvest(catalog);

		if (failures > 0) {
			System.err.println(failures + " failure(s)");
			System.exit(1);
		}
		System.out.println("MqxliffConvertRoundTripTest: all passed");
	}

	/** #350 / OpenXLIFF preserve-inline-ids + opaque payload (#5). */
	private static void testPreservesMismatchedTagIdsAndNestedEntities(Path catalog) throws Exception {
		String name = "preserves mismatched tag ids + nested entities";
		Path dir = Files.createTempDirectory("oxlf-mq-ids-");
		try {
			Path src = dir.resolve("in.mqxliff");
			write(src, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2" xmlns:mq="MQXliff">
					 <file source-language="en" target-language="de" datatype="xml" original="t">
					  <body>
					   <trans-unit id="tu1" mq:segmentguid="g1">
					    <source>Hello <ph id="1">&lt;mq:rxt displaytext="A" val="&amp;lt;x&amp;quot;y" /&gt;</ph> and <ph id="2">&lt;mq:rxt displaytext="B" val="b" /&gt;</ph></source>
					    <target>Hallo <ph id="1">&lt;mq:rxt displaytext="A" val="&amp;lt;x&amp;quot;y" /&gt;</ph> und <ph id="4">&lt;mq:rxt displaytext="D" val="d" /&gt;</ph></target>
					   </trans-unit>
					  </body>
					 </file>
					</xliff>
					""");
			Path x21 = convertToXliff21(src, dir, catalog, true);
			String xml = Files.readString(x21);
			assertContains(name, xml, "id=\"1\"");
			assertContains(name, xml, "id=\"2\"");
			assertContains(name, xml, "id=\"4\"");
			// Must not renumber target-only tag 4 away / collapse to sequential 1,2,3 only
			assertContains(name, xml, "dataRef=\"4\"");
			assertContains(name, xml, "val=\"&amp;lt;x&amp;quot;y\"");
			assertContains(name, xml, "equiv=\"A\"");
			pass(name);
		} finally {
			deleteRecursive(dir);
		}
	}

	/** #326 / #325 / includeNonTranslatable + tag-only keep. */
	private static void testKeepsLockedAndTagOnlyUnits(Path catalog) throws Exception {
		String name = "keeps locked + tag-only units";
		Path dir = Files.createTempDirectory("oxlf-mq-lock-");
		try {
			Path src = dir.resolve("in.mqxliff");
			write(src, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2" xmlns:mq="MQXliff">
					 <file source-language="en" target-language="de" datatype="xml" original="t">
					  <body>
					   <trans-unit id="plain">
					    <source>Text</source>
					    <target>Text</target>
					   </trans-unit>
					   <trans-unit id="locked" translate="no" ts="locked">
					    <source><ph id="9">&lt;mq:rxt displaytext="L" val="l" /&gt;</ph></source>
					    <target><ph id="9">&lt;mq:rxt displaytext="L" val="l" /&gt;</ph></target>
					   </trans-unit>
					  </body>
					 </file>
					</xliff>
					""");
			Path withFlag = convertToXliff21(src, dir.resolve("with"), catalog, true);
			String with = Files.readString(withFlag);
			assertEquals(name + " unit count with flag", 2, countUnits(with));
			assertContains(name, with, "openxliff:locked");
			assertContains(name, with, "dataRef=\"9\"");

			Path withoutFlag = convertToXliff21(src, dir.resolve("without"), catalog, false);
			String without = Files.readString(withoutFlag);
			assertEquals(name + " unit count without flag", 1, countUnits(without));
			pass(name);
		} finally {
			deleteRecursive(dir);
		}
	}

	/** Export unbound-mq regression + restore MemoQ ph shape. */
	private static void testMergeRestoresPhPayloadsWithoutUnboundMq(Path catalog) throws Exception {
		String name = "Merge restores mq:rxt inside ph";
		Path dir = Files.createTempDirectory("oxlf-mq-merge-");
		try {
			Path src = dir.resolve("in.mqxliff");
			write(src, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2" xmlns:mq="MQXliff">
					 <file source-language="en" target-language="de" datatype="xml" original="t">
					  <body>
					   <trans-unit id="tu1" approved="yes">
					    <source>Hello <ph id="1">&lt;mq:rxt displaytext="A" val="&amp;lt;x&amp;quot;y" /&gt;</ph></source>
					    <target state="translated">Hallo <ph id="1">&lt;mq:rxt displaytext="A" val="&amp;lt;x&amp;quot;y" /&gt;</ph></target>
					   </trans-unit>
					  </body>
					 </file>
					</xliff>
					""");
			Path x21 = convertToXliff21(src, dir, catalog, true);
			Path back = dir.resolve("merged.mqxliff");
			List<String> merge = Merge.merge(x21.toString(), back.toString(), catalog.toString(), true);
			assertEquals(name + " merge status", Constants.SUCCESS, merge.get(0));
			String out = Files.readString(back);
			assertContains(name, out, "<ph");
			assertContains(name, out, "mq:rxt");
			assertContains(name, out, "val=\"&amp;lt;x&amp;quot;y\"");
			// Must not expand payload into a real namespaced child of target
			assertFalse(name + " bare mq:rxt element in target",
					out.matches("(?s).*<target[^>]*>\\s*<mq:rxt[\\s\\S]*</target>.*"));
			pass(name);
		} finally {
			deleteRecursive(dir);
		}
	}

	/** Excel/Levsha empty &lt;x equiv-text&gt; path still works. */
	private static void testLevshaEmptyPlaceholderStillWorks(Path catalog) throws Exception {
		String name = "Levsha empty x placeholder";
		Path dir = Files.createTempDirectory("oxlf-levsha-x-");
		try {
			Path src = dir.resolve("in.lxliff");
			write(src, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2">
					 <file source-language="en" target-language="ru" datatype="x-excel" original="t.xlsx">
					  <body>
					   <trans-unit id="1">
					    <source>Hi <x id="x1" equiv-text="{0}"/></source>
					    <target>Привет <x id="x1" equiv-text="{0}"/></target>
					   </trans-unit>
					  </body>
					 </file>
					</xliff>
					""");
			Path x21 = convertToXliff21(src, dir, catalog, true);
			String xml = Files.readString(x21);
			assertContains(name, xml, "equiv=\"{0}\"");
			assertContains(name, xml, "dataRef=\"1\"");
			pass(name);
		} finally {
			deleteRecursive(dir);
		}
	}

	/** #322 / ToXliff2 empty-target must not wipe approved→final. */
	private static void testApprovedFinalSurvivesEmptyTargetHarvest(Path catalog) throws Exception {
		String name = "approved final survives empty/tag-only target";
		Path dir = Files.createTempDirectory("oxlf-final-");
		try {
			Path src = dir.resolve("in.mqxliff");
			write(src, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff version="1.2" xmlns="urn:oasis:names:tc:xliff:document:1.2" xmlns:mq="MQXliff">
					 <file source-language="en" target-language="de" datatype="xml" original="t">
					  <body>
					   <trans-unit id="tu1" approved="yes">
					    <source><ph id="1">&lt;mq:rxt displaytext="A" val="a" /&gt;</ph></source>
					    <target state="translated"><ph id="1">&lt;mq:rxt displaytext="A" val="a" /&gt;</ph></target>
					   </trans-unit>
					  </body>
					 </file>
					</xliff>
					""");
			Path x21 = convertToXliff21(src, dir, catalog, true);
			String xml = Files.readString(x21);
			assertContains(name, xml, "state=\"final\"");
			pass(name);
		} finally {
			deleteRecursive(dir);
		}
	}

	private static Path convertToXliff21(Path source, Path dir, Path catalog, boolean includeNonTranslatable)
			throws Exception {
		Files.createDirectories(dir);
		Path xliff12 = dir.resolve("open.xlf");
		Path skeleton = dir.resolve("open.skl");
		Path xliff21 = dir.resolve("out.xlf");
		Files.copy(source, skeleton);
		Map<String, String> params = new HashMap<>();
		params.put("source", source.toAbsolutePath().toString());
		params.put("xliff", xliff12.toAbsolutePath().toString());
		params.put("skeleton", skeleton.toAbsolutePath().toString());
		params.put("catalog", catalog.toString());
		params.put("srcLang", "en");
		params.put("tgtLang", "de");
		params.put("idAttribute", "id");
		params.put("charlimAttribute", "maxlengthchars");
		if (includeNonTranslatable) {
			params.put("includeNonTranslatable", "yes");
		}
		List<String> res = ToOpenXliff.run(params);
		if (!Constants.SUCCESS.equals(res.get(0))) {
			throw new IOException("ToOpenXliff failed: " + res);
		}
		res = ToXliff2.run(xliff12.toString(), xliff21.toString(), catalog.toString(), "2.1");
		if (!Constants.SUCCESS.equals(res.get(0))) {
			throw new IOException("ToXliff2 failed: " + res);
		}
		return xliff21;
	}

	private static int countUnits(String xliff21) {
		Matcher m = Pattern.compile("<unit\\s").matcher(xliff21);
		int n = 0;
		while (m.find()) {
			n++;
		}
		return n;
	}

	private static void write(Path path, String content) throws IOException {
		Files.createDirectories(path.getParent());
		Files.writeString(path, content, StandardCharsets.UTF_8);
	}

	private static void deleteRecursive(Path root) throws IOException {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					// best-effort cleanup
				}
			});
		}
	}

	private static void assertContains(String test, String haystack, String needle) {
		if (haystack == null || !haystack.contains(needle)) {
			fail(test + ": expected to contain " + needle);
		}
	}

	private static void assertFalse(String test, boolean condition) {
		if (condition) {
			fail(test);
		}
	}

	private static void assertEquals(String test, Object expected, Object actual) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			fail(test + ": expected " + expected + " but was " + actual);
		}
	}

	private static void fail(String message) {
		failures++;
		System.err.println("FAIL: " + message);
	}

	private static void pass(String name) {
		System.out.println("OK: " + name);
	}
}
