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

public class ToOpenXliff {

    private static List<String> namespaces;
    private static int tag;
    private static boolean preserveSpaces = false;

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
                recurse1x(root, units);
            }
            if (root.getAttributeValue("version").startsWith("2")) {
                recurse2x(root, units);
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

    private static void recurse2x(Element root, List<Element> units) {
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
                source.setContent(getContent2x(src));
                if (!hasTranslatableText(source)) {
                    return;
                }
                unit.addContent(source);
                Element target = new Element("target");
                tag = 1;
                target.setContent(getContent2x(segment.getChild("target")));
                unit.addContent(target);
                if (segments.size() == 1) {
                    // Handle context information from unit level
                    List<Element> contextList = harvestContext(root);
                    Element unitNote = harvestNote(root);
                    addContextInformation(unit, contextList, unitNote);
                    
                    // Handle notes element
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
                            altSource.setContent(getContent2x(match.getChild("source")));
                            altTrans.addContent(altSource);
                            Element altTarget = new Element("target");
                            tag = 1;
                            altTarget.setContent(getContent2x(match.getChild("target")));
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
            recurse2x(it.next(), units);
        }
    }

    private static List<XMLNode> getContent2x(Element child) {
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
                        Element ph1 = new Element("ph");
                        ph1.setAttribute("id", "" + tag++);
                        ph1.setText(XliffUtils.getHead(e));
                        result.add(ph1);

                        List<XMLNode> nested = getContent2x(e);
                        result.addAll(nested);

                        Element ph2 = new Element("ph");
                        ph2.setAttribute("id", "" + tag++);
                        ph2.setText("</pc>");
                        result.add(ph2);
                    }
                    if ("cp".equals(name)) {
                        Element ph = new Element("ph");
                        ph.setAttribute("id", "" + tag++);
                        ph.setText(e.toString());
                        result.add(ph);
                    }
                    if ("ph".equals(name) || "sc".equals(name) || "sm".equals(name)) {
                        Element ph = new Element("ph");
                        ph.setAttribute("id", "" + tag++);
                        ph.setText(e.toString());
                        result.add(ph);
                    }
                    if ("ec".equals(name)) {
                        Element ph = new Element("ph");
                        ph.setAttribute("id", "" + tag++);
                        ph.setText(e.toString());
                        result.add(ph);
                    }
                    if ("em".equals(name)) {
                        Element ph = new Element("ph");
                        ph.setAttribute("id", "" + tag++);
                        ph.setText(e.toString());
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
                        List<XMLNode> nested = getContent2x(e);
                        mrk.setContent(nested);
                    }
                }
            }
        }
        return result;
    }

    private static List<Element> harvestContext(Element unit) {
        List<Element> contextList = new ArrayList<>();
        
        // Handle traditional context-group elements
        List<Element> contextGroups = unit.getChildren("context-group");
        for (Element contextGroup : contextGroups) {
            contextList.add(contextGroup);
        }
        
        // Handle context and maxlen as direct attributes on trans-unit
        if (unit.hasAttribute("context") || unit.hasAttribute("maxlen")) {
            Element contextGroup = new Element("context-group");
            contextGroup.setAttribute("name", "unit-context");
            contextGroup.setAttribute("purpose", "information");
            
            // Add context attribute as context element
            if (unit.hasAttribute("context")) {
                Element contextElement = new Element("context");
                contextElement.setAttribute("context-type", "x-context");
                contextElement.setAttribute("context-id", unit.getAttributeValue("id", ""));
                contextElement.setText(unit.getAttributeValue("context"));
                contextGroup.addContent(contextElement);
            }
            
            // Add maxlen attribute as context element for character limit
            if (unit.hasAttribute("maxlen")) {
                Element maxlenElement = new Element("context");
                maxlenElement.setAttribute("context-type", "x-charlimit");
                maxlenElement.setAttribute("context-id", unit.getAttributeValue("id", ""));
                maxlenElement.setText(unit.getAttributeValue("maxlen"));
                contextGroup.addContent(maxlenElement);
            }
            
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
                
                // Copy context elements with their context-id and other attributes
                List<Element> contexts = contextGroup.getChildren("context");
                for (Element context : contexts) {
                    Element newContext = new Element("context");
                    
                    // Copy all attributes including context-id
                    if (context.hasAttribute("context-type")) {
                        newContext.setAttribute("context-type", context.getAttributeValue("context-type"));
                    }
                    if (context.hasAttribute("context-id")) {
                        newContext.setAttribute("context-id", context.getAttributeValue("context-id"));
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

    private static void recurse1x(Element root, List<Element> units) {
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
                List<Element> contextList = harvestContext(root);
                Element note = harvestNote(root);
                addContextInformation(unit, contextList, note);
                units.add(unit);
                root.addContent(new PI(Constants.TOOLID, unit.getAttributeValue("id")));
            }
            return;
        }
        List<Element> children = root.getChildren();
        Iterator<Element> it = children.iterator();
        while (it.hasNext()) {
            recurse1x(it.next(), units);
        }
    }

    private static boolean hasTranslatableText(Element e) {
        if (("source".equals(e.getName()) || "target".equals(e.getName()) || "mrk".equals(e.getName())
                || "pc".equals(e.getName()) || "sub".equals(e.getName())) && XliffUtils.hasText(e)) {
            return true;
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
                        ph.setAttribute("id", "" + tag++);
                        ph.setText(e.toString());
                        result.add(ph);
                    }
                    if ("mrk".equals(name)) {
                        // add <mrk> as is
                        result.add(e);
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