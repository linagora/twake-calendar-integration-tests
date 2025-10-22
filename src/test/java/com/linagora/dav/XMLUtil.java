/********************************************************************
 *  As a subpart of Twake Mail, this file is edited by Linagora.    *
 *                                                                  *
 *  https://twake-mail.com/                                         *
 *  https://linagora.com                                            *
 *                                                                  *
 *  This file is subject to The Affero Gnu Public License           *
 *  version 3.                                                      *
 *                                                                  *
 *  https://www.gnu.org/licenses/agpl-3.0.en.html                   *
 *                                                                  *
 *  This program is distributed in the hope that it will be         *
 *  useful, but WITHOUT ANY WARRANTY; without even the implied      *
 *  warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR         *
 *  PURPOSE. See the GNU Affero General Public License for          *
 *  more details.                                                   *
 ********************************************************************/

package com.linagora.dav;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.google.common.collect.ImmutableList;

public class XMLUtil {

    public static class DavNamespaceContext implements NamespaceContext {
        @Override
        public String getNamespaceURI(String prefix) {
            if ("d".equals(prefix)) {
                return "DAV:";
            }
            return XMLConstants.NULL_NS_URI;
        }

        @Override
        public String getPrefix(String namespaceURI) {
            return null;
        }

        @Override
        public java.util.Iterator<String> getPrefixes(String namespaceURI) {
            return null;
        }
    }

    private static final DocumentBuilderFactory DOCUMENT_BUILDER_FACTORY;
    private static final XmlMapper xmlMapper = new XmlMapper();

    static {
        try {
            DOCUMENT_BUILDER_FACTORY = DocumentBuilderFactory.newInstance();
            DOCUMENT_BUILDER_FACTORY.setNamespaceAware(true); // Enable namespace awareness

            //REDHAT
            //https://www.blackhat.com/docs/us-15/materials/us-15-Wang-FileCry-The-New-Age-Of-XXE-java-wp.pdf
            DOCUMENT_BUILDER_FACTORY.setAttribute(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            DOCUMENT_BUILDER_FACTORY.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            DOCUMENT_BUILDER_FACTORY.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            //OWASP
            //https://cheatsheetseries.owasp.org/cheatsheets/XML_External_Entity_Prevention_Cheat_Sheet.html
            DOCUMENT_BUILDER_FACTORY.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DOCUMENT_BUILDER_FACTORY.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DOCUMENT_BUILDER_FACTORY.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            // Disable external DTDs as well
            DOCUMENT_BUILDER_FACTORY.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            // and these as well, per Timothy Morgan's 2014 paper: "XML Schema, DTD, and Entity Attacks"
            DOCUMENT_BUILDER_FACTORY.setXIncludeAware(false);
            DOCUMENT_BUILDER_FACTORY.setExpandEntityReferences(false);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException(e);
        }
    }

    public static List<String> extractEventIdsFromXml(byte[] xml) throws ParserConfigurationException, IOException, SAXException, XPathExpressionException {
        Document doc = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(xml));
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new DavNamespaceContext());
        String expression = "/d:multistatus/d:response/d:href";
        NodeList nodes = (NodeList) xpath.evaluate(expression, doc, XPathConstants.NODESET);
        ImmutableList.Builder<String> eventIds = new ImmutableList.Builder<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            String href = nodes.item(i).getTextContent();
            if (href.endsWith(".ics")) {
                String filename = href.substring(href.lastIndexOf('/') + 1, href.length() - 4);
                eventIds.add(filename);
            }
        }
        return eventIds.build();
    }

    public static String extractByXPath(String xml, String xpathExpr, Map<String, String> namespaces) throws Exception {
        Document doc = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            public String getNamespaceURI(String prefix) {
                return namespaces.get(prefix);
            }
            public String getPrefix(String uri) { return null; }
            public Iterator getPrefixes(String uri) { return null; }
        });
        return xpath.evaluate(xpathExpr, doc);
    }

    public static List<String> extractMultipleValueByXPath(String xml, String xpathExpr, Map<String, String> namespaces) throws Exception {
        Document doc = DOCUMENT_BUILDER_FACTORY.newDocumentBuilder()
            .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        XPath xpath = XPathFactory.newInstance().newXPath();
        xpath.setNamespaceContext(new NamespaceContext() {
            public String getNamespaceURI(String prefix) {
                return namespaces.get(prefix);
            }
            public String getPrefix(String uri) { return null; }
            public Iterator getPrefixes(String uri) { return null; }
        });
        NodeList nodes = (NodeList) xpath.evaluate(xpathExpr, doc, XPathConstants.NODESET);
        List<String> results = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            results.add(nodes.item(i).getTextContent());
        }
        return results;
    }

    public static List<String> extractCalendarHrefsFromPropfind(String xml) {
        List<String> hrefs = new ArrayList<>();
        try {
            JsonNode root = xmlMapper.readTree(xml);
            JsonNode responses = root.get("response");
            if (responses == null) return hrefs;

            if (!responses.isArray()) responses = xmlMapper.createArrayNode().add(responses);

            for (JsonNode resp : responses) {
                String href = resp.path("href").asText(null);
                if (href == null) continue;

                JsonNode propstats = resp.get("propstat");
                if (propstats == null) continue;
                if (!propstats.isArray()) propstats = xmlMapper.createArrayNode().add(propstats);

                for (JsonNode ps : propstats) {
                    String contentType = ps.path("prop").path("getcontenttype").asText("");
                    if (contentType.contains("text/calendar")) {
                        hrefs.add(href);
                        break;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse calendar hrefs", e);
        }
        return hrefs;
    }
}
