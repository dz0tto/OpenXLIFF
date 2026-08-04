/*******************************************************************************
 * Copyright (c) 2018 - 2025 Maxprograms.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 1.0 which accompanies this distribution,
 * and is available at https://www.eclipse.org/org/documents/epl-v10.html
 *
 * Contributors: Maxprograms - initial API and implementation
 *******************************************************************************/

package com.maxprograms.converters.xliff;

import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import javax.xml.parsers.ParserConfigurationException;

import org.xml.sax.SAXException;

import com.maxprograms.converters.Constants;
import com.maxprograms.xml.Attribute;
import com.maxprograms.xml.CatalogBuilder;
import com.maxprograms.xml.Document;
import com.maxprograms.xml.Element;
import com.maxprograms.xml.Indenter;
import com.maxprograms.xml.PI;
import com.maxprograms.xml.SAXBuilder;
import com.maxprograms.xml.TextNode;
import com.maxprograms.xml.XMLNode;
import com.maxprograms.xml.XMLOutputter;
import com.maxprograms.xml.XMLUtils;

public class ToOpenXliff {

    private static List<String> namespaces;
    private static int tag;
    private static boolean preserveSpaces = false;
    private static List<String[]> sourcetags;

    private static String normalizeInlineId(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String t = rawId.trim();
        if (t.length() > 1 && (t.charAt(0) == 'x' || t.charAt(0) == 'p')
                && Character.isDigit(t.charAt(1))) {
            return t.substring(1);
        }
        return t;
    }

    private static boolean isLevshaStyleInline(Element e) {
        return e.hasAttribute("equiv-text") || e.hasAttribute("ctype");
    }

    /**
     * Build the historic OpenXLIFF ph-text shape {@code <x …>escaped-payload</x>} without
     * {@link Element#toString()}, which re-serializes attribute values and collapses nested
     * entity encoding ({@code &amp;lt;} → {@code &lt;}). FromOpenXliff.replaceTags parses this
     * string back into an {@code <x>} so export can restore MemoQ {@code <ph>} wrappers.
     */
    private static String wrapLevshaInlineMarkup(String id, String equiv, String payload) {
        StringBuilder sb = new StringBuilder();
        sb.append("<x");
        if (id != null && !id.isEmpty()) {
            sb.append(" id=\"");
            sb.append(XMLUtils.cleanText(id).replace("\"", "&quot;"));
            sb.append('"');
        }
        if (equiv != null && !equiv.isEmpty()) {
            sb.append(" equiv-text=\"");
            sb.append(XMLUtils.cleanText(equiv).replace("\"", "&quot;"));
            sb.append('"');
        }
        sb.append('>');
        sb.append(XMLUtils.cleanText(payload));
        sb.append("</x>");
        return sb.toString();
    }

    private static String inlinePhId(Element e) {
        if (isLevshaStyleInline(e)) {
            String preserved = normalizeInlineId(e.getAttributeValue("id"));
            if (preserved != null && !preserved.isEmpty()) {
                return preserved;
            }
        }
        return String.valueOf(tag++);
    }

    private ToOpenXliff() {
        // do not instantiate this class
        // use run method instead
    }

    public static List<String> run(Map<String, String> params) {
        List<String> result = new ArrayList<>();
        String inputFile = params.get("source");
        String xliffFile = params.get("xliff");
        String skeletonFile = params.get("skeleton");
        String sourceLanguage = params.get("srcLang");
        String targetLanguage = params.get("tgtLang");
        String catalog = params.get("catalog");
        // optional parameters
        String idAttribute = params.get("idAttribute");
        String charlimAttribute = params.get("charlimAttribute");
        String contextAttribute = params.get("contextAttribute");
        try {
            SAXBuilder builder = new SAXBuilder();
            builder.setEntityResolver(CatalogBuilder.getCatalog(catalog));
            Document doc = builder.build(inputFile);
            Element root = doc.getRootElement();

            Document newDoc = new Document(null, "xliff", null, null);
            Element newRoot = newDoc.getRootElement();
            newRoot.setAttribute("version", "1.2");
            newRoot.setAttribute("xmlns", "urn:oasis:names:tc:xliff:document:1.2");
            newRoot.addContent(new PI("encoding", doc.getEncoding().name()));

            Element file = new Element("file");
            file.setAttribute("datatype", "x-xliff");
            file.setAttribute("source-language", sourceLanguage);
            if (targetLanguage != null && !targetLanguage.isEmpty()) {
                file.setAttribute("target-language", targetLanguage);
            }
            file.setAttribute("original", inputFile);
            newRoot.addContent(file);

            Element header = new Element("header");
            file.addContent(header);

            Element skl = new Element("skl");
            header.addContent(skl);

            Element externalFile = new Element("external-file");
            externalFile.setAttribute("href", skeletonFile);
            skl.addContent(externalFile);

            Element tool = new Element("tool");
            tool.setAttribute("tool-id", Constants.TOOLID);
            tool.setAttribute("tool-name", Constants.TOOLNAME);
            tool.setAttribute("tool-version", Constants.VERSION);
            header.addContent(tool);

            List<Element> units = new Vector<>();

            if (root.getAttributeValue("version").startsWith("1")) {
                namespaces = new ArrayList<>();
                recurse1x(root, units, idAttribute, charlimAttribute, contextAttribute);
            }
            if (root.getAttributeValue("version").startsWith("2")) {
                recurse2x(root, units, idAttribute, charlimAttribute, contextAttribute);
                if (!file.hasAttribute("target-language") && hasTarget(units)) {
                    throw new IOException(Messages.getString("ToOpenXliff.3"));
                }
            }
            if (units.isEmpty()) {
                result.add(Constants.ERROR);
                result.add(Messages.getString("ToOpenXliff.1"));
                return result;
            }

            Element body = new Element("body");
            file.addContent(body);
            for (int i = 0; i < units.size(); i++) {
                body.addContent(units.get(i));
            }

            try (FileOutputStream out = new FileOutputStream(xliffFile)) {
                Indenter.indent(newRoot, 2);
                XMLOutputter outputter = new XMLOutputter();
                outputter.preserveSpace(true);
                outputter.output(newDoc, out);
            }
            try (FileOutputStream skeleton = new FileOutputStream(skeletonFile)) {
                XMLOutputter outputter = new XMLOutputter();
                outputter.preserveSpace(true);
                outputter.output(doc, skeleton);
            }
            result.add(Constants.SUCCESS);
        } catch (IOException | SAXException | ParserConfigurationException | URISyntaxException e) {
            Logger logger = System.getLogger(ToOpenXliff.class.getName());
            logger.log(Level.ERROR, Messages.getString("ToOpenXliff.2"), e);
            result.add(Constants.ERROR);
            result.add(e.getMessage());
        }
        return result;
    }

    private static boolean hasTarget(List<Element> units) {
        for (Element unit : units) {
            if (unit.getChild("target") != null) {
                return true;
            }
        }
        return false;
    }

    private static void recurse2x(Element root, List<Element> units, String idAttribute, String charlimAttribute, String contextAttribute) {
        if ("unit".equals(root.getName()) && !root.getAttributeValue("translate").equals("no")) {
            boolean preserve = root.getAttributeValue("xml:space").equals("preserve");
            List<Element> segments = root.getChildren("segment");
            Iterator<Element> st = segments.iterator();
            while (st.hasNext()) {
                Element segment = st.next();
                boolean isFinal = segment.getAttributeValue("state").equals("final");
                Element unit = new Element("trans-unit");
                unit.setAttribute("id", "" + units.size());
                if (isFinal) {
                    unit.setAttribute("approved", "yes");
                }
                Element src = segment.getChild("source");
                if (preserve || src.getAttributeValue("xml:space").equals("preserve")) {
                    unit.setAttribute("xml:space", "preserve");
                }
                Element source = new Element("source");
                tag = 1;
                sourcetags = new Vector<>();
                source.setContent(getContent2x(src, true));
                if (!hasTranslatableText(source)) {
                    return;
                }
                unit.addContent(source);
                Element target = new Element("target");
                target.setContent(getContent2x(segment.getChild("target"), false));
                unit.addContent(target);
                // Always add context/note for every trans-unit
                List<Element> contextList = harvestContext(root, idAttribute, charlimAttribute, contextAttribute);
                Element unitNote = harvestNote(root);
                addContextInformation(unit, contextList, unitNote);
                if (segments.size() == 1) {
                    // Handle notes element (multiple notes)
                    Element notes = root.getChild("notes");
                    if (notes != null) {
                        List<Element> noteList = notes.getChildren("note");
                        Iterator<Element> it = noteList.iterator();
                        while (it.hasNext()) {
                            Element note = it.next();
                            Element n = new Element("note");
                            n.setText(note.getText());
                            if (note.hasAttribute("priority")) {
                                n.setAttribute("priority", note.getAttributeValue("priority"));
                            }
                            if (note.hasAttribute("annotates")) {
                                String value = note.getAttributeValue("annotates");
                                if ("source".equals(value) || "target".equals(value)) {
                                    n.setAttribute("appliesTo", value);
                                }
                            }
                            unit.addContent(n);
                        }
                    }
                }
                Element matchesHolder = root.getChild("mtc:matches");
                if (matchesHolder != null) {
                    List<Element> matches = matchesHolder.getChildren("mtc:match");
                    Iterator<Element> it = matches.iterator();
                    while (it.hasNext()) {
                        Element match = it.next();
                        String ref = match.getAttributeValue("ref");
                        if (ref.equals("#" + segment.getAttributeValue("id"))) {
                            Element altTrans = new Element("alt-trans");
                            String origin = match.getAttributeValue("origin");
                            if (!origin.isEmpty()) {
                                altTrans.setAttribute("origin", origin);
                            }
                            String quality = match.getAttributeValue("matchQuality");
                            if (!quality.isBlank()) {
                                altTrans.setAttribute("match-quality", quality);
                            }
                            Element altSource = new Element("source");
                            tag = 1;
                            sourcetags = new Vector<>();
                            altSource.setContent(getContent2x(match.getChild("source"), true));
                            altTrans.addContent(altSource);
                            Element altTarget = new Element("target");
                            altTarget.setContent(getContent2x(match.getChild("target"), false));
                            altTrans.addContent(altTarget);
                            if (!altSource.getContent().isEmpty() && !altTarget.getContent().isEmpty()) {
                                unit.addContent(altTrans);
                            }
                        }
                    }
                }
                units.add(unit);
                segment.addContent(new PI(Constants.TOOLID, unit.getAttributeValue("id")));
            }
            return;
        }
        List<Element> children = root.getChildren();
        Iterator<Element> it = children.iterator();
        while (it.hasNext()) {
            recurse2x(it.next(), units, idAttribute, charlimAttribute, contextAttribute);
        }
    }

    private static List<XMLNode> getContent2x(Element child, boolean inSource) {
        List<XMLNode> result = new Vector<>();
        if (child != null) {
            List<XMLNode> content = child.getContent();
            Iterator<XMLNode> it = content.iterator();
            while (it.hasNext()) {
                XMLNode node = it.next();
                if (node.getNodeType() == XMLNode.TEXT_NODE) {
                    result.add(node);
                }
                if (node.getNodeType() == XMLNode.ELEMENT_NODE) {
                    Element e = (Element) node;
                    String name = e.getName();
                    if ("pc".equals(name)) {
                        String head = XliffUtils.getHead(e);
                        Element ph1 = new Element("ph");
                        ph1.setText(head);
                        if (inSource) {
                            ph1.setAttribute("id", "" + tag++);
                            sourcetags.add(new String[] { head, "" + (tag - 1) });
                        } else {
                            int i = findFirstTag(head);
                            if (i != -1) {
                                ph1.setAttribute("id", "" + i);
                                sourcetags.remove(new String[] { head, "" + i });
                            } else {
                                ph1.setAttribute("id", "" + tag++);
                            }
                        }
                        result.add(ph1);

                        List<XMLNode> nested = getContent2x(e, inSource);
                        result.addAll(nested);

                        Element ph2 = new Element("ph");
                        ph2.setText("</pc>");
                        if (inSource) {
                            ph2.setAttribute("id", "" + tag++);
                            sourcetags.add(new String[] { head + "<tail/>", "" + (tag - 1) });
                        } else {
                            int i = findFirstTag(head + "<tail/>");
                            if (i != -1) {
                                ph2.setAttribute("id", "" + i);
                                sourcetags.remove(new String[] { head + "<tail/>", "" + i });
                            } else {
                                ph2.setAttribute("id", "" + tag++);
                            }
                        }
                        result.add(ph2);
                    }
                    if ("cp".equals(name)) {
                        Element ph = new Element("ph");
                        String text = e.toString();
                        ph.setText(text);
                        if (inSource) {
                            ph.setAttribute("id", "" + tag++);
                            sourcetags.add(new String[] { text, "" + (tag - 1) });
                        } else {
                            int i = findFirstTag(e.toString());
                            if (i != -1) {
                                ph.setAttribute("id", "" + i);
                                sourcetags.remove(new String[] { text, "" + i });
                            } else {
                                ph.setAttribute("id", "" + tag++);
                            }
                        }
                        result.add(ph);
                    }
                    if ("ph".equals(name) || "sc".equals(name) || "sm".equals(name)) {
                        Element ph = new Element("ph");
                        String text = e.toString();
                        ph.setText(text);
                        if (inSource) {
                            ph.setAttribute("id", "" + tag++);
                            sourcetags.add(new String[] { text, "" + (tag - 1) });
                        } else {
                            int i = findFirstTag(e.toString());
                            if (i != -1) {
                                ph.setAttribute("id", "" + i);
                                sourcetags.remove(new String[] { text, "" + i });
                            } else {
                                ph.setAttribute("id", "" + tag++);
                            }
                        }
                        result.add(ph);
                    }
                    if ("ec".equals(name)) {
                        Element ph = new Element("ph");
                        String text = e.toString();
                        ph.setText(text);
                        if (inSource) {
                            ph.setAttribute("id", "" + tag++);
                            sourcetags.add(new String[] { text, "" + (tag - 1) });
                        } else {
                            int i = findFirstTag(e.toString());
                            if (i != -1) {
                                ph.setAttribute("id", "" + i);
                                sourcetags.remove(new String[] { text, "" + i });
                            } else {
                                ph.setAttribute("id", "" + tag++);
                            }
                        }
                        result.add(ph);
                    }
                    if ("em".equals(name)) {
                        Element ph = new Element("ph");
                        String text = e.toString();
                        ph.setText(text);
                        if (inSource) {
                            ph.setAttribute("id", "" + tag++);
                            sourcetags.add(new String[] { text, "" + (tag - 1) });
                        } else {
                            int i = findFirstTag(e.toString());
                            if (i != -1) {
                                ph.setAttribute("id", "" + i);
                                sourcetags.remove(new String[] { text, "" + i });
                            } else {
                                ph.setAttribute("id", "" + tag++);
                            }
                        }
                        result.add(ph);
                    }
                    if ("mrk".equals(name)) {
                        Element mrk = new Element("mrk");
                        result.add(mrk);
                        boolean translate = e.getAttributeValue("translate", "yes").equals("yes");
                        if (!translate) {
                            mrk.setAttribute("mtype", "protected");
                        } else {
                            String type = e.getAttributeValue("type");
                            if (type.startsWith("oxlf:")) {
                                mrk.setAttribute("mtype", type.substring(5).replace("_", ":"));
                            } else {
                                mrk.setAttribute("mtype", "x-other");
                            }
                        }
                        if (e.hasAttribute("value")) {
                            mrk.setAttribute("ts", e.getAttributeValue("value"));
                        }
                        List<XMLNode> nested = getContent2x(e, inSource);
                        mrk.setContent(nested);
                    }
                }
            }
        }
        return result;
    }

    private static List<Element> harvestContext(Element unit, String idAttribute, String charlimAttribute, String contextAttribute) {
        List<Element> contextList = new ArrayList<>();

        boolean hasId = idAttribute != null && !idAttribute.isEmpty();
        boolean hasContext = contextAttribute != null && !contextAttribute.isEmpty();
        boolean hasCharlim = charlimAttribute != null && !charlimAttribute.isEmpty();
        
        Element contextGroup = new Element("context-group");
        contextGroup.setAttribute("name", "unit-context");

        // Handle context and maxlen as direct attributes on trans-unit
        if (hasContext || hasCharlim || hasId) {

            
            // Add ID as identifier context element
            if (hasId && unit.hasAttribute(idAttribute)) {
                Element idElement = new Element("context");
                idElement.setAttribute("context-type", "x-identifier");
                idElement.setText(unit.getAttributeValue(idAttribute));
                contextGroup.addContent(idElement);
            }
            
            // Add context attribute as context element
            if (hasContext && unit.hasAttribute(contextAttribute)) {
                Element contextElement = new Element("context");
                contextElement.setAttribute("context-type", "x-context");
                contextElement.setText(unit.getAttributeValue(contextAttribute));
                contextGroup.addContent(contextElement);
            }
            
            // Add maxlen attribute as context element for character limit
            String charlimValue = null;
            for (Attribute a : unit.getAttributes()) {
                if (a.getName().equals(charlimAttribute) || a.getName().endsWith(":" + charlimAttribute) || a.getName().endsWith(charlimAttribute)) {
                    charlimValue = a.getValue();
                    break;
                }
            }
            if (charlimValue != null) {
                Element maxlenElement = new Element("context");
                maxlenElement.setAttribute("context-type", "x-charlimit");
                maxlenElement.setText(charlimValue);
                contextGroup.addContent(maxlenElement);
            }
        }

        // Handle traditional context-group elements, add to  the same contextGroup if it exists
        List<Element> contextGroups = unit.getChildren("context-group");
        for (Element cg : contextGroups) {
            for (Element context : cg.getChildren("context")) {
                contextGroup.addContent(context);
            }
        }
        //check if contextGroup is empty
        if (!contextGroup.getChildren().isEmpty()) {
            contextList.add(contextGroup);
        }

        return contextList;
    }

    private static Element harvestNote(Element unit) {
        Element note = unit.getChild("note");
        return note;
    }

    private static void addContextInformation(Element targetUnit, List<Element> contextList, Element note) {
        // Add context-group elements directly to preserve structure and context-id
        if (!contextList.isEmpty()) {
            for (Element contextGroup : contextList) {
                Element newContextGroup = new Element("context-group");
                
                // Copy attributes from original context-group
                if (contextGroup.hasAttribute("name")) {
                    newContextGroup.setAttribute("name", contextGroup.getAttributeValue("name"));
                }
                if (contextGroup.hasAttribute("purpose")) {
                    newContextGroup.setAttribute("purpose", contextGroup.getAttributeValue("purpose"));
                }
                if (contextGroup.hasAttribute("crc")) {
                    newContextGroup.setAttribute("crc", contextGroup.getAttributeValue("crc"));
                }
                
                // Copy context elements without context-id attributes
                List<Element> contexts = contextGroup.getChildren("context");
                for (Element context : contexts) {
                    Element newContext = new Element("context");
                    
                    // Copy attributes excluding context-id
                    if (context.hasAttribute("context-type")) {
                        newContext.setAttribute("context-type", context.getAttributeValue("context-type"));
                    }
                    if (context.hasAttribute("match-mandatory")) {
                        newContext.setAttribute("match-mandatory", context.getAttributeValue("match-mandatory"));
                    }
                    if (context.hasAttribute("crc")) {
                        newContext.setAttribute("crc", context.getAttributeValue("crc"));
                    }
                    
                    // Copy the text content
                    newContext.setText(context.getText());
                    newContextGroup.addContent(newContext);
                }
                
                targetUnit.addContent(newContextGroup);
            }
        }
        
        // Add note separately if it exists
        if (note != null) {
            Element noteElement = new Element("note");
            noteElement.setText(note.getText());
            
            // Copy note attributes
            if (note.hasAttribute("priority")) {
                noteElement.setAttribute("priority", note.getAttributeValue("priority"));
            }
            if (note.hasAttribute("from")) {
                noteElement.setAttribute("from", note.getAttributeValue("from"));
            }
            if (note.hasAttribute("annotates")) {
                String value = note.getAttributeValue("annotates");
                if ("source".equals(value) || "target".equals(value)) {
                    noteElement.setAttribute("appliesTo", value);
                }
            }
            
            targetUnit.addContent(noteElement);
        }
    
    }
    private static int findFirstTag(String text) {
        for (String[] pair : sourcetags) {
            if (pair[0].equals(text)) {
                return Integer.parseInt(pair[1]);
            }
        }
        return -1;
    }

    private static void recurse1x(Element root, List<Element> units, String idAttribute, String charlimAttribute, String contextAttribute) {
        if ("xliff".equals(root.getName())) {
            List<Attribute> atts = root.getAttributes();
            Iterator<Attribute> it = atts.iterator();
            while (it.hasNext()) {
                Attribute a = it.next();
                if (a.getName().startsWith("xmlns:")) {
                    String ns = a.getName().substring("xmlns:".length());
                    if (!ns.equals("xml")) {
                        namespaces.add(ns);
                    }
                }
                if (a.getName().startsWith("x-workiva")) {
                    preserveSpaces = true;
                }
            }
            if (!namespaces.isEmpty()) {
                renameAttributes(root);
            }
        }
        if ("trans-unit".equals(root.getName()) && !root.getAttributeValue("translate").equals("no")) {
            Element segSource = root.getChild("seg-source");
            if (segSource != null) {
                List<XMLNode> content = segSource.getContent();
                Iterator<XMLNode> it = content.iterator();
                while (it.hasNext()) {
                    XMLNode node = it.next();
                    if (node.getNodeType() == XMLNode.ELEMENT_NODE) {
                        Element e = (Element) node;
                        if ("mrk".equals(e.getName()) && "seg".equals(e.getAttributeValue("mtype"))) {
                            Element unit = new Element("trans-unit");
                            unit.setAttribute("id", "" + units.size());
                            unit.setAttribute("approved", root.getAttributeValue("approved", "no"));
                            boolean space = root.getAttributeValue("xml:space").equals("preserve");
                            if (space) {
                                unit.setAttribute("xml:space", "preserve");
                            }
                            Element source = new Element("source");
                            tag = 1;
                            source.setContent(getContent1x(e));
                            if (!hasTranslatableText(source)) {
                                return;
                            }
                            unit.addContent(source);
                            Element target = new Element("target");
                            Element tgt = root.getChild("target");
                            if (tgt != null) {
                                tag = 1;
                                Element mrk = locateMrk(tgt, e.getAttributeValue("mid"));
                                if (mrk != null) {
                                    target.setContent(getContent1x(mrk));
                                }
                            }
                            unit.addContent(target);
                            // --- Add context and note for 1.x ---
                            List<Element> contextList = harvestContext(root, idAttribute, charlimAttribute, contextAttribute);
                            Element note = harvestNote(root);
                            addContextInformation(unit, contextList, note);
                            // ---
                            units.add(unit);
                            e.addContent(new PI(Constants.TOOLID, unit.getAttributeValue("id")));
                        }
                    }
                }
            } else {
                Element unit = new Element("trans-unit");
                unit.setAttribute("id", "" + units.size());
                unit.setAttribute("approved", root.getAttributeValue("approved", "no"));
                boolean space = root.getAttributeValue("xml:space").equals("preserve");
                if (space || preserveSpaces) {
                    unit.setAttribute("xml:space", "preserve");
                }

                Element source = new Element("source");
                tag = 1;
                source.setContent(getContent1x(root.getChild("source")));
                if (!hasTranslatableText(source)) {
                    return;
                }
                unit.addContent(source);
                Element target = new Element("target");
                tag = 1;
                target.setContent(getContent1x(root.getChild("target")));
                unit.addContent(target);

                List<Element> matches = root.getChildren("alt-trans");
                Iterator<Element> it = matches.iterator();
                while (it.hasNext()) {
                    Element match = it.next();
                    Element altTrans = new Element("alt-trans");
                    String origin = match.getAttributeValue("origin");
                    if (!origin.isEmpty()) {
                        altTrans.setAttribute("origin", origin);
                    }
                    String quality = match.getAttributeValue("match-quality");
                    if (!quality.isBlank()) {
                        altTrans.setAttribute("match-quality", quality);
                    }
                    Element altSource = new Element("source");
                    tag = 1;
                    altSource.setContent(getContent1x(match.getChild("source")));
                    altTrans.addContent(altSource);
                    Element altTarget = new Element("target");
                    tag = 1;
                    altTarget.setContent(getContent1x(match.getChild("target")));
                    altTrans.addContent(altTarget);
                    if (!altSource.getContent().isEmpty() && !altTarget.getContent().isEmpty()) {
                        unit.addContent(altTrans);
                    }
                }
                // --- Add context and note for 1.x ---
                List<Element> contextList = harvestContext(root, idAttribute, charlimAttribute, contextAttribute);
                Element note = harvestNote(root);
                addContextInformation(unit, contextList, note);
                // ---
                units.add(unit);
                root.addContent(new PI(Constants.TOOLID, unit.getAttributeValue("id")));
            }
            return;
        }
        List<Element> children = root.getChildren();
        Iterator<Element> it = children.iterator();
        while (it.hasNext()) {
            recurse1x(it.next(), units, idAttribute, charlimAttribute, contextAttribute);
        }
    }

    private static boolean hasTranslatableText(Element e) {
        if ("source".equals(e.getName()) || "target".equals(e.getName()) || "mrk".equals(e.getName())
                || "pc".equals(e.getName()) || "sub".equals(e.getName())) {
            // Keep units whose source is only inline tags (no plain text).
            if (XliffUtils.hasText(e) || !e.getChildren().isEmpty()) {
                return true;
            }
        }
        List<Element> children = e.getChildren();
        Iterator<Element> it = children.iterator();
        while (it.hasNext()) {
            if (hasTranslatableText(it.next())) {
                return true;
            }
        }
        return false;
    }

    protected static Element locateMrk(Element target, String mid) {
        List<XMLNode> content = target.getContent();
        Iterator<XMLNode> it = content.iterator();
        while (it.hasNext()) {
            XMLNode node = it.next();
            if (node.getNodeType() == XMLNode.ELEMENT_NODE) {
                Element e = (Element) node;
                if ("mrk".equals(e.getName()) && e.getAttributeValue("mid").equals(mid)) {
                    return e;
                }
            }
        }
        return null;
    }

    private static List<XMLNode> getContent1x(Element child) {
        List<XMLNode> result = new Vector<>();
        if (child != null) {
            List<XMLNode> content = child.getContent();
            Iterator<XMLNode> it = content.iterator();
            while (it.hasNext()) {
                XMLNode node = it.next();
                if (node.getNodeType() == XMLNode.TEXT_NODE) {
                    result.add(node);
                }
                if (node.getNodeType() == XMLNode.ELEMENT_NODE) {
                    Element e = (Element) node;
                    String name = e.getName();
                    if ("g".equals(name)) {
                        Element ph1 = new Element("ph");
                        ph1.setAttribute("id", "" + tag++);
                        ph1.setText(XliffUtils.getHead(e));
                        result.add(ph1);
                        if (e.getChildren().isEmpty()) {
                            result.add(new TextNode(e.getText()));
                        } else {
                            result.addAll(getContent1x(e));
                        }
                        Element ph2 = new Element("ph");
                        ph2.setAttribute("id", "" + tag++);
                        ph2.setText("</g>");
                        result.add(ph2);
                    }
                    if ("x".equals(name) || "bx".equals(name) || "ex".equals(name) || "ph".equals(name)
                            || "bpt".equals(name) || "ept".equals(name) || "it".equals(name)) {
                        Element ph = new Element("ph");
                        ph.setAttribute("id", inlinePhId(e));
                        // Levsha/MemoQ placeholders: keep opaque child text (mq:rxt payload) inside
                        // an <x> wrapper so FromOpenXliff.replaceTags can parse it. Do not use
                        // Element.toString() for the payload — it collapses nested entities.
                        if (isLevshaStyleInline(e)) {
                            String payload = e.getText();
                            String equiv = e.getAttributeValue("equiv-text", "");
                            if (equiv.isEmpty()) {
                                equiv = e.getAttributeValue("equiv", "");
                            }
                            if (payload != null && !payload.isEmpty()) {
                                ph.setText(wrapLevshaInlineMarkup(e.getAttributeValue("id", ""), equiv, payload));
                            } else {
                                ph.setText(e.toString());
                            }
                            if (!equiv.isEmpty()) {
                                ph.setAttribute("equiv-text", equiv);
                            }
                        } else {
                            ph.setText(e.toString());
                        }
                        result.add(ph);
                    }
                    if ("mrk".equals(name)) {
                        // add <mrk> as is
                        result.add(e);
                    }
                    if (!"g".equals(name) && !"x".equals(name) && !"bx".equals(name) && !"ex".equals(name)
                            && !"ph".equals(name) && !"bpt".equals(name) && !"ept".equals(name)
                            && !"it".equals(name) && !"mrk".equals(name)) {
                        // Tool-specific inline codes (e.g. MemoQ <mq:ch/>) must not be dropped;
                        // keep the original markup as a placeholder so it survives the round trip.
                        Element ph = new Element("ph");
                        ph.setAttribute("id", inlinePhId(e));
                        ph.setText(e.toString());
                        result.add(ph);
                    }
                }
            }
        }
        return result;
    }

    private static void renameAttributes(Element e) {
        List<Attribute> atts = e.getAttributes();
        Iterator<Attribute> at = atts.iterator();
        Vector<String> change = new Vector<>();
        while (at.hasNext()) {
            Attribute a = at.next();
            if (a.getName().indexOf(":") != -1) {
                String ns = a.getName().substring(0, a.getName().indexOf(":"));
                if (namespaces.contains(ns)) {
                    change.add(a.getName());
                }
            }
        }
        for (int i = 0; i < change.size(); i++) {
            String name = change.get(i);
            Attribute a = e.getAttribute(name);
            e.setAttribute(name.replace(":", "__"), a.getValue());
            e.removeAttribute(name);
        }
        List<Element> children = e.getChildren();
        Iterator<Element> it = children.iterator();
        while (it.hasNext()) {
            renameAttributes(it.next());
        }
    }
}