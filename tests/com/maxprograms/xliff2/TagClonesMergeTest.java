/*******************************************************************************
 * Copyright (c) 2018 - 2026 Maxprograms / Levsha.
 *
 * A duplicated inline tag ({@code <id>.d<n>}, declared in the unit's
 * {@code tagClones} metadata) must merge back to XLIFF 1.2 with the same
 * payload and stored attributes as the tag it copies.
 *******************************************************************************/
package com.maxprograms.xliff2;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.maxprograms.converters.Constants;

/** Runnable with {@code ant test}. Exits 0 on success, 1 on failure. */
public final class TagClonesMergeTest {

	private static int failures;

	private TagClonesMergeTest() {
	}

	public static void main(String[] args) throws Exception {
		Path catalogPath = Path.of(args.length > 0 ? args[0] : "catalog/catalog.xml").toAbsolutePath();
		testApplyTagClonesCopiesPayloadAndAttributes();
		testApplyTagClonesFollowsChainsAndKeepsOwnData();
		testClonedPhMergesWithOriginalPayload(catalogPath);
		testClonedPhWithoutMetadataStillUsesDataRef(catalogPath);
		if (failures > 0) {
			System.err.println(failures + " failure(s)");
			System.exit(1);
		}
		System.out.println("TagClonesMergeTest: all passed");
	}

	private static void testApplyTagClonesCopiesPayloadAndAttributes() {
		Map<String, String> tags = new HashMap<>();
		tags.put("1", "{0}");
		Map<String, List<String[]>> attributes = new HashMap<>();
		List<String[]> attrs = new ArrayList<>();
		attrs.add(new String[] { "ctype", "x-placeholder" });
		attrs.add(new String[] { "equiv-text", "{0}" });
		attrs.add(new String[] { "oxlf-orig-id", "7" });
		attributes.put("1", attrs);
		Map<String, String> clones = new HashMap<>();
		clones.put("1.d2", "1");

		FromXliff2.applyTagClones(tags, attributes, clones);

		assertEquals("clone payload", "{0}", tags.get("1.d2"));
		List<String[]> cloneAttrs = attributes.get("1.d2");
		if (cloneAttrs == null) {
			fail("clone attributes missing");
			return;
		}
		boolean ctype = false;
		boolean origId = false;
		for (String[] pair : cloneAttrs) {
			if ("ctype".equals(pair[0]) && "x-placeholder".equals(pair[1])) {
				ctype = true;
			}
			if ("oxlf-orig-id".equals(pair[0])) {
				origId = true;
			}
		}
		if (!ctype) {
			fail("clone must inherit ctype");
		}
		if (origId) {
			fail("clone must not inherit the original's 1.2 id");
		}
	}

	private static void testApplyTagClonesFollowsChainsAndKeepsOwnData() {
		Map<String, String> tags = new HashMap<>();
		tags.put("a", "<br/>");
		tags.put("a.d3", "own");
		Map<String, String> clones = new HashMap<>();
		clones.put("a.d2", "a");
		clones.put("a.d3", "a.d2");
		clones.put("a.d4", "a.d3");
		FromXliff2.applyTagClones(tags, new HashMap<>(), clones);
		assertEquals("chain resolves to root", "<br/>", tags.get("a.d2"));
		assertEquals("existing payload kept", "own", tags.get("a.d3"));
		assertEquals("chain through clone", "<br/>", tags.get("a.d4"));
	}

	private static void testClonedPhMergesWithOriginalPayload(Path catalogPath) throws Exception {
		String name = "cloned ph merges with original payload";
		Path dir = Files.createTempDirectory("oxlf-tagclones-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" xmlns:mda="urn:oasis:names:tc:xliff:metadata:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.xml">
					  <unit id="u1">
					   <mda:metadata>
					    <mda:metaGroup category="tagClones">
					     <mda:meta type="1.d2">1</mda:meta>
					    </mda:metaGroup>
					   </mda:metadata>
					   <originalData>
					    <data id="d3">&lt;br/&gt;</data>
					   </originalData>
					   <segment id="s1">
					    <source>Hello <ph id="1" dataRef="d3"/> world</source>
					    <target>Hallo <ph id="1" dataRef="d3"/> Welt <ph id="1.d2"/> nochmal</target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);
			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " status", Constants.SUCCESS, from.get(0));
			String xml12 = Files.readString(out12);
			Matcher m = Pattern.compile("<target[^>]*>([\\s\\S]*?)</target>").matcher(xml12);
			if (!m.find()) {
				fail(name + ": no target");
				return;
			}
			String target = m.group(1);
			int count = 0;
			Matcher ph = Pattern.compile("<ph[^>]*>&lt;br/&gt;</ph>").matcher(target);
			while (ph.find()) {
				count++;
			}
			assertEquals(name + " both ph carry the payload: " + target, 2, count);
			if (!target.contains("id=\"1.d2\"")) {
				fail(name + ": clone id lost: " + target);
			}
		} finally {
			deleteRecursive(dir);
		}
	}

	private static void testClonedPhWithoutMetadataStillUsesDataRef(Path catalogPath) throws Exception {
		String name = "clone with dataRef merges without metadata";
		Path dir = Files.createTempDirectory("oxlf-tagclones-");
		try {
			Path xliff = dir.resolve("in.xlf");
			Files.writeString(xliff, """
					<?xml version="1.0" encoding="UTF-8"?>
					<xliff xmlns="urn:oasis:names:tc:xliff:document:2.0" version="2.0" srcLang="en" trgLang="de">
					 <file id="f1" original="t.xml">
					  <unit id="u1">
					   <originalData>
					    <data id="d3">{0}</data>
					   </originalData>
					   <segment id="s1">
					    <source>A <ph id="1" dataRef="d3"/></source>
					    <target><ph id="1.d2" dataRef="d3"/> B <ph id="1" dataRef="d3"/></target>
					   </segment>
					  </unit>
					 </file>
					</xliff>
					""", StandardCharsets.UTF_8);
			Path out12 = dir.resolve("out12.xlf");
			List<String> from = FromXliff2.run(xliff.toString(), out12.toString(), catalogPath.toString());
			assertEquals(name + " status", Constants.SUCCESS, from.get(0));
			String xml12 = Files.readString(out12);
			Matcher m = Pattern.compile("<target[^>]*>([\\s\\S]*?)</target>").matcher(xml12);
			if (!m.find()) {
				fail(name + ": no target");
				return;
			}
			String target = m.group(1);
			int count = 0;
			Matcher ph = Pattern.compile("<ph[^>]*>\\{0\\}</ph>").matcher(target);
			while (ph.find()) {
				count++;
			}
			assertEquals(name + " both ph carry the payload: " + target, 2, count);
		} finally {
			deleteRecursive(dir);
		}
	}

	private static void assertEquals(String name, Object expected, Object actual) {
		if (expected == null ? actual != null : !expected.equals(actual)) {
			fail(name + ": expected <" + expected + "> but was <" + actual + ">");
		} else {
			System.out.println("ok   " + name);
		}
	}

	private static void fail(String message) {
		failures++;
		System.err.println("FAIL " + message);
	}

	private static void deleteRecursive(Path dir) throws Exception {
		if (!Files.exists(dir)) {
			return;
		}
		try (var stream = Files.walk(dir)) {
			stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (Exception ignore) {
					// best effort
				}
			});
		}
	}
}
