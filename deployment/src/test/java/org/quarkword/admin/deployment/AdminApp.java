package org.quarkword.admin.deployment;

import io.quarkus.test.QuarkusExtensionTest;

/**
 * Test applications with the admin extension and the REST module it serves (and so posts, taxonomy and security) on
 * QuarkWord's datasource; Dev Services starts the database. No Keycloak: the default OIDC tenant is off, and its
 * server address is set only so that the extension's start-up checks find one.
 */
public final class AdminApp {

    private AdminApp() {}

    /** An application whose {@code quarkword} datasource Dev Services starts on {@link TestDb#kind()}. */
    public static QuarkusExtensionTest app(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(
                        jar -> jar.addClasses(classes).addClasses(TestDb.class, AdminApp.class, Failures.class))
                .overrideConfigKey("quarkus.datasource.\"quarkword\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.oidc.auth-server-url", "http://kc.test/realms/q")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
    }

    /** For tests whose build or start must fail: no database is started. */
    public static QuarkusExtensionTest buildOnly(Class<?>... classes) {
        return app(classes).overrideConfigKey("quarkus.datasource.\"quarkword\".devservices.enabled", "false");
    }
}
