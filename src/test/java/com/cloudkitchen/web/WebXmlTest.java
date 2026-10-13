package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/** The production deployment descriptor (SDD 10.1, 10.3, 12.3, 12.8, D-077), read as XML without starting a server. */
class WebXmlTest {

    private static final Path DESCRIPTOR = Path.of("src", "main", "webapp", "WEB-INF", "web.xml");
    private static Document doc;

    @BeforeAll
    static void parse() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        doc = factory.newDocumentBuilder().parse(Files.newInputStream(DESCRIPTOR));
    }

    private static List<String> texts(String tag) {
        List<String> out = new ArrayList<>();
        NodeList nodes = doc.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add(nodes.item(i).getTextContent().trim());
        }
        return out;
    }

    private static Element only(String tag) {
        NodeList nodes = doc.getElementsByTagName(tag);
        assertEquals(1, nodes.getLength(), tag);
        return (Element) nodes.item(0);
    }

    @Test
    void theFilterChainRunsEncodingThenSecurityHeaders() {
        NodeList declared = doc.getElementsByTagName("filter");
        List<String> names = new ArrayList<>();
        for (int i = 0; i < declared.getLength(); i++) {
            names.add(((Element) declared.item(i)).getElementsByTagName("filter-name").item(0).getTextContent().trim());
        }
        assertEquals(List.of("EncodingFilter", "SecurityHeadersFilter", "CsrfFilter", "AuthFilter", "RoleFilter"), names,
                "SDD 10.3: Encoding, SecurityHeaders, Csrf, Auth, Role");
        NodeList mappings = doc.getElementsByTagName("filter-mapping");
        List<String> order = new ArrayList<>();
        for (int i = 0; i < mappings.getLength(); i++) {
            Element m = (Element) mappings.item(i);
            order.add(m.getElementsByTagName("filter-name").item(0).getTextContent().trim());
            assertEquals("/*", m.getElementsByTagName("url-pattern").item(0).getTextContent().trim());
        }
        assertEquals(List.of("EncodingFilter", "SecurityHeadersFilter", "CsrfFilter", "AuthFilter", "RoleFilter"), order);
    }

    @Test
    void everyDeclaredFilterAndServletClassExists() throws Exception {
        List<String> classes = new ArrayList<>(texts("filter-class"));
        classes.addAll(texts("servlet-class"));
        classes.addAll(texts("listener-class"));
        assertFalse(classes.isEmpty());
        for (String name : classes) {
            Class.forName(name);
        }
    }

    @Test
    void thePhase1SpikeProbesAreNotPartOfTheProductionDescriptor() {
        String text = texts("filter-class").toString() + texts("servlet-class");
        assertFalse(text.contains("Spike"), text);
        assertFalse(Files.exists(Path.of("src", "main", "webapp", "spike.jsp")), "the spike page lives in test scope (D-076)");
    }

    @Test
    void sessionPolicyIs30MinutesCookieOnlyHttpOnlySameSiteLax() {
        assertEquals("30", only("session-timeout").getTextContent().trim());
        assertEquals(List.of("COOKIE"), texts("tracking-mode"));
        assertEquals("true", only("http-only").getTextContent().trim());
        assertEquals(List.of("SameSite"), texts("attribute-name"));
        assertEquals(List.of("Lax"), texts("attribute-value"));
    }

    @Test
    void everyRequiredErrorCodeTheThrowableAndAnyOtherStatusReachTheErrorServlet() {
        NodeList pages = doc.getElementsByTagName("error-page");
        List<String> codes = new ArrayList<>();
        boolean throwable = false;
        boolean fallback = false;
        for (int i = 0; i < pages.getLength(); i++) {
            Element page = (Element) pages.item(i);
            assertEquals("/error", page.getElementsByTagName("location").item(0).getTextContent().trim());
            boolean hasCode = page.getElementsByTagName("error-code").getLength() > 0;
            boolean hasType = page.getElementsByTagName("exception-type").getLength() > 0;
            if (hasCode) {
                codes.add(page.getElementsByTagName("error-code").item(0).getTextContent().trim());
            } else if (hasType) {
                throwable |= "java.lang.Throwable".equals(page.getElementsByTagName("exception-type").item(0).getTextContent().trim());
            } else {
                fallback = true;
            }
        }
        assertEquals(List.of("400", "403", "404", "405", "500"), codes);
        assertTrue(throwable, "uncaught exceptions");
        assertTrue(fallback, "the default error page covers every other status (D-077)");
    }

    @Test
    void jspsAreUtf8AndMayNotUseScripting() {
        Element group = only("jsp-property-group");
        assertEquals("true", group.getElementsByTagName("scripting-invalid").item(0).getTextContent().trim());
        assertEquals("UTF-8", group.getElementsByTagName("page-encoding").item(0).getTextContent().trim());
        List<String> patterns = new ArrayList<>();
        NodeList nodes = group.getElementsByTagName("url-pattern");
        for (int i = 0; i < nodes.getLength(); i++) {
            patterns.add(nodes.item(i).getTextContent().trim());
        }
        assertEquals(List.of("*.jsp", "*.jspf", "*.tag"), patterns);
    }

    @Test
    void requestAndResponseEncodingAreUtf8AndTheDescriptorIsComplete() {
        assertEquals("UTF-8", only("request-character-encoding").getTextContent().trim());
        assertEquals("UTF-8", only("response-character-encoding").getTextContent().trim());
        assertEquals("true", doc.getDocumentElement().getAttribute("metadata-complete"));
        assertEquals("6.0", doc.getDocumentElement().getAttribute("version"));
    }
}
