package org.quarkpress.it.admin;

import static io.restassured.RestAssured.given;

import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import io.restassured.specification.RequestSpecification;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The flows' fixtures, written over HTTP to the test application's setup resource ({@code /it/...}, or
 * {@code /<site>/it/...} for a subdirectory site) and its REST API, with each user's Keycloak token and the main site's
 * host. Every method waits for its answer and checks its status.
 */
final class ItSetup {

    /** Obtains tokens from the Dev Services Keycloak (in an IT, Quarkus hands it the Dev Services context). */
    private final KeycloakTestClient keycloak = new KeycloakTestClient();

    private final Map<String, String> tokens = new HashMap<>();

    private final Map<String, Long> ids = new HashMap<>();

    /** A request to the main site's host as {@code user} (anonymous when null). */
    RequestSpecification as(String user) {
        RequestSpecification request = given().header("Host", "localhost");
        return user == null ? request : request.auth().oauth2(token(user));
    }

    private String token(String user) {
        return tokens.computeIfAbsent(user, keycloak::getAccessToken);
    }

    /** The user's QuarkPress id. */
    long userId(String name) {
        Long known = ids.get(name);
        if (known != null) {
            return known;
        }
        long id = as(name).get("/it/me")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("user");
        ids.put(name, id);
        return id;
    }

    /** Gives the user the role on the main site, as alice. */
    void role(String name, String role) {
        membership("/", name, role);
    }

    /** Creates a site at {@code path} (such as {@code /fv01/}) on the main site's domain, as alice; answers its id. */
    long createSite(String path, String title) {
        return as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("path", path, "title", title))
                .post("/it/sites")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    /** Gives the user the role on the site at {@code sitePath} ({@code /} for the main site), as alice. */
    void membership(String sitePath, String name, String role) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId(name), "role", role))
                .post(sitePath + "it/memberships")
                .then()
                .statusCode(200);
    }

    /** A post of the main site written by {@code asUser}; answers its id. */
    long post(String asUser, String type, String status, String title) {
        return post(asUser, type, status, title, null, null);
    }

    /** A post of the main site written by {@code asUser}, with content and a parent (each optional); its id. */
    long post(String asUser, String type, String status, String title, String content, Long parent) {
        return postOn("/", asUser, type, status, title, content, parent);
    }

    /** A post of the site at {@code sitePath} written by {@code asUser}; answers its id. */
    long postOn(String sitePath, String asUser, String type, String status, String title) {
        return postOn(sitePath, asUser, type, status, title, null, null);
    }

    private long postOn(
            String sitePath, String asUser, String type, String status, String title, String content, Long parent) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("status", status);
        body.put("title", title);
        if (content != null) {
            body.put("content", content);
        }
        if (parent != null) {
            body.put("parent", parent);
        }
        return as(asUser)
                .contentType(ContentType.JSON)
                .body(body)
                .post(sitePath + "it/posts")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    /** An autosave of the post by {@code asUser}; answers the autosave's id. */
    long autosave(long post, String asUser, String title, String content) {
        return as(asUser)
                .contentType(ContentType.JSON)
                .body(Map.of("post", post, "title", title, "content", content))
                .post("/it/autosaves")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    /** Sets the post's last-modified time of the main site to {@code at} (UTC, {@code 2026-10-18T10:00:00}), as alice. */
    void backdate(long post, String at) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("posts", List.of(Map.of("id", post, "at", at, "date", false))))
                .put("/it/backdate")
                .then()
                .statusCode(200);
    }

    /** Sets the post's edit lock to the user's, taken {@code age} seconds ago (as alice); answers the lock's value. */
    String lock(long post, String name, long age) {
        return as("alice")
                .contentType(ContentType.JSON)
                .body(Map.of("user", userId(name), "age", age))
                .put("/it/locks/" + post)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getString("value");
    }

    /** Sets an option of the main site to a JSON value, as alice. */
    void option(String name, String json) {
        optionOn("/", name, json);
    }

    /** Sets an option of the site at {@code sitePath} to a JSON value, as alice. */
    void optionOn(String sitePath, String name, String json) {
        as("alice")
                .contentType(ContentType.JSON)
                .body(json)
                .put(sitePath + "it/options/" + name)
                .then()
                .statusCode(200);
    }

    /** The REST answer of {@code path} below the site's REST root (such as {@code wp/v2/posts/1}) as {@code asUser}. */
    JsonPath rest(String asUser, String sitePath, String path) {
        return as(asUser)
                .get(sitePath + "wp-json/" + path)
                .then()
                .statusCode(200)
                .extract()
                .jsonPath();
    }

    /** Deletes the post for good, as alice. */
    void deletePost(long id) {
        as("alice").delete("/it/posts/" + id).then().statusCode(200);
    }

    /** Uploads a PNG file to the main site's media library over REST as {@code asUser}; answers the attachment's id. */
    long upload(String asUser, Path png) {
        return as(asUser)
                .multiPart("file", png.toFile(), "image/png")
                .post("/wp-json/wp/v2/media")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    /** Moves the post to the trash over REST as {@code asUser}. */
    void trash(String asUser, String base, long id) {
        as(asUser).delete("/wp-json/wp/v2/" + base + "/" + id).then().statusCode(200);
    }

    /** The post as {@code asUser} reads it over REST in the edit context; empty when the answer is 404. */
    Optional<String> statusOf(String asUser, String base, long id) {
        var response = as(asUser).get("/wp-json/wp/v2/" + base + "/" + id + "?context=edit");
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        response.then().statusCode(200);
        return Optional.of(response.jsonPath().getString("status"));
    }

    /** How many posts the REST list counts for {@code asUser} with these statuses (its {@code X-WP-Total}). */
    int total(String asUser, String base, String statuses) {
        String total = as(asUser)
                .get("/wp-json/wp/v2/" + base + "?context=edit&per_page=1&status=" + statuses)
                .then()
                .statusCode(200)
                .extract()
                .header("X-WP-Total");
        return Integer.parseInt(total);
    }
}
