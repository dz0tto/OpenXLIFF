/*******************************************************************************
 * Copyright (c) 2018 - 2025 Maxprograms.
 *
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/epl-v10.html
 *
 * Contributors:
 *     Maxprograms - initial API and implementation
 *******************************************************************************/
package com.maxprograms.xliff2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import com.maxprograms.converters.Constants;
import com.maxprograms.segmenter.Segmenter;
import com.maxprograms.xml.Catalog;
import com.maxprograms.xml.Document;
import com.maxprograms.xml.Element;
import com.maxprograms.xml.Indenter;
import com.maxprograms.xml.SAXBuilder;
import com.maxprograms.xml.TextNode;
import com.maxprograms.xml.XMLNode;
import com.maxprograms.xml.XMLOutputter;

public class Resegmenter {

    private static Segmenter segmenter;
    private static boolean canResegment;
    private static boolean translate;

    private Resegmenter() {
        // do not instantiate this class
        // use run method instead
    }

    public static List<String> run(String xliff, String srx, String srcLang, Catalog catalog) {
        List<String> result = new ArrayList<>();
        try {
            segmenter = new Segmenter(srx, srcLang, catalog);
            SAXBuilder builder = new SAXBuilder();
            builder.setEntityResolver(catalog);
            Document doc = builder.build(xliff);
            Element root = doc.getRootElement();
            recurse(root);
            try (FileOutputStream out = new FileOutputStream(new File(xliff))) {
                XMLOutputter outputter = new XMLOutputter();
                outputter.preserveSpace(true);
                Indenter.indent(root, 2);
                outputter.output(doc, out);
            }
            result.add(Constants.SUCCESS);
        } catch (SAXException | IOException | ParserConfigurationException e) {
            Logger logger = System.getLogger(Resegmenter.class.getName());
            logger.log(Level.ERROR, Messages.getString("Resegmenter.1"), e);
            result.add(Constants.ERROR);
            result.add(e.getMessage());
        }
        return result;
    }

    private static void recurse(Element root) throws SAXException, IOException, ParserConfigurationException {
        if ("file".equals(root.getName())) {
            canResegment = "yes".equals(root.getAttributeValue("canResegment", "yes"));
            translate = "yes".equals(root.getAttributeValue("translate", "yes"));
        } else if (root.hasAttribute("canResegment")) {
            canResegment = "yes".equals(root.getAttributeValue("canResegment", canResegment ? "yes" : "no"));
            translate = "yes".equals(root.getAttributeValue("translate", translate ? "yes" : "no"));
        }
        if ("unit".equals(root.getName())) {
            boolean hasMatches = !root.getChildren("mtc:matches").isEmpty();
            // Resegment every eligible segment — including units that already have more
            // than one segment (e.g. after a newlines-only SRX pass, before sentence SRX).
            if (translate && canResegment && !hasMatches && !root.getChildren("segment").isEmpty()) {
                resegmentUnit(root);
            }
        } else {
            List<Element> children = root.getChildren();
            Iterator<Element> it = children.iterator();
            while (it.hasNext()) {
                recurse(it.next());
            }
        }
    }

    private static void resegmentUnit(Element unit) throws SAXException, IOException, ParserConfigurationException {
        String unitId = unit.getAttributeValue("id");
        List<XMLNode> original = new ArrayList<>(unit.getContent());
        List<XMLNode> rebuilt = new ArrayList<>();
        int id = 0;
        int ignorableId = 0;
        boolean changed = false;
        int inputSegments = unit.getChildren("segment").size();

        for (XMLNode node : original) {
            if (node.getNodeType() != XMLNode.ELEMENT_NODE) {
                rebuilt.add(node);
                continue;
            }
            Element el = (Element) node;
            if (!"segment".equals(el.getName())) {
                rebuilt.add(el);
                continue;
            }

            Element source = el.getChild("source");
            Element target = el.getChild("target");
            boolean isSourceCopy = target != null && source.getContent().equals(target.getContent());
            boolean isEmpty = target != null && target.getContent().isEmpty();
            if (target != null && !isSourceCopy && !isEmpty) {
                // Translated target present — leave content, but renumber when the unit
                // is being rebuilt so ids stay unique alongside newly split segments.
                if (inputSegments > 1) {
                    el.setAttribute("id", unitId + '-' + id++);
                    changed = true;
                }
                rebuilt.add(el);
                continue;
            }

            String originalId = el.getAttributeValue("id");
            Element segSource = segmenter.segment(source);
            keepPairedInlineTogether(segSource);
            int newSegments = segSource.getChildren("mrk").size();
            int emittedBefore = rebuilt.size();

            List<XMLNode> content = segSource.getContent();
            Iterator<XMLNode> it = content.iterator();
            while (it.hasNext()) {
                XMLNode n = it.next();
                if (n.getNodeType() != XMLNode.ELEMENT_NODE) {
                    continue;
                }
                Element e = (Element) n;
                if ("mrk".equals(e.getName()) && "seg".equals(e.getAttributeValue("mtype"))) {
                    // Do not peel leading/trailing inline tags into <ignorable>.
                    // Tags stay on the segment so translators see and manage them.
                    // SRX leaves join whitespace on segment N+1; peel into <ignorable>
                    // so editors see clean text and export rejoins via FromXliff2.
                    String leadingWs = peelLeadingWhitespace(e);
                    boolean tagOnly = hasInlineTags(e);
                    boolean textOnlyBlank = !hasText(e) && !tagOnly;
                    if (textOnlyBlank) {
                        // Empty / whitespace-only SRX leftover (e.g. blank line between
                        // newlines). Keep peeled join space as ignorable; skip empty bits.
                        if (!leadingWs.isEmpty()) {
                            rebuilt.add(makeIgnorable(unitId, ignorableId++, leadingWs));
                            changed = true;
                        }
                        continue;
                    }
                    if (!leadingWs.isEmpty()) {
                        rebuilt.add(makeIgnorable(unitId, ignorableId++, leadingWs));
                    }
                    // Tag-only (inline placeholders, no plain text) stays a <segment>
                    // so it appears in the editor — same policy as the converters.
                    Element newSeg = new Element("segment");
                    boolean keepOriginalId = newSegments == 1 && inputSegments == 1;
                    newSeg.setAttribute("id", keepOriginalId ? originalId : unitId + '-' + id++);
                    changed = true;
                    Element newSource = new Element("source");
                    newSource.setAttribute("xml:space", source.getAttributeValue("xml:space", "default"));
                    newSeg.addContent(newSource);
                    newSource.addContent(e.getContent());
                    Element newTarget = null;
                    if (isSourceCopy) {
                        newTarget = new Element("target");
                        newTarget.setAttribute("xml:space",
                                source.getAttributeValue("xml:space", "default"));
                        newSeg.addContent(newTarget);
                        newTarget.addContent(e.getContent());
                    }
                    // Newline SRX (beforebreak) leaves the break at the end of
                    // segment N. Peel only line-break chars into a following
                    // <ignorable> so the editor source stays clean and export
                    // restores every separator via FromXliff2. Do not peel
                    // spaces — sentence SRX keeps those on N+1 for the leading peel.
                    String trailingBreaks = peelTrailingLineBreaks(newSource);
                    if (!trailingBreaks.isEmpty() && newTarget != null) {
                        peelTrailingLineBreaks(newTarget);
                    }
                    rebuilt.add(newSeg);
                    if (!trailingBreaks.isEmpty()) {
                        rebuilt.add(makeIgnorable(unitId, ignorableId++, trailingBreaks));
                    }
                } else {
                    MessageFormat mf = new MessageFormat(Messages.getString("Resegmenter.2"));
                    throw new SAXException(mf.format(new String[] { e.toString() }));
                }
            }
            // If SRX produced nothing usable, keep the original segment (never drop tags).
            if (rebuilt.size() == emittedBefore) {
                rebuilt.add(el);
            }
        }

        if (changed) {
            unit.setContent(rebuilt);
        }
    }

    /**
     * SRX may split after a sentence that sits between {@code sc} and its matching
     * {@code ec}. Merge those {@code mrk} pieces so a pair is never split across
     * segments. Isolated {@code sc}/{@code ec} (no mate in this unit) are left as-is.
     */
    private static void keepPairedInlineTogether(Element segSource) {
        if (segSource == null) {
            return;
        }
        List<Element> mrks = segSource.getChildren("mrk");
        if (mrks.size() <= 1) {
            return;
        }
        Map<String, Integer> scIndex = new HashMap<>();
        Map<String, Integer> ecIndex = new HashMap<>();
        for (int i = 0; i < mrks.size(); i++) {
            collectPairEnds(mrks.get(i), i, scIndex, ecIndex);
        }
        boolean[] mergeNext = new boolean[mrks.size()];
        boolean any = false;
        for (Map.Entry<String, Integer> sc : scIndex.entrySet()) {
            Integer ecAt = ecIndex.get(sc.getKey());
            if (ecAt == null) {
                continue;
            }
            int from = Math.min(sc.getValue(), ecAt);
            int to = Math.max(sc.getValue(), ecAt);
            for (int i = from; i < to; i++) {
                mergeNext[i] = true;
                any = true;
            }
        }
        if (!any) {
            return;
        }
        List<XMLNode> rebuilt = new ArrayList<>();
        int i = 0;
        while (i < mrks.size()) {
            Element acc = mrks.get(i);
            while (i < mrks.size() - 1 && mergeNext[i]) {
                i++;
                acc.addContent(mrks.get(i).getContent());
            }
            rebuilt.add(acc);
            i++;
        }
        for (int m = 0; m < rebuilt.size(); m++) {
            Element mrk = (Element) rebuilt.get(m);
            mrk.setAttribute("mid", String.valueOf(m + 1));
        }
        segSource.setContent(rebuilt);
    }

    private static void collectPairEnds(Element e, int index, Map<String, Integer> scIndex,
            Map<String, Integer> ecIndex) {
        if (e == null) {
            return;
        }
        if ("sc".equals(e.getName())) {
            String id = e.getAttributeValue("id");
            if (!id.isEmpty() && !scIndex.containsKey(id)) {
                scIndex.put(id, index);
            }
        } else if ("ec".equals(e.getName())) {
            String startRef = e.getAttributeValue("startRef");
            if (startRef.isEmpty()) {
                startRef = e.getAttributeValue("id");
            }
            if (!startRef.isEmpty() && !ecIndex.containsKey(startRef)) {
                ecIndex.put(startRef, index);
            }
        }
        List<Element> children = e.getChildren();
        Iterator<Element> it = children.iterator();
        while (it.hasNext()) {
            collectPairEnds(it.next(), index, scIndex, ecIndex);
        }
    }

    private static boolean hasText(Element e) {
        List<XMLNode> content = e.getContent();
        Iterator<XMLNode> it = content.iterator();
        while (it.hasNext()) {
            XMLNode node = it.next();
            if (node.getNodeType() == XMLNode.TEXT_NODE) {
                TextNode t = (TextNode) node;
                if (!t.getText().isBlank()) {
                    return true;
                }
            }
            if (node.getNodeType() == XMLNode.ELEMENT_NODE) {
                Element child = (Element) node;
                if (hasText(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * True when the element contains any inline markup (ph/pc/x/…), including nested
     * placeholders. Tag-only SRX pieces must stay visible segments.
     */
    private static boolean hasInlineTags(Element e) {
        if (e == null) {
            return false;
        }
        List<XMLNode> content = e.getContent();
        if (content == null) {
            return false;
        }
        Iterator<XMLNode> it = content.iterator();
        while (it.hasNext()) {
            XMLNode node = it.next();
            if (node.getNodeType() == XMLNode.ELEMENT_NODE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a leading run of join whitespace from {@code mrk} content and return it.
     * SRX sentence breaks keep the after-break whitespace on the following part.
     */
    private static String peelLeadingWhitespace(Element mrk) {
        List<XMLNode> content = mrk.getContent();
        if (content == null || content.isEmpty()) {
            return "";
        }
        XMLNode first = content.get(0);
        if (first.getNodeType() != XMLNode.TEXT_NODE) {
            return "";
        }
        TextNode textNode = (TextNode) first;
        String text = textNode.getText();
        if (text == null || text.isEmpty()) {
            return "";
        }
        int i = 0;
        while (i < text.length() && isJoinWhitespace(text.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return "";
        }
        String leading = text.substring(0, i);
        String rest = text.substring(i);
        if (rest.isEmpty()) {
            List<XMLNode> next = new ArrayList<>(content);
            next.remove(0);
            mrk.setContent(next);
        } else {
            textNode.setText(rest);
        }
        return leading;
    }

    private static boolean isJoinWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u00A0';
    }

    /**
     * Remove a trailing run of line-break characters from {@code container} and
     * return it. Spaces and tabs stay on the segment so sentence SRX is unchanged.
     */
    private static String peelTrailingLineBreaks(Element container) {
        List<XMLNode> content = container.getContent();
        if (content == null || content.isEmpty()) {
            return "";
        }
        for (int i = content.size() - 1; i >= 0; i--) {
            XMLNode node = content.get(i);
            if (node.getNodeType() != XMLNode.TEXT_NODE) {
                return "";
            }
            TextNode textNode = (TextNode) node;
            String text = textNode.getText();
            if (text == null || text.isEmpty()) {
                continue;
            }
            int end = text.length();
            while (end > 0 && isLineBreakChar(text.charAt(end - 1))) {
                end--;
            }
            if (end == text.length()) {
                return "";
            }
            String peeled = text.substring(end);
            if (end == 0) {
                List<XMLNode> next = new ArrayList<>(content);
                next.remove(i);
                container.setContent(next);
            } else {
                textNode.setText(text.substring(0, end));
            }
            return peeled;
        }
        return "";
    }

    private static boolean isLineBreakChar(char c) {
        return c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029';
    }

    private static Element makeIgnorable(String unitId, int ignorableId, String text) {
        Element ignorable = new Element("ignorable");
        ignorable.setAttribute("id", unitId + "-i" + ignorableId);
        Element ignorableSource = new Element("source");
        ignorableSource.setAttribute("xml:space", "preserve");
        ignorableSource.addContent(text);
        ignorable.addContent(ignorableSource);
        return ignorable;
    }
}
