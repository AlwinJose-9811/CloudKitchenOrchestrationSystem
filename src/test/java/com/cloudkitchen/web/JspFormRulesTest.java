package com.cloudkitchen.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudkitchen.web.support.RoutePolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Acceptance review of every form in the views (C6, SDD 10.1, 10.3): each {@code POST} form carries the CSRF input and posts
 * to a {@code POST} route of the route table; each {@code GET} form goes to a {@code GET} route that changes nothing and has
 * no CSRF input; every state-changing route of the table has at least one form that reaches it (login is a form of its own
 * page, the others sit on the pages that offer them).
 */
class JspFormRulesTest {

    private static final Path VIEWS = Path.of("src", "main", "webapp", "WEB-INF");
    private static final Pattern FORM = Pattern.compile("<form\\b((?:<c:url[^>]*>|[^>])*)>(.*?)</form>", Pattern.DOTALL);
    private static final Pattern METHOD = Pattern.compile("method=\"(get|post)\"");
    private static final Pattern ACTION = Pattern.compile("action=\"<c:url value='([^']+)'/>\"");

    private record Form(String file, String method, String action, String body) {
    }

    private static List<Form> forms() throws IOException {
        List<Form> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(VIEWS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".jsp") || f.toString().endsWith(".jspf") || f.toString().endsWith(".tag")).toList()) {
                Matcher m = FORM.matcher(Files.readString(file));
                while (m.find()) {
                    Matcher method = METHOD.matcher(m.group(1));
                    Matcher action = ACTION.matcher(m.group(1));
                    assertTrue(method.find() && action.find(), file + ": a form needs a literal method and a c:url action: " + m.group(1));
                    out.add(new Form(file.getFileName().toString(), method.group(1).toUpperCase(), action.group(1), m.group(2)));
                }
            }
        }
        return out;
    }

    @Test
    void thereAreFormsToCheck() throws IOException {
        assertTrue(forms().size() >= 15, "the views hold the forms of C1 to C5 (15 when written): " + forms().size());
    }

    @Test
    void everyPostFormCarriesTheCsrfInputAndPostsToAPostRouteOfTheTable() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Form form : forms()) {
            if (!form.method().equals("POST")) {
                continue;
            }
            if (!form.body().contains("<t:csrf/>")) {
                offenders.add(form.file() + " POST " + form.action() + ": no CSRF input");
            }
            if (RoutePolicy.standard().find(form.action(), "POST").filter(RoutePolicy.Route::isPost).isEmpty()) {
                offenders.add(form.file() + " POST " + form.action() + ": not a POST route of the table");
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void everyGetFormGoesToAGetRouteOfTheTableAndHasNoCsrfInput() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Form form : forms()) {
            if (!form.method().equals("GET")) {
                continue;
            }
            if (form.body().contains("_csrf") || form.body().contains("<t:csrf/>")) {
                offenders.add(form.file() + " GET " + form.action() + ": a token must never travel in a URL");
            }
            if (RoutePolicy.standard().find(form.action(), "GET").filter(r -> r.method().equals("GET")).isEmpty()) {
                offenders.add(form.file() + " GET " + form.action() + ": not a GET route of the table");
            }
        }
        assertEquals(List.of(), offenders);
    }

    @Test
    void everyPostRouteOfTheTableIsReachedByAFormOfTheViews() throws IOException {
        List<String> posted = forms().stream().filter(f -> f.method().equals("POST")).map(Form::action).distinct().toList();
        List<String> missing = new ArrayList<>();
        for (RoutePolicy.Route route : RoutePolicy.standard().allRoutes()) {
            if (route.isPost() && !posted.contains(route.path())) {
                missing.add(route.path());
            }
        }
        assertEquals(List.of(), missing, "a state-changing route without any form would be unreachable from the pages");
        assertFalse(posted.isEmpty());
    }
}
