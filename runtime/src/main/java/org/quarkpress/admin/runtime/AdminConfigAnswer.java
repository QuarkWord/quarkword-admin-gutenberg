package org.quarkpress.admin.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quarkpress.restwp.runtime.RestWpConfig;

/**
 * What the shell reads from {@code quarkpress-admin/config.json} before anything else: the admin path, the REST API's
 * path, the sign-in client's settings, how often the editor refreshes its post lock (in seconds) and the WordPress
 * release whose editor the jar carries. The same answer for every site and user: the shell computes its site's URLs
 * from its own address. The sign-in server is {@code quarkpress.admin.oidc.authority}, else the application's own
 * OIDC server; without either the application does not start.
 */
@ApplicationScoped
public class AdminConfigAnswer {

    @Inject
    AdminConfig config;

    @Inject
    RestWpConfig rest;

    @Inject
    AssetRegistry registry;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    Optional<String> authServerUrl;

    private volatile String json;

    /** Builds the answer when the application starts, so that a missing sign-in server stops the start. */
    void start(@Observes StartupEvent event) {
        json();
    }

    /** The answer's JSON text. */
    public String json() {
        String answer = json;
        if (answer == null) {
            answer = build();
            json = answer;
        }
        return answer;
    }

    private String build() {
        String authority = config.oidc()
                .authority()
                .or(() -> authServerUrl)
                .orElseThrow(() -> new IllegalStateException(
                        "QuarkPress admin: set quarkpress.admin.oidc.authority or quarkus.oidc.auth-server-url"));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("adminPath", config.path());
        root.put("restPath", rest.path());
        ObjectNode oidc = root.putObject("oidc");
        oidc.put("authority", authority);
        oidc.put("clientId", config.oidc().clientId());
        oidc.put("scope", config.oidc().scope());
        root.put("lockRefreshInterval", config.lockRefreshInterval().toSeconds());
        root.put("wordpress", registry.version());
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
