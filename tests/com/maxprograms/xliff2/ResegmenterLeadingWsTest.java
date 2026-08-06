/*******************************************************************************
 * Copyright (c) 2018 - 2026 Maxprograms / Levsha.
 *
 * Resegmenter should peel SRX join whitespace into &lt;ignorable&gt; so segment N+1
 * does not start with a leading space.
 *******************************************************************************/
package com.maxprograms.xliff2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.maxprograms.converters.Constants;
import com.maxprograms.xml.Catalog;
import com.maxprograms.xml.CatalogBuilder;

/**
 * Runnable with {@code ant test-resegment}. Exits 0 on success, 1 on failure.
 */
public final class ResegmenterLeadingWsTest {

	private static int failures;

	private ResegmenterLeadingWsTest() {
	}

	public static void main(String[] args) throws Exception {
		Path catalogPath = Path.of(args.length > 0 ? args[0] : "catalog/catalog.xml").toAbsolutePath();
		Path srxPath = Path.of(args.length > 1 ? args[1] : "srx/default.srx").toAbsolutePath();
		Path newlinesSrx = Path.of(args.length > 2 ? args[2] : "srx/default.srx").toAbsolutePath();
		if (!Files.isRegularFile(catalogPath)) {
			fail("catalog not found: " + catalogPath);
			System.exit(1);
		}
		if (!Files.isRegularFile(srxPath)) {
			fail("srx not found: " + srxPath);
			System.exit(1);
		}

		testPeelsLeadingSpaceIntoIgnorable(catalogPath, srxPath);
		testExportJoinRestoresSpace(catalogPath, srxPath);
		testKeepsTagOnlySegment(catalogPath, srxPath);
		if (Files.isRegularFile(newlinesSrx)) {
			testKeepsTagOnlyFirstLineOnNewlines(catalogPath, newlinesSrx);
		}

		if (failures > 0) {
			System.err.println(failures + " failure(s)");
			System.exit(1);
		}
		System.out.println("ResegmenterLeadingWsTest: all passed");
	}

	private static void testPeelsLeadingSpaceIntoIgnorable(Path catalogPath, Path srxPath) throws Exception {
		String name = "peels leading join space into ignorable";
		Path dir = Files.createTempDirectory("oxlf-reseg-ws-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.txt" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="default">Hello. World.</source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), srxPath.toString(), "en", catalog);
			assertEquals(name + " status", Constants.SUCCESS, res.get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, "<ignorable");
			assertContains(name, xml, "xml:space=\"preserve\"> </source>");
			assertContains(name, xml, ">Hello.</source>");
			assertContains(name, xml, ">World.</source>");
			assertNotContains(name, xml, "> World.</source>");
		} finally {
			deleteRecursive(dir);
		}
	}

	private static void testExportJoinRestoresSpace(Path catalogPath, Path srxPath) throws Exception {
		String name = "FromXliff2 join restores ignorable space";
		Path dir = Files.createTempDirectory("oxlf-reseg-join-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.txt" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="default">Hello. World.</source>
					    <target xml:space="default">Hello. World.</target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), srxPath.toString(), "en", catalog);
			assertEquals(name + " reseg status", Constants.SUCCESS, res.get(0));

			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " from2 status", Constants.SUCCESS, from.get(0));

			String xml12 = Files.readString(out12);
			// Joined source/target must contain the space between sentences.
			Pattern src = Pattern.compile("<source[^>]*>([\\s\\S]*?)</source>");
			Matcher m = src.matcher(xml12);
			boolean found = false;
			while (m.find()) {
				String body = m.group(1);
				if (body.contains("Hello.") && body.contains("World.")) {
					found = true;
					if (!body.contains("Hello. World.")) {
						fail(name + ": joined source missing space: " + body);
					}
				}
			}
			if (!found) {
				fail(name + ": no joined source with Hello/World");
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	private static void testKeepsTagOnlySegment(Path catalogPath, Path srxPath) throws Exception {
		String name = "keeps tag-only unit as segment";
		Path dir = Files.createTempDirectory("oxlf-reseg-tagonly-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.txt" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve"><ph id="1"/></source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), srxPath.toString(), "en", catalog);
			assertEquals(name + " status", Constants.SUCCESS, res.get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, "<segment");
			assertContains(name, xml, "<ph id=\"1\"/");
			// Must not demote the whole unit to ignorable-only.
			assertContains(name, xml, "<segment id=\"s1\"");
		} finally {
			deleteRecursive(dir);
		}
	}

	private static void testKeepsTagOnlyFirstLineOnNewlines(Path catalogPath, Path newlinesSrx) throws Exception {
		String name = "keeps tag-only first line as segment (newlines SRX)";
		Path dir = Files.createTempDirectory("oxlf-reseg-tagline-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve"><ph id="1"/>\nHello</source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), newlinesSrx.toString(), "en", catalog);
			assertEquals(name + " status", Constants.SUCCESS, res.get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, "<ph id=\"1\"/");
			assertContains(name, xml, ">Hello</source>");
			// Tag-only first line must remain a segment (not only ignorable).
			int segCount = 0;
			int idx = 0;
			while ((idx = xml.indexOf("<segment", idx)) >= 0) {
				segCount++;
				idx += 8;
			}
			if (segCount < 2) {
				fail(name + ": expected >=2 segments, got " + segCount + " in " + xml);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	private static void assertEquals(String name, String expected, String actual) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			fail(name + ": expected <" + expected + "> but was <" + actual + ">");
		}
	}

	private static void assertContains(String name, String haystack, String needle) {
		if (haystack == null || !haystack.contains(needle)) {
			fail(name + ": missing <" + needle + ">");
		}
	}

	private static void assertNotContains(String name, String haystack, String needle) {
		if (haystack != null && haystack.contains(needle)) {
			fail(name + ": unexpectedly contains <" + needle + ">");
		}
	}

	private static void fail(String msg) {
		failures++;
		System.err.println("FAIL: " + msg);
	}

	private static void deleteRecursive(Path dir) throws Exception {
		if (dir == null || !Files.exists(dir)) {
			return;
		}
		try (var walk = Files.walk(dir)) {
			walk.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception ignored) {
					// best-effort cleanup
				}
			});
		}
	}
}
