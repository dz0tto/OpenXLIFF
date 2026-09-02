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
		testDoesNotPeelLeadingTags(catalogPath, srxPath);
		if (Files.isRegularFile(newlinesSrx)) {
			testKeepsTagOnlyFirstLineOnNewlines(catalogPath, newlinesSrx);
			testNewlinesThenSentenceSrx(catalogPath, srxPath, newlinesSrx);
			testKeepsTagOnlyAfterConvertStylePh(catalogPath, newlinesSrx, srxPath);
			testPeelsTrailingNewlineIntoIgnorable(catalogPath, newlinesSrx);
			testPeelsMultipleNewlinesIntoIgnorable(catalogPath, newlinesSrx);
			testExportJoinRestoresNewlineBetweenAdjacentSegments(catalogPath, newlinesSrx);
			testExportJoinRestoresDoubleNewlineFromIgnorables(catalogPath, newlinesSrx);
		}
		testExportJoinRestoresNewlineFromSourceTrailingBreak(catalogPath);
		testExportJoinRestoresLeftoverBreakPlusIgnorable(catalogPath);
		testExportJoinDoesNotInventNewlineBetweenAdjacentSegments(catalogPath);
		testKeepsScEcPairOnSameSegment(catalogPath, srxPath);

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

	private static void testDoesNotPeelLeadingTags(Path catalogPath, Path srxPath) throws Exception {
		String name = "does not peel leading tags into ignorable";
		Path dir = Files.createTempDirectory("oxlf-reseg-nopeel-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.txt" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve"><ph id="1"/>Hello. World.</source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), srxPath.toString(), "en", catalog);
			assertEquals(name + " status", Constants.SUCCESS, res.get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, "<ph id=\"1\"/");
			assertContains(name, xml, "Hello.");
			// Leading ph must remain inside a segment source, not a peeled ignorable.
			Pattern peeled = Pattern.compile(
					"<ignorable[^>]*>\\s*<source[^>]*><ph id=\"1\"/></source>\\s*</ignorable>");
			if (peeled.matcher(xml).find()) {
				fail(name + ": leading ph was peeled into ignorable: " + xml);
			}
			int segPos = xml.indexOf("<segment");
			int phPos = xml.indexOf("<ph id=\"1\"");
			if (phPos < 0 || segPos < 0 || phPos < segPos) {
				fail(name + ": leading ph not inside a segment: " + xml);
			}
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

	/**
	 * Convert-style {@code <ph dataRef>} tag-only lines must remain segments through
	 * newlines then sentence SRX (never demoted to ignorable / dropped).
	 */
	private static void testKeepsTagOnlyAfterConvertStylePh(Path catalogPath, Path newlinesSrx, Path sentenceSrx)
			throws Exception {
		String name = "keeps Convert-style tag-only ph through newlines+sentence SRX";
		Path dir = Files.createTempDirectory("oxlf-reseg-tagph-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <originalData>
					    <data id="1">&lt;x id="x1"/&gt;</data>
					   </originalData>
					   <segment id="s1">
					    <source xml:space="preserve"><ph id="1" dataRef="1" equiv="&lt;b/&gt;"/>\nHello. More.</source>
					   </segment>
					  </unit>
					  <unit id="u2" canResegment="yes" translate="yes">
					   <originalData>
					    <data id="1">&lt;x id="x2"/&gt;</data>
					   </originalData>
					   <segment id="s1">
					    <source xml:space="preserve"><ph id="1" dataRef="1" equiv="&lt;br/&gt;"/></source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			assertEquals(name + " newlines", Constants.SUCCESS,
					Resegmenter.run(xliff.toString(), newlinesSrx.toString(), "en", catalog).get(0));
			assertEquals(name + " sentences", Constants.SUCCESS,
					Resegmenter.run(xliff.toString(), sentenceSrx.toString(), "en", catalog).get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, "dataRef=\"1\"");
			assertContains(name, xml, "Hello.");
			// Tag-only first line and standalone tag-only unit must remain <segment>, not only ignorable.
			if (!xml.contains("<ph") || countTag(xml, "<segment") < 3) {
				fail(name + ": expected tag-only segments kept, got " + xml);
			}
			// Must not demote the standalone tag-only unit to ignorable-only.
			assertContains(name, xml, "equiv=\"&lt;br/&gt;\"");
			int u2 = xml.indexOf("id=\"u2\"");
			if (u2 < 0) {
				fail(name + ": missing unit u2");
			}
			String u2xml = xml.substring(u2);
			if (!u2xml.contains("<segment")) {
				fail(name + ": tag-only unit u2 lost its segment: " + u2xml);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * FULL mode: newlines-only.srx first, then default.srx must still split sentences
	 * inside each line (units already have multiple segments after pass 1).
	 */
	private static void testNewlinesThenSentenceSrx(Path catalogPath, Path sentenceSrx, Path newlinesSrx)
			throws Exception {
		String name = "newlines then sentence SRX splits multi-segment units";
		Path dir = Files.createTempDirectory("oxlf-reseg-twopass-");
		try {
			Path xliff = dir.resolve("in.xlf");
			// Keep the XML prolog at column 0; put the cell newline in via \n escape.
			String body = """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve">First line only.\nSecond line. And another sentence.</source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""";
			Files.writeString(xliff, body, StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> pass1 = Resegmenter.run(xliff.toString(), newlinesSrx.toString(), "en", catalog);
			assertEquals(name + " newlines status", Constants.SUCCESS, pass1.get(0));
			String afterNewlines = Files.readString(xliff);
			int afterNl = countTag(afterNewlines, "<segment");
			if (afterNl < 2) {
				fail(name + ": expected >=2 segments after newlines, got " + afterNl + " in " + afterNewlines);
			}

			List<String> pass2 = Resegmenter.run(xliff.toString(), sentenceSrx.toString(), "en", catalog);
			assertEquals(name + " sentence status", Constants.SUCCESS, pass2.get(0));
			String afterSentences = Files.readString(xliff);
			int afterSr = countTag(afterSentences, "<segment");
			if (afterSr < 3) {
				fail(name + ": expected >=3 segments after sentence SRX, got " + afterSr + " in " + afterSentences);
			}
			assertContains(name, afterSentences, "First line only.");
			assertContains(name, afterSentences, "Second line.");
			assertContains(name, afterSentences, "And another sentence.");
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * Newline SRX leaves the break on segment N; peel it into a following
	 * {@code <ignorable>} so the editor source has no trailing newline.
	 */
	private static void testPeelsTrailingNewlineIntoIgnorable(Path catalogPath, Path newlinesSrx) throws Exception {
		String name = "peels trailing newline into following ignorable";
		Path dir = Files.createTempDirectory("oxlf-reseg-trail-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve">First line\nSecond line</source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), newlinesSrx.toString(), "en", catalog);
			assertEquals(name + " status", Constants.SUCCESS, res.get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, ">First line</source>");
			assertContains(name, xml, ">Second line</source>");
			assertContains(name, xml, "<ignorable");
			assertNotContains(name, xml, ">First line\n</source>");
			if (countTag(xml, "<segment") < 2) {
				fail(name + ": expected 2 segments in " + xml);
			}
			if (countIgnorableNewlines(xml) < 1) {
				fail(name + ": expected a newline ignorable in " + xml);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * Several newlines between lines must all leave the segment sources and live
	 * in ignorables (single or multiple) so export can restore the same run.
	 */
	private static void testPeelsMultipleNewlinesIntoIgnorable(Path catalogPath, Path newlinesSrx) throws Exception {
		String name = "peels multiple newlines out of segment sources";
		Path dir = Files.createTempDirectory("oxlf-reseg-multinl-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve">First line\n\nSecond line</source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), newlinesSrx.toString(), "en", catalog);
			assertEquals(name + " status", Constants.SUCCESS, res.get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, ">First line</source>");
			assertContains(name, xml, ">Second line</source>");
			assertNotContains(name, xml, ">First line\n</source>");
			assertNotContains(name, xml, ">First line\n\n</source>");
			if (countTag(xml, "<segment") != 2) {
				fail(name + ": expected 2 segments, got " + countTag(xml, "<segment") + " in " + xml);
			}
			if (countIgnorableNewlines(xml) < 2) {
				fail(name + ": expected at least 2 newline chars in ignorables, got "
						+ countIgnorableNewlines(xml) + " in " + xml);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * After newline segmentation, translated line segments must rejoin with {@code \\n}.
	 */
	private static void testExportJoinRestoresNewlineBetweenAdjacentSegments(Path catalogPath, Path newlinesSrx)
			throws Exception {
		String name = "FromXliff2 join restores newline between line segments";
		Path dir = Files.createTempDirectory("oxlf-reseg-nl-join-");
		try {
			Path xliff = dir.resolve("in.xlf");
			String body = """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="ru">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve">First line\nSecond line</source>
					    <target xml:space="preserve">First line\nSecond line</target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""";
			Files.writeString(xliff, body, StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), newlinesSrx.toString(), "en", catalog);
			assertEquals(name + " reseg status", Constants.SUCCESS, res.get(0));

			String split = Files.readString(xliff);
			split = split.replace(">First line</target>", ">Первая строка</target>");
			split = split.replace(">First line\n</target>", ">Первая строка</target>");
			split = split.replace(">Second line</target>", ">Вторая строка</target>");
			Files.writeString(xliff, split, StandardCharsets.UTF_8);

			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " from2 status", Constants.SUCCESS, from.get(0));

			String xml12 = Files.readString(out12);
			Pattern tgt = Pattern.compile("<target[^>]*>([\\s\\S]*?)</target>");
			Matcher m = tgt.matcher(xml12);
			boolean found = false;
			while (m.find()) {
				String bodyTgt = m.group(1);
				if (bodyTgt.contains("Первая") && bodyTgt.contains("Вторая")) {
					found = true;
					if (!bodyTgt.contains("Первая строка\nВторая строка")) {
						fail(name + ": joined target missing newline: " + bodyTgt);
					}
				}
			}
			if (!found) {
				fail(name + ": no joined target with both lines: " + xml12);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * Two newlines between lines must reappear on convert-back from ignorables,
	 * even when the translator targets have no trailing break.
	 */
	private static void testExportJoinRestoresDoubleNewlineFromIgnorables(Path catalogPath, Path newlinesSrx)
			throws Exception {
		String name = "FromXliff2 join restores double newline from ignorables";
		Path dir = Files.createTempDirectory("oxlf-reseg-dblnl-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="ru">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="preserve">First line\n\nSecond line</source>
					    <target xml:space="preserve">First line\n\nSecond line</target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), newlinesSrx.toString(), "en", catalog);
			assertEquals(name + " reseg status", Constants.SUCCESS, res.get(0));

			String split = Files.readString(xliff);
			split = split.replace(">First line</target>", ">Первая строка</target>");
			split = split.replace(">First line\n</target>", ">Первая строка</target>");
			split = split.replace(">Second line</target>", ">Вторая строка</target>");
			Files.writeString(xliff, split, StandardCharsets.UTF_8);

			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " from2 status", Constants.SUCCESS, from.get(0));

			String xml12 = Files.readString(out12);
			if (!xml12.contains("Первая строка\n\nВторая строка")) {
				fail(name + ": joined target missing double newline: " + xml12);
			}
			if (!xml12.contains("First line\n\nSecond line")) {
				fail(name + ": joined source missing original double newline: " + xml12);
			}
			if (xml12.contains("First line\n\n\nSecond line")) {
				fail(name + ": joined source invented an extra newline: " + xml12);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * When the previous source still ends with the newline SRX break, restore that
	 * same break on the target only — do not double it on the source.
	 */
	private static void testExportJoinRestoresNewlineFromSourceTrailingBreak(Path catalogPath) throws Exception {
		String name = "FromXliff2 restores target newline from source trailing break";
		Path dir = Files.createTempDirectory("oxlf-reseg-nl-src-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="ru">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="u1-0">
					    <source xml:space="preserve">First line\n</source>
					    <target xml:space="preserve">Первая строка</target>
					   </segment>
					   <segment id="u1-1">
					    <source xml:space="preserve">Second line</source>
					    <target xml:space="preserve">Вторая строка</target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " from2 status", Constants.SUCCESS, from.get(0));

			String xml12 = Files.readString(out12);
			if (!xml12.contains("Первая строка\nВторая строка")) {
				fail(name + ": joined target missing restored newline: " + xml12);
			}
			if (!xml12.contains("First line\nSecond line")) {
				fail(name + ": joined source missing original newline: " + xml12);
			}
			if (xml12.contains("First line\n\nSecond line")) {
				fail(name + ": joined source doubled the newline: " + xml12);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * Leftover trailing break on segment N plus a following newline ignorable
	 * (Excel {@code _x000D_} restored after SRX) must both reach the target.
	 */
	private static void testExportJoinRestoresLeftoverBreakPlusIgnorable(Path catalogPath) throws Exception {
		String name = "FromXliff2 restores leftover source break plus ignorable";
		Path dir = Files.createTempDirectory("oxlf-reseg-nl-leftover-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="ru">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="u1-0">
					    <source xml:space="preserve">First line\n</source>
					    <target xml:space="preserve">Первая строка</target>
					   </segment>
					   <ignorable id="u1-i0">
					    <source xml:space="preserve">\n</source>
					   </ignorable>
					   <segment id="u1-1">
					    <source xml:space="preserve">Second line</source>
					    <target xml:space="preserve">Вторая строка</target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " from2 status", Constants.SUCCESS, from.get(0));

			String xml12 = Files.readString(out12);
			if (!xml12.contains("Первая строка\n\nВторая строка")) {
				fail(name + ": joined target missing both newlines: " + xml12);
			}
			if (!xml12.contains("First line\n\nSecond line")) {
				fail(name + ": joined source missing both newlines: " + xml12);
			}
			if (xml12.contains("First line\n\n\nSecond line")) {
				fail(name + ": joined source invented an extra newline: " + xml12);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/**
	 * Adjacent segments with no linebreak in the source must stay glued.
	 */
	private static void testExportJoinDoesNotInventNewlineBetweenAdjacentSegments(Path catalogPath) throws Exception {
		String name = "FromXliff2 does not invent newline between adjacent segments";
		Path dir = Files.createTempDirectory("oxlf-reseg-nl-none-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="ru">
					 <file id="f1" original="t.xlsx" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="u1-0">
					    <source xml:space="preserve">First line</source>
					    <target xml:space="preserve">Первая строка</target>
					   </segment>
					   <segment id="u1-1">
					    <source xml:space="preserve">Second line</source>
					    <target xml:space="preserve">Вторая строка</target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " from2 status", Constants.SUCCESS, from.get(0));

			String xml12 = Files.readString(out12);
			if (xml12.contains("Первая строка\nВторая строка")) {
				fail(name + ": invented newline on target: " + xml12);
			}
			if (xml12.contains("First line\nSecond line")) {
				fail(name + ": invented newline on source: " + xml12);
			}
			if (!xml12.contains("Первая строкаВторая строка")) {
				fail(name + ": expected glued target: " + xml12);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	/** SRX would split after "World." between sc and matching ec — keep the pair together. */
	private static void testKeepsScEcPairOnSameSegment(Path catalogPath, Path srxPath) throws Exception {
		String name = "does not split sc from matching ec";
		Path dir = Files.createTempDirectory("oxlf-reseg-scec-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.txt" canResegment="yes">
					  <unit id="u1" canResegment="yes" translate="yes">
					   <segment id="s1">
					    <source xml:space="default">Hello <sc id="1"/>World. More <ec id="1e" startRef="1"/> text.</source>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);

			Catalog catalog = CatalogBuilder.getCatalog(catalogPath.toString());
			List<String> res = Resegmenter.run(xliff.toString(), srxPath.toString(), "en", catalog);
			assertEquals(name + " status", Constants.SUCCESS, res.get(0));

			String xml = Files.readString(xliff);
			assertContains(name, xml, "<sc id=\"1\"");
			assertContains(name, xml, "startRef=\"1\"");
			int scPos = xml.indexOf("<sc id=\"1\"");
			int ecPos = xml.indexOf("<ec ");
			int scSeg = xml.lastIndexOf("<segment", scPos);
			int ecSeg = xml.lastIndexOf("<segment", ecPos);
			if (scPos < 0 || ecPos < 0 || scSeg != ecSeg) {
				fail(name + ": sc/ec split across segments: " + xml);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	private static int countIgnorableNewlines(String xml) {
		int total = 0;
		int idx = 0;
		while ((idx = xml.indexOf("<ignorable", idx)) >= 0) {
			int end = xml.indexOf("</ignorable>", idx);
			if (end < 0) {
				break;
			}
			String body = xml.substring(idx, end);
			int src = body.indexOf("<source");
			int srcClose = body.indexOf("</source>");
			if (src >= 0 && srcClose > src) {
				int gt = body.indexOf('>', src);
				if (gt >= 0 && gt < srcClose) {
					String text = body.substring(gt + 1, srcClose);
					for (int i = 0; i < text.length(); i++) {
						char c = text.charAt(i);
						if (c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029') {
							total++;
						}
					}
				}
			}
			idx = end + 12;
		}
		return total;
	}

	private static int countTag(String xml, String tag) {
		int count = 0;
		int idx = 0;
		while ((idx = xml.indexOf(tag, idx)) >= 0) {
			count++;
			idx += tag.length();
		}
		return count;
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
