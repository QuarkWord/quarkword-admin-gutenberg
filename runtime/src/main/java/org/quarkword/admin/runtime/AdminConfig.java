package org.quarkword.admin.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

/** Settings of the admin screens, read when the application starts. */
@ConfigMapping(prefix = "quarkword.admin")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface AdminConfig {

    /**
     * The path of the admin screens below each site's path, with a leading and a trailing slash: the shell's lists live
     * at {@code <site path><path>edit.php} and the editor at {@code <site path><path>post.php}.
     */
    @WithDefault("/wp-admin/")
    String path();

    /** How the shell signs its users in. */
    Oidc oidc();

    /** How often the editor tells the server that its user still holds the post's lock. */
    @WithDefault("60s")
    Duration lockRefreshInterval();

    /** The OpenID Connect client the browser signs in with (authorization code with PKCE). */
    interface Oidc {

        /** The OpenID Connect provider; when unset, the application's {@code quarkus.oidc.auth-server-url}. */
        Optional<String> authority();

        /** The public client the shell signs in as. */
        @WithDefault("quarkword-admin")
        String clientId();

        /** The scopes the shell asks for. */
        @WithDefault("openid profile email")
        String scope();
    }
}
