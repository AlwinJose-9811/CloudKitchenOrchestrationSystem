package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** FR-WEB-01, FR-WEB-08, FR-WEB-12: the views contain no scripting, no raw output, no inline script or style, and no SQL. */
class JspRulesTest {

    private static final Path WEBAPP = Path.of("src", "main", "webapp");

    private static List<Path> viewFiles() throws IOException {
        try (Stream<Path> files = Files.walk(WEBAPP.resolve("WEB-INF"))) {
            return files.filter(p -> p.toString().matches(".*\\.(jsp|jspf|tag)$")).sorted().toList();
        }
    }

    private static String text(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void thereAreViewsToCheck() throws IOException {
        List<String> names = viewFiles().stream().map(p -> p.getFileName().toString()).toList();
        for (String expected : List.of("header.jspf", "footer.jspf", "csrf.tag", "statusBadge.tag", "400.jsp", "403.jsp", "404.jsp", "405.jsp", "500.jsp", "login.jsp", "dashboard.jsp", "list.jsp", "detail.jsp")) {
            assertTrue(names.contains(expected), expected + " in " + names);
        }
    }

    @Test
    void noScriptletsOrExpressionsOnlyDirectivesAndComments() throws IOException {
        Pattern scriptlet = Pattern.compile("<%(?![@-])");
        List<String> offenders = new ArrayList<>();
        for (Path file : viewFiles()) {
            if (scriptlet.matcher(text(file)).find()) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void everyExpressionIsEscapedOrUsedOnlyInsideATag() throws IOException {
        // remove the tags that are allowed to hold an expression, then no "${" may remain
        Pattern allowed = Pattern.compile("<c:out\\s[^>]*>|<c:(if|when|forEach|set|url|param)\\s[^>]*>|<%@[^%]*%>|<t:[a-zA-Z]+\\s[^>]*>");
        List<String> offenders = new ArrayList<>();
        for (Path file : viewFiles()) {
            String rest = allowed.matcher(text(file)).replaceAll("");
            if (rest.contains("${")) {
                offenders.add(file + ": " + rest.lines().filter(l -> l.contains("${")).findFirst().orElse(""));
            }
        }
        assertEquals(List.of(), offenders, "raw EL output; use <c:out> or fn:escapeXml (FR-WEB-08)");
    }

    @Test
    void noInlineScriptInlineStyleOrEventHandlerBecauseTheCspForbidsThem() throws IOException {
        Pattern forbidden = Pattern.compile("<script|\\sstyle\\s*=|\\son[a-z]+\\s*=|javascript:", Pattern.CASE_INSENSITIVE);
        List<String> offenders = new ArrayList<>();
        for (Path file : viewFiles()) {
            if (forbidden.matcher(text(file)).find()) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void pagesDoNotOpenASessionOnTheirOwn() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : viewFiles()) {
            String name = file.getFileName().toString();
            if (name.endsWith(".jsp") && !text(file).contains("session=\"false\"")) {
                offenders.add(file.toString());
            }
        }
        assertEquals(List.of(), offenders, "a JSP creates a session by default; controllers manage sessions explicitly (D-074)");
    }

    @Test
    void viewsHoldNoSqlOrJavaTypes() throws IOException {
        Pattern forbidden = Pattern.compile("<sql:|jakarta\\.tags\\.sql|java\\.sql|jdbc:", Pattern.CASE_INSENSITIVE);
        for (Path file : viewFiles()) {
            assertFalse(forbidden.matcher(text(file)).find(), file.toString());
        }
    }

    @Test
    void viewsAreNotReachableByUrl() throws IOException {
        for (Path file : viewFiles()) {
            assertTrue(file.startsWith(WEBAPP.resolve("WEB-INF")), file + " must be under WEB-INF");
        }
        try (Stream<Path> top = Files.list(WEBAPP)) {
            assertEquals(List.of("META-INF", "WEB-INF", "static"), top.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }
}
