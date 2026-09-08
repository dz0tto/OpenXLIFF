/*******************************************************************************
 * Copyright (c) 2016-2026 Maxprograms.
 *
 * Contributors:
 *     Maxprograms - initial API and implementation
 *******************************************************************************/
package com.maxprograms.converters.msoffice;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import com.maxprograms.xml.Element;
import com.maxprograms.xml.SAXBuilder;
import com.maxprograms.xml.TextNode;

/**
 * Runnable via {@code ant test}. Exits 0 on success, 1 on failure.
 * Asserts digits-only and tags-only sources stay visible segments for DOCX convert.
 */
public final class MSOfficeKeepSegmentsTest {

	private static int failures;

	private MSOfficeKeepSegmentsTest() {
	}

	public static void main(String[] args) throws Exception {
		Method containsText = MSOffice2Xliff.class.getDeclaredMethod("containsText", Element.class);
		containsText.setAccessible(true);
		Method hasVisible = MSOffice2Xliff.class.getDeclaredMethod("hasVisibleSegmentContent", Element.class);
		hasVisible.setAccessible(true);

		// writeSegment builds a flat <source> of text nodes + <ph>; digits arrive as text.
		assertTrue("digits-only is visible text", (Boolean) containsText.invoke(null, sourceText("12345")));
		assertTrue("digits-only is visible segment", (Boolean) hasVisible.invoke(null, sourceText("12345")));

		assertFalse("pure whitespace is not text", (Boolean) containsText.invoke(null, sourceText("   ")));
		assertFalse("pure whitespace is not visible", (Boolean) hasVisible.invoke(null, sourceText("   ")));

		Element tagsOnly = parseSource("<source xmlns:m=\"http://schemas.openxmlformats.org/officeDocument/2006/math\">"
				+ "<ph id=\"1\">&lt;m:oMath&gt;</ph><ph id=\"2\">&lt;/m:oMath&gt;</ph></source>");
		assertFalse("tags-only has no plain text", (Boolean) containsText.invoke(null, tagsOnly));
		assertTrue("tags-only is still a visible segment", (Boolean) hasVisible.invoke(null, tagsOnly));

		Element mixed = parseSource("<source>Hello <ph id=\"1\">&lt;br/&gt;</ph> world</source>");
		assertTrue("mixed text+tags is text", (Boolean) containsText.invoke(null, mixed));
		assertTrue("mixed text+tags is visible", (Boolean) hasVisible.invoke(null, mixed));

		if (failures > 0) {
			System.err.println(failures + " failure(s)");
			System.exit(1);
		}
		System.out.println("MSOfficeKeepSegmentsTest: all passed");
	}

	private static Element sourceText(String text) {
		Element source = new Element("source");
		source.addContent(new TextNode(text));
		return source;
	}

	private static Element parseSource(String xml) throws Exception {
		SAXBuilder builder = new SAXBuilder();
		return builder.build(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getRootElement();
	}

	private static void assertTrue(String name, boolean value) {
		if (!value) {
			failures++;
			System.err.println("FAIL: " + name);
		} else {
			System.out.println("ok: " + name);
		}
	}

	private static void assertFalse(String name, boolean value) {
		assertTrue(name, !value);
	}
}
